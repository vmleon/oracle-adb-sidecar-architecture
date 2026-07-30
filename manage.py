#!/usr/bin/env python3
"""
Live AI Hub (AI Data Gateway) — management CLI

Usage:
    ./manage.py setup      # Configure OCI, write .env, render terraform.tfvars
    ./manage.py build      # Build the backend jar and the frontend dist
    ./manage.py provision  # terraform init (with retry) + apply
    ./manage.py status     # Endpoints, SSH commands, bootstrap progress
    ./manage.py reset      # Re-run database provisioning on the ops host
    ./manage.py clean      # terraform destroy + delete local artifacts
"""

import configparser
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
from pathlib import Path

try:
    import click
except ImportError:
    sys.exit("click is required. Run: pip install -r requirements.txt")

from rich.console import Console
from rich.panel import Panel

try:
    from InquirerPy import inquirer
except ImportError:
    inquirer = None

try:
    from jinja2 import Template
except ImportError:
    Template = None

try:
    from dotenv import load_dotenv, set_key
except ImportError:
    load_dotenv = None
    set_key = None

console = Console()

PROJECT_ROOT = Path(__file__).parent.resolve()
ENV_FILE = PROJECT_ROOT / ".env"
TF_DIR = PROJECT_ROOT / "deploy" / "terraform"
BACKEND_DIR = PROJECT_ROOT / "src" / "backend"
FRONTEND_DIR = PROJECT_ROOT / "src" / "frontend"
BACKEND_JAR = BACKEND_DIR / "build" / "libs" / "backend-1.0.0.jar"
FRONTEND_DIST = FRONTEND_DIR / "dist"


# ============================================================================
# ENV HELPERS
# ============================================================================

def env_load():
    if load_dotenv and ENV_FILE.exists():
        load_dotenv(ENV_FILE, override=True)


def env_save(key: str, value: str):
    """Write one key to .env, leaving the rest of the file intact."""
    if not ENV_FILE.exists():
        ENV_FILE.touch()
    if set_key:
        set_key(str(ENV_FILE), key, value)
        return
    lines = [
        line for line in ENV_FILE.read_text().splitlines()
        if not line.startswith(f"{key}=")
    ]
    lines.append(f'{key}="{value}"')
    ENV_FILE.write_text("\n".join(lines) + "\n")


def env_get(key: str, default: str = None) -> str:
    env_load()
    return os.getenv(key, default)


# ============================================================================
# PROMPT HELPERS (InquirerPy when available, plain prompts otherwise)
# ============================================================================

def _select(message, choices, default=None):
    if inquirer:
        return inquirer.select(message=message, choices=choices, default=default).execute()
    console.print(f"[bold]{message}[/bold]")
    for i, c in enumerate(choices, 1):
        console.print(f"  {i}. {c}")
    return choices[click.prompt("Number", type=int, default=1) - 1]


def _fuzzy(message, choices, default=None):
    if inquirer:
        return inquirer.fuzzy(
            message=message, choices=choices, default=default, max_height="60%"
        ).execute()
    return _select(message, choices, default)


def _text(message, default=None):
    if inquirer:
        return inquirer.text(message=message, default=default or "").execute()
    return click.prompt(message, default=default)


# ============================================================================
# TOOL PREFLIGHT
# ============================================================================

def _oci_sdk_available() -> bool:
    try:
        import oci  # noqa: F401
        return True
    except ImportError:
        return False


def _print_tools(required=("terraform", "ssh"), need_oci_sdk=True) -> bool:
    """Show which external tools are on PATH so missing ones surface up front."""
    def mark(ok):
        return "[green]found[/green]" if ok else "[red]MISSING[/red]"

    parts = [f"{t} {mark(bool(shutil.which(t)))}" for t in required]
    if need_oci_sdk:
        parts.append(f"oci SDK {mark(_oci_sdk_available())}")
    console.print("Tooling: " + " · ".join(parts) + "\n")

    missing = [t for t in required if not shutil.which(t)]
    if need_oci_sdk and not _oci_sdk_available():
        missing.append("oci SDK (pip install -r requirements.txt)")
    if missing:
        console.print(f"[red]Missing:[/red] {', '.join(missing)}")
        return False
    return True


def _check_version(cmd, args, name, min_major) -> bool:
    try:
        result = subprocess.run([cmd] + args, capture_output=True, text=True, timeout=15)
        output = result.stdout + result.stderr
        match = re.search(r"(\d+)\.\d+", output)
        if not match:
            console.print(f"[red]Error:[/red] Could not parse {name} version from: {output.strip()}")
            return False
        major = int(match.group(1))
        if major < min_major:
            console.print(f"[red]Error:[/red] {name} {major} found, need {min_major}+")
            return False
        console.print(f"  {name} {match.group(0)} [green]OK[/green]")
        return True
    except FileNotFoundError:
        console.print(f"[red]Error:[/red] {name} not found. Install {name} {min_major}+ and try again.")
        return False


# ============================================================================
# OCI HELPERS
# ============================================================================

def _read_oci_config():
    oci_config_path = Path.home() / ".oci" / "config"
    if not oci_config_path.exists():
        console.print(f"[red]Error:[/red] OCI config not found at {oci_config_path}")
        console.print("Configure it first: [cyan]oci setup config[/cyan]")
        sys.exit(1)

    config = configparser.ConfigParser()
    config.read(oci_config_path)
    profiles = list(config.sections())
    if config.defaults():
        profiles.insert(0, "DEFAULT")
    return profiles, config


def _list_regions(oci_config):
    import oci
    try:
        identity_client = oci.identity.IdentityClient(oci_config)
        tenancy_id = oci_config["tenancy"]
        home_region_key = identity_client.get_tenancy(tenancy_id).data.home_region_key
        subscriptions = identity_client.list_region_subscriptions(tenancy_id).data
        regions = [
            {"name": s.region_name, "is_home": s.region_key == home_region_key}
            for s in subscriptions
        ]
        regions.sort(key=lambda r: (not r["is_home"], r["name"]))
        return regions
    except Exception as e:
        console.print(f"[yellow]Warning:[/yellow] Could not fetch regions: {e}")
        return None


def _list_compartments(oci_config):
    import oci
    try:
        identity_client = oci.identity.IdentityClient(oci_config)
        tenancy_id = oci_config["tenancy"]
        tenancy = identity_client.get_compartment(tenancy_id).data
        compartments = [{"name": f"{tenancy.name} (root)", "id": tenancy_id}]
        response = oci.pagination.list_call_get_all_results(
            identity_client.list_compartments,
            compartment_id=tenancy_id,
            compartment_id_in_subtree=True,
            access_level="ACCESSIBLE",
        )
        compartments += [
            {"name": c.name, "id": c.id}
            for c in response.data if c.lifecycle_state == "ACTIVE"
        ]
        return compartments
    except Exception as e:
        console.print(f"[yellow]Warning:[/yellow] Could not fetch compartments: {e}")
        return None


def _passwords_exist() -> bool:
    return all(env_get(k) for k in (
        "ADB_ADMIN_PASSWORD", "ORACLE_DB_PASSWORD",
        "POSTGRES_DB_PASSWORD", "MONGO_DB_PASSWORD",
    ))


def _generate_password(length=20):
    """Oracle-compliant: starts with a letter, 2+ specials, 2+ digits."""
    letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    digits = "0123456789"
    specials = "#_-"

    password = [secrets.choice(letters)]
    password += [secrets.choice(specials) for _ in range(2)]
    password += [secrets.choice(digits) for _ in range(2)]
    alphabet = letters + digits + specials
    password += [secrets.choice(alphabet) for _ in range(length - 5)]

    tail = password[1:]
    secrets.SystemRandom().shuffle(tail)
    password[1:] = tail
    return "".join(password)


# ============================================================================
# TERRAFORM HELPERS
# ============================================================================

def _tf(*args, capture=False):
    return subprocess.run(
        ["terraform", f"-chdir={TF_DIR}", *args],
        capture_output=capture, text=True,
    )


def _tf_output(name: str):
    result = _tf("output", "-raw", name, capture=True)
    if result.returncode == 0 and result.stdout.strip():
        return result.stdout.strip()
    return None


def _terraform_init(attempts: int = 3) -> bool:
    """`terraform init`, retried on transient failures (provider registry TLS
    timeouts, CDN blips) with a short backoff."""
    for i in range(1, attempts + 1):
        console.print(f"[bold]terraform init[/bold] (attempt {i}/{attempts})...")
        if _tf("init", "-input=false").returncode == 0:
            return True
        if i < attempts:
            wait = 5 * i
            console.print(
                f"[yellow]init failed (often a transient network issue); "
                f"retrying in {wait}s...[/yellow]"
            )
            time.sleep(wait)
    console.print(
        "[red]terraform init failed after retries. Check network access to the "
        "provider registries and retry.[/red]"
    )
    return False


def _render_tfvars() -> bool:
    template_file = TF_DIR / "terraform.tfvars.j2"
    if not Template:
        console.print("[red]jinja2 is not installed; cannot render terraform.tfvars.[/red]")
        console.print("Run: pip install -r requirements.txt")
        return False
    if not template_file.exists():
        console.print(f"[red]Error:[/red] Template not found: {template_file}")
        return False

    tfvars_file = TF_DIR / "terraform.tfvars"
    tfvars_file.write_text(Template(template_file.read_text()).render(
        profile=env_get("OCI_PROFILE"),
        tenancy_ocid=env_get("OCI_TENANCY_OCID"),
        compartment_ocid=env_get("OCI_COMPARTMENT_OCID"),
        region=env_get("OCI_REGION"),
        home_region=env_get("OCI_HOME_REGION"),
        genai_region=env_get("OCI_GENAI_REGION"),
        genai_compartment_id=env_get("OCI_GENAI_COMPARTMENT_ID"),
        project_name=env_get("PROJECT_NAME"),
        adb_admin_password=env_get("ADB_ADMIN_PASSWORD"),
        oracle_db_password=env_get("ORACLE_DB_PASSWORD"),
        postgres_db_password=env_get("POSTGRES_DB_PASSWORD"),
        mongo_db_password=env_get("MONGO_DB_PASSWORD"),
        ssh_public_key=env_get("SSH_PUBLIC_KEY"),
        ssh_private_key_path=env_get("SSH_PRIVATE_KEY_PATH"),
        create_identity_resources=env_get("CREATE_IDENTITY_RESOURCES", "true"),
    ))
    tfvars_file.chmod(0o600)
    console.print(f"[green]Generated:[/green] {tfvars_file}")
    return True


# ============================================================================
# COMMANDS
# ============================================================================

@click.group()
def cli():
    """Live AI Hub (AI Data Gateway) manager."""


@cli.command()
def setup():
    """Configure OCI, write .env, render terraform.tfvars."""
    console.print("[bold]Live AI Hub (AI Data Gateway) — Setup[/bold]\n")

    if not _print_tools():
        sys.exit(1)

    profiles, oci_config_parser = _read_oci_config()
    profile = _select("OCI profile:", profiles, default=profiles[0] if profiles else None)

    profile_config = oci_config_parser[profile]
    tenancy_ocid = profile_config.get("tenancy")
    if not tenancy_ocid:
        console.print(f"[red]Error:[/red] No tenancy OCID in profile '{profile}'")
        sys.exit(1)

    sdk_config = {
        "user": profile_config.get("user"),
        "key_file": profile_config.get("key_file"),
        "fingerprint": profile_config.get("fingerprint"),
        "tenancy": tenancy_ocid,
        "region": profile_config.get("region", "us-phoenix-1"),
    }

    console.print("\nFetching subscribed regions and compartments...")
    regions = _list_regions(sdk_config)
    if regions:
        labels = [f"{r['name']} (home)" if r["is_home"] else r["name"] for r in regions]
        region = _select("Region:", labels, default=labels[0]).replace(" (home)", "")
        # IAM writes only land in the home region; identity.tf targets it explicitly.
        home_region = next(
            (r["name"] for r in regions if r["is_home"]), region
        )
    else:
        region = _text("Region", default=sdk_config["region"])
        home_region = _text("Home region (IAM writes go here)", default=region)
    sdk_config["region"] = region

    compartments = _list_compartments(sdk_config)
    if compartments:
        comp_map = {c["name"]: c["id"] for c in compartments}
        selected = _fuzzy("Compartment (type to search):", list(comp_map))
        compartment_ocid = comp_map[selected]
    else:
        compartment_ocid = _text("Compartment OCID")
        comp_map = {}

    console.print("\n[bold]OCI GenAI (used by the Select AI profiles and agents)[/bold]")
    if regions:
        default_choice = next(
            (l for l in labels if l.replace(" (home)", "") == region), labels[0]
        )
        genai_region = _select("GenAI region:", labels, default=default_choice).replace(" (home)", "")
    else:
        genai_region = _text("GenAI region", default=region)

    if compartments:
        default_comp = next(
            (c["name"] for c in compartments if c["id"] == compartment_ocid),
            compartments[0]["name"],
        )
        genai_compartment_id = comp_map[
            _fuzzy("GenAI compartment (type to search):", list(comp_map), default=default_comp)
        ]
    else:
        genai_compartment_id = _text("GenAI compartment OCID", default=compartment_ocid)

    ssh_dir = Path.home() / ".ssh"
    ssh_keys = (
        sorted(
            f.name for f in ssh_dir.iterdir()
            if f.is_file() and not f.suffix and f.with_suffix(".pub").exists()
        )
        if ssh_dir.is_dir() else []
    )
    if ssh_keys:
        ssh_private_key_path = str(ssh_dir / _fuzzy("SSH private key:", ssh_keys))
    else:
        ssh_private_key_path = _text("SSH private key path")

    ssh_public_key_path = Path(ssh_private_key_path + ".pub")
    if ssh_public_key_path.exists():
        ssh_public_key = ssh_public_key_path.read_text().strip()
    else:
        ssh_public_key = _text("SSH public key (paste content)")

    project_name = _text("Project name (used for OCI resource naming):", default="aidatagateway")

    console.print(
        "\n[bold]Resource principal[/bold]\n"
        "The gateway authenticates to OCI GenAI and Object Storage as itself, which "
        "needs a dynamic group and a policy in the tenancy root. Creating them "
        "requires tenancy-level IAM rights; answer no if you don't have them and an "
        "administrator will apply the statements instead."
    )
    create_identity = click.confirm(
        "Create the dynamic group and policy?", default=True
    )

    console.print(Panel(
        f"Profile:           {profile}\n"
        f"Region:            {region}\n"
        f"Home region:       {home_region} (IAM writes)\n"
        f"Compartment:       {compartment_ocid}\n"
        f"GenAI region:      {genai_region}\n"
        f"GenAI compartment: {genai_compartment_id}\n"
        f"SSH key:           {ssh_private_key_path}\n"
        f"Project name:      {project_name}\n"
        f"Identity objects:  {'created by Terraform' if create_identity else 'left to an administrator'}\n"
        f"DB passwords:      {'reused from .env' if _passwords_exist() else 'generated (adb/oracle/postgres/mongo)'}",
        title="Configuration summary",
    ))
    if not click.confirm("Save this configuration?", default=True):
        console.print("[yellow]Setup cancelled — nothing written.[/yellow]")
        sys.exit(0)

    for key, value in {
        "OCI_PROFILE": profile,
        "OCI_TENANCY_OCID": tenancy_ocid,
        "OCI_COMPARTMENT_OCID": compartment_ocid,
        "OCI_REGION": region,
        "OCI_GENAI_REGION": genai_region,
        "OCI_GENAI_COMPARTMENT_ID": genai_compartment_id,
        "PROJECT_NAME": project_name,
        "OCI_HOME_REGION": home_region,
        "CREATE_IDENTITY_RESOURCES": "true" if create_identity else "false",
        # Reuse any password already in .env. Re-running setup against a live
        # deployment must not rotate credentials the running databases hold.
        "ADB_ADMIN_PASSWORD": env_get("ADB_ADMIN_PASSWORD") or _generate_password(),
        "ORACLE_DB_PASSWORD": env_get("ORACLE_DB_PASSWORD") or _generate_password(),
        "POSTGRES_DB_PASSWORD": env_get("POSTGRES_DB_PASSWORD") or _generate_password(),
        "MONGO_DB_PASSWORD": env_get("MONGO_DB_PASSWORD") or _generate_password(),
        "SSH_PRIVATE_KEY_PATH": ssh_private_key_path,
        "SSH_PUBLIC_KEY": ssh_public_key,
    }.items():
        env_save(key, value)

    console.print(f"[green]Configuration saved to {ENV_FILE}[/green]")
    if not _render_tfvars():
        sys.exit(1)

    console.print("\nNext step: [bold]./manage.py build[/bold]")


@cli.command()
def build():
    """Build the backend jar and the frontend dist."""
    console.print("[bold]Building backend and frontend...[/bold]\n")

    console.print("Checking tools:")
    ok = True
    ok = _check_version("java", ["--version"], "Java", 23) and ok
    ok = _check_version("node", ["--version"], "Node", 22) and ok
    ok = _check_version("npm", ["--version"], "npm", 10) and ok
    if not ok:
        sys.exit(1)

    for label, cmd, cwd in [
        ("Backend (Gradle)", "./gradlew build -x test", BACKEND_DIR),
        ("Frontend (Angular)", "npm install && npm run build", FRONTEND_DIR),
    ]:
        console.print(f"\n[bold]{label}[/bold]")
        if subprocess.run(cmd, cwd=cwd, shell=True).returncode != 0:
            console.print(f"[red]Error:[/red] {label} failed")
            sys.exit(1)

    console.print("\n[green]Build complete.[/green]")
    console.print("\nNext step: [bold]./manage.py provision[/bold]")


@cli.command()
@click.option("--yes", is_flag=True, help="Skip the terraform apply approval prompt")
def provision(yes):
    """terraform init (with retry) + apply."""
    console.print("[bold]Provision infrastructure[/bold]\n")

    if not _print_tools(required=("terraform",), need_oci_sdk=False):
        sys.exit(1)

    if not (TF_DIR / "terraform.tfvars").exists():
        console.print("[red]terraform.tfvars not found. Run `./manage.py setup` first.[/red]")
        sys.exit(1)

    missing = []
    if not BACKEND_JAR.exists():
        missing.append(f"Backend JAR ({BACKEND_JAR.relative_to(PROJECT_ROOT)})")
    if not FRONTEND_DIST.exists() or not any(FRONTEND_DIST.iterdir()):
        missing.append(f"Frontend dist ({FRONTEND_DIST.relative_to(PROJECT_ROOT)}/)")
    if missing:
        console.print("[red]Build artifacts missing — Terraform uploads them as artifacts:[/red]")
        for m in missing:
            console.print(f"  - {m}")
        console.print("Run [bold]./manage.py build[/bold] first.")
        sys.exit(1)

    if not _terraform_init():
        sys.exit(1)

    if yes:
        console.print("\n[bold]terraform apply[/bold] — auto-approved.\n")
        apply_args = ("apply", "-auto-approve")
    else:
        console.print("\n[bold]terraform apply[/bold] — review the plan and confirm at the prompt.\n")
        apply_args = ("apply",)
    if _tf(*apply_args).returncode != 0:
        console.print("[red]terraform apply did not complete.[/red]")
        sys.exit(1)

    console.print("\n[green]Infrastructure provisioned.[/green]")

    if env_get("CREATE_IDENTITY_RESOURCES", "true").lower() != "true":
        console.print(
            "\n[yellow]Identity objects were not created.[/yellow] Select AI cannot "
            "authenticate until a tenancy administrator applies these statements:\n"
        )
        result = _tf("output", "-raw", "resource_principal_statements", capture=True)
        console.print(result.stdout.strip() or "  (run: terraform output resource_principal_statements)")

    console.print(
        "Cloud-init now runs Ansible on each instance and Liquibase from ops; "
        "this takes several minutes."
    )
    console.print("\nNext step: [bold]./manage.py status[/bold]")


@cli.command()
def status():
    """Show endpoints, SSH commands, and how to follow bootstrap progress."""
    if not shutil.which("terraform"):
        console.print("[red]Error:[/red] terraform not found on PATH.")
        sys.exit(1)

    ops_ip = _tf_output("ops_public_ip")
    lb_ip = _tf_output("lb_public_ip")
    if not ops_ip:
        console.print("[red]Error:[/red] Could not read ops_public_ip from terraform output.")
        console.print("Run [bold]./manage.py provision[/bold] first.")
        sys.exit(1)

    ssh_key = env_get("SSH_PRIVATE_KEY_PATH", "")
    ssh_ops = f"ssh -A -i {ssh_key} opc@{ops_ip}" if ssh_key else f"ssh -A opc@{ops_ip}"
    ssh_add = f"ssh-add {ssh_key}" if ssh_key else "ssh-add"

    console.print(Panel(
        f"Frontend:     http://{lb_ip or 'N/A'}\n"
        f"Demo API:     http://{lb_ip or 'N/A'}/api/v1/demo\n"
        f"Health:       http://{lb_ip or 'N/A'}/api/v1/health\n"
        f"Ops bastion:  {ops_ip}\n"
        f"\n"
        f"SSH to ops (-A forwards your key so ops can jump to the private tiers):\n"
        f"  {ssh_add}\n"
        f"  {ssh_ops}\n"
        f"\n"
        f"From ops, the private tiers are pre-exported as $BACKEND / $FRONTEND / $DATABASES:\n"
        f"  ssh opc@$BACKEND      # Spring Boot\n"
        f"  ssh opc@$FRONTEND     # nginx + Angular\n"
        f"  ssh opc@$DATABASES        # podman host (Oracle / Postgres / Mongo containers)",
        title="Deployment",
    ))

    console.print(
        "\nCloud-init runs Ansible on each instance automatically.\n"
        "Follow ops progress:  [cyan]sudo tail -f /var/log/cloud-init-output.log[/cyan]\n"
        "Bootstrap succeeded when [cyan]/var/lib/aidatagateway/bootstrap.ok[/cyan] exists.\n"
    )


@cli.command()
@click.option("--yes", is_flag=True, help="Skip the confirmation prompt")
def reset(yes):
    """Re-run database provisioning (Liquibase on all four engines) from ops."""
    console.print("[bold]Reset database provisioning[/bold]\n")

    if not _print_tools(required=("terraform", "ssh"), need_oci_sdk=False):
        sys.exit(1)

    ops_ip = _tf_output("ops_public_ip")
    if not ops_ip:
        console.print("[red]Error:[/red] Could not read ops_public_ip from terraform output.")
        console.print("Run [bold]./manage.py provision[/bold] first.")
        sys.exit(1)

    ssh_key = env_get("SSH_PRIVATE_KEY_PATH", "")
    console.print(
        "This re-runs the ops playbook, which re-applies Liquibase against ADB, "
        "Oracle Free, and Postgres and re-runs the Mongo init script.\n"
        "Every changeset is guarded (CREATE OR REPLACE / DROP-if-exists), so "
        "re-applying is a no-op where nothing changed.\n"
    )
    if not (yes or click.confirm(f"Re-run database provisioning on ops ({ops_ip})?", default=True)):
        console.print("[yellow]Reset cancelled.[/yellow]")
        return

    remote = (
        'sudo ansible-playbook -i /home/opc/ops.ini '
        '--extra-vars "@/home/opc/ansible_params.json" '
        '/home/opc/ansible_ops/server.yaml'
    )
    ssh_cmd = ["ssh"]
    if ssh_key:
        ssh_cmd += ["-i", ssh_key]
    ssh_cmd += [f"opc@{ops_ip}", remote]

    if subprocess.run(ssh_cmd).returncode != 0:
        console.print("\n[red]Reset failed.[/red] Inspect the run on ops:")
        console.print("  [cyan]tail -n 200 /home/opc/ansible-playbook.log[/cyan]")
        sys.exit(1)

    console.print("\n[green]Database provisioning re-applied.[/green]")


@cli.command()
@click.option("--yes", is_flag=True, help="Skip confirmation prompts")
@click.option("--local-only", is_flag=True, help="Skip terraform destroy; only remove local artifacts")
@click.option("--purge", is_flag=True, help="Also delete .env and terraform.tfvars (next deploy needs a full setup)")
def clean(yes, local_only, purge):
    """Destroy cloud resources and remove local artifacts.

    Keeps .env and terraform.tfvars by default, so `provision` can rebuild the
    same deployment without re-answering `setup`. Use --purge to drop those too.
    """
    console.print("[bold]Clean up[/bold]\n")

    if local_only:
        console.print("[dim]--local-only: leaving cloud resources alone.[/dim]")
    elif (TF_DIR / "terraform.tfstate").exists():
        console.print("[yellow]Terraform state found — cloud resources may exist.[/yellow]")
        if yes or click.confirm("Run `terraform destroy`?", default=True):
            if not shutil.which("terraform"):
                console.print("[red]terraform not found on PATH.[/red]")
                sys.exit(1)
            if _tf("destroy", "-auto-approve").returncode != 0:
                console.print("[red]terraform destroy failed.[/red]")
                if not (yes or click.confirm("Continue with local cleanup anyway?", default=False)):
                    return
            else:
                console.print("[green]Cloud resources destroyed.[/green]")
    else:
        console.print("[dim]No Terraform state — skipping cloud cleanup.[/dim]")

    scope = "build output, Terraform state" + (", .env and tfvars" if purge else "")
    if not (yes or click.confirm(f"Delete local artifacts ({scope})?", default=True)):
        console.print("[yellow]Local cleanup cancelled.[/yellow]")
        return

    files = [
        TF_DIR / "terraform.tfstate",
        TF_DIR / "terraform.tfstate.backup",
        TF_DIR / "tfplan",
    ]
    dirs = [
        TF_DIR / "generated",
        TF_DIR / ".terraform",
        BACKEND_DIR / "build",
        BACKEND_DIR / ".gradle",
        FRONTEND_DIST,
        FRONTEND_DIR / ".angular",
        FRONTEND_DIR / "node_modules",
    ]
    if purge:
        files += [ENV_FILE, TF_DIR / "terraform.tfvars"]

    deleted = []
    for f in files:
        if f.exists():
            f.unlink()
            deleted.append(str(f.relative_to(PROJECT_ROOT)))
    for d in dirs:
        if d.exists():
            shutil.rmtree(d)
            deleted.append(str(d.relative_to(PROJECT_ROOT)))

    if deleted:
        console.print("[green]Deleted:[/green]")
        for item in deleted:
            console.print(f"  {item}")
    else:
        console.print("Nothing to clean.")

    if purge:
        console.print("\nNext deploy starts from [bold]./manage.py setup[/bold].")
    else:
        console.print(
            "\n[dim].env and terraform.tfvars kept — rebuild with "
            "[/dim][bold]./manage.py build && ./manage.py provision[/bold]"
        )


if __name__ == "__main__":
    try:
        cli()
    except KeyboardInterrupt:
        console.print("\n[yellow]Interrupted.[/yellow]")
        sys.exit(130)
