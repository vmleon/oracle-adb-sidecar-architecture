#!/usr/bin/env python3
"""CLI for managing the ADB sidecar architecture deployment."""

import configparser
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
from pathlib import Path

import click
from dotenv import load_dotenv
from InquirerPy import inquirer
from jinja2 import Template
from rich.console import Console
from rich.panel import Panel

console = Console()

PROJECT_ROOT = Path(__file__).parent
ENV_FILE = PROJECT_ROOT / ".env"
TF_DIR = PROJECT_ROOT / "deploy" / "tf" / "app"
ANSIBLE_DIR = PROJECT_ROOT / "deploy" / "ansible"


def _read_oci_config():
    oci_config_path = Path.home() / ".oci" / "config"
    if not oci_config_path.exists():
        console.print(f"[red]Error:[/red] OCI config not found at {oci_config_path}")
        sys.exit(1)

    config = configparser.ConfigParser()
    config.read(oci_config_path)

    profiles = list(config.sections())
    if config.defaults():
        profiles.insert(0, "DEFAULT")

    return profiles, config


def _generate_password(length=20):
    """Oracle-compliant: starts with letter, 2+ specials, 2+ digits."""
    letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    digits = "0123456789"
    specials = "#_-"

    password = [secrets.choice(letters)]
    password.append(secrets.choice(specials))
    password.append(secrets.choice(specials))
    password.append(secrets.choice(digits))
    password.append(secrets.choice(digits))

    alphabet = letters + digits + specials
    for _ in range(length - 5):
        password.append(secrets.choice(alphabet))

    tail = password[1:]
    secrets.SystemRandom().shuffle(tail)
    password[1:] = tail

    return "".join(password)


def _list_regions(oci_config):
    import oci

    try:
        identity_client = oci.identity.IdentityClient(oci_config)
        tenancy_id = oci_config["tenancy"]

        tenancy = identity_client.get_tenancy(tenancy_id).data
        home_region_key = tenancy.home_region_key

        subscriptions = identity_client.list_region_subscriptions(tenancy_id).data
        regions = []
        for sub in subscriptions:
            is_home = sub.region_key == home_region_key
            regions.append({"name": sub.region_name, "is_home": is_home})

        regions.sort(key=lambda x: (not x["is_home"], x["name"]))
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
        for comp in response.data:
            if comp.lifecycle_state == "ACTIVE":
                compartments.append({"name": comp.name, "id": comp.id})

        return compartments
    except Exception as e:
        console.print(f"[yellow]Warning:[/yellow] Could not fetch compartments: {e}")
        return None


@click.group()
def cli():
    """ADB Sidecar Architecture Manager."""


@cli.command()
def setup():
    """Interactive OCI configuration. Stores results in .env."""
    console.print("[bold]ADB Sidecar Architecture — Setup[/bold]\n")

    profiles, oci_config_parser = _read_oci_config()

    profile = inquirer.select(
        message="OCI profile:",
        choices=profiles,
        default=profiles[0] if profiles else None,
    ).execute()

    profile_config = oci_config_parser[profile]
    tenancy_ocid = profile_config.get("tenancy")
    user_ocid = profile_config.get("user")
    fingerprint = profile_config.get("fingerprint")
    key_file = profile_config.get("key_file")
    config_region = profile_config.get("region", "us-phoenix-1")

    sdk_config = {
        "user": user_ocid,
        "key_file": key_file,
        "fingerprint": fingerprint,
        "tenancy": tenancy_ocid,
        "region": config_region,
    }

    console.print("\nFetching subscribed regions...")
    regions = _list_regions(sdk_config)

    if regions:
        choices = [
            f"{r['name']} (home)" if r["is_home"] else r["name"] for r in regions
        ]
        selected = inquirer.select(
            message="Region:",
            choices=choices,
            default=choices[0],
        ).execute()
        region = selected.replace(" (home)", "")
    else:
        region = click.prompt("Region", default=config_region)

    sdk_config["region"] = region

    console.print("\nFetching compartments...")
    compartments = _list_compartments(sdk_config)

    if compartments:
        choices = [c["name"] for c in compartments]
        comp_map = {c["name"]: c["id"] for c in compartments}
        selected = inquirer.fuzzy(
            message="Compartment (type to search):",
            choices=choices,
        ).execute()
        compartment_ocid = comp_map[selected]
    else:
        compartment_ocid = click.prompt("Compartment OCID")

    ssh_dir = Path.home() / ".ssh"
    ssh_keys = (
        sorted(
            f.name
            for f in ssh_dir.iterdir()
            if f.is_file() and not f.suffix and (f.with_suffix(".pub")).exists()
        )
        if ssh_dir.is_dir()
        else []
    )

    if ssh_keys:
        ssh_private_key_path = str(
            ssh_dir
            / inquirer.fuzzy(message="SSH private key:", choices=ssh_keys).execute()
        )
    else:
        ssh_private_key_path = click.prompt("SSH private key path")
    ssh_public_key_path = ssh_private_key_path + ".pub"
    if Path(ssh_public_key_path).exists():
        ssh_public_key = Path(ssh_public_key_path).read_text().strip()
    else:
        ssh_public_key = click.prompt("SSH public key (paste content)")

    project_name = inquirer.text(
        message="Project name (used for OCI resource naming):",
        default="adbsidecar",
    ).execute()

    adb_admin_password = _generate_password()
    oracle_db_password = _generate_password()
    postgres_db_password = _generate_password()
    mongo_db_password = _generate_password()

    console.print("\nOCI GenAI settings (used for Select AI Agents):")
    genai_region = click.prompt("GenAI region", default="us-chicago-1")
    genai_compartment_id = click.prompt(
        "GenAI compartment OCID (leave blank to use the same compartment)",
        default=compartment_ocid,
    )

    console.print(
        Panel(
            f"Profile:      {profile}\n"
            f"Tenancy:      {tenancy_ocid}\n"
            f"Region:       {region}\n"
            f"Compartment:  {compartment_ocid}\n"
            f"SSH key:      {ssh_private_key_path}\n"
            f"Project name: {project_name}\n"
            f"GenAI region: {genai_region}\n"
            f"GenAI compartment: {genai_compartment_id}\n"
            f"DB passwords: (4 generated — adb/oracle/postgres/mongo — stored in .env)",
            title="Configuration Summary",
        )
    )

    if not click.confirm("Save configuration?", default=True):
        console.print("[yellow]Setup cancelled.[/yellow]")
        sys.exit(0)

    env_vars = {
        "OCI_PROFILE": profile,
        "OCI_TENANCY_OCID": tenancy_ocid,
        "OCI_USER_OCID": user_ocid,
        "OCI_FINGERPRINT": fingerprint,
        "OCI_KEY_FILE": key_file,
        "OCI_COMPARTMENT_OCID": compartment_ocid,
        "OCI_REGION": region,
        "OCI_GENAI_REGION": genai_region,
        "OCI_GENAI_COMPARTMENT_ID": genai_compartment_id,
        "PROJECT_NAME": project_name,
        "ADB_ADMIN_PASSWORD": adb_admin_password,
        "ORACLE_DB_PASSWORD": oracle_db_password,
        "POSTGRES_DB_PASSWORD": postgres_db_password,
        "MONGO_DB_PASSWORD": mongo_db_password,
        "SSH_PRIVATE_KEY_PATH": ssh_private_key_path,
        "SSH_PUBLIC_KEY": ssh_public_key,
    }

    with open(ENV_FILE, "w") as f:
        for key, value in env_vars.items():
            f.write(f'{key}="{value}"\n')

    console.print(f"\n[green]Configuration saved to {ENV_FILE}[/green]")
    console.print("\nNext step: [bold]python manage.py build[/bold]")


def _check_version(cmd, args, name, min_major):
    try:
        result = subprocess.run(
            [cmd] + args, capture_output=True, text=True, timeout=15
        )
        output = result.stdout + result.stderr
        match = re.search(r"(\d+)\.\d+", output)
        if not match:
            console.print(
                f"[red]Error:[/red] Could not parse {name} version from: {output.strip()}"
            )
            return False
        major = int(match.group(1))
        if major < min_major:
            console.print(f"[red]Error:[/red] {name} {major} found, need {min_major}+")
            return False
        console.print(f"  {name} {match.group(0)} [green]OK[/green]")
        return True
    except FileNotFoundError:
        console.print(
            f"[red]Error:[/red] {name} not found. Install {name} {min_major}+ and try again."
        )
        return False


def _run_build_step(label, cmd, cwd):
    console.print(f"\n[bold]{label}[/bold]")
    result = subprocess.run(cmd, cwd=cwd, shell=True)
    if result.returncode != 0:
        console.print(f"[red]Error:[/red] {label} failed (exit {result.returncode})")
        return False
    return True


@cli.command()
def build():
    """Build backend JAR and frontend dist."""
    console.print("[bold]Building backend and frontend...[/bold]\n")

    console.print("Checking tools:")
    ok = True
    ok = _check_version("java", ["--version"], "Java", 23) and ok
    ok = _check_version("node", ["--version"], "Node", 22) and ok
    ok = _check_version("npm", ["--version"], "npm", 10) and ok
    if not ok:
        sys.exit(1)

    backend_dir = PROJECT_ROOT / "src" / "backend"
    frontend_dir = PROJECT_ROOT / "src" / "frontend"

    if not _run_build_step("Backend (Gradle)", "./gradlew build -x test", backend_dir):
        sys.exit(1)

    if not _run_build_step(
        "Frontend (Angular)", "npm install && npm run build", frontend_dir
    ):
        sys.exit(1)

    console.print("\n[green]Build complete.[/green]")
    console.print("\nNext step: [bold]python manage.py tf[/bold]")


@cli.command()
def tf():
    """Generate terraform.tfvars from Jinja2 template and .env values."""
    if not ENV_FILE.exists():
        console.print(
            "[red]Error:[/red] .env not found. Run 'python manage.py setup' first."
        )
        sys.exit(1)

    load_dotenv(ENV_FILE, override=True)

    required_vars = [
        "OCI_PROFILE",
        "OCI_TENANCY_OCID",
        "OCI_USER_OCID",
        "OCI_FINGERPRINT",
        "OCI_KEY_FILE",
        "OCI_COMPARTMENT_OCID",
        "OCI_REGION",
        "OCI_GENAI_REGION",
        "OCI_GENAI_COMPARTMENT_ID",
        "PROJECT_NAME",
        "ADB_ADMIN_PASSWORD",
        "ORACLE_DB_PASSWORD",
        "POSTGRES_DB_PASSWORD",
        "MONGO_DB_PASSWORD",
        "SSH_PUBLIC_KEY",
        "SSH_PRIVATE_KEY_PATH",
    ]
    missing = [v for v in required_vars if not os.getenv(v)]
    if missing:
        console.print(
            f"[red]Error:[/red] Missing variables in .env: {', '.join(missing)}"
        )
        sys.exit(1)

    oci_key_file = Path(os.getenv("OCI_KEY_FILE").replace("~", str(Path.home())))
    if not oci_key_file.exists():
        console.print(f"[red]Error:[/red] OCI key file not found: {oci_key_file}")
        sys.exit(1)
    oci_private_api_key = oci_key_file.read_text().strip()

    jar_path = (
        PROJECT_ROOT / "src" / "backend" / "build" / "libs" / "backend-1.0.0.jar"
    )
    dist_path = PROJECT_ROOT / "src" / "frontend" / "dist"
    missing = []
    if not jar_path.exists():
        missing.append("Backend JAR (src/backend/build/libs/backend-1.0.0.jar)")
    if not dist_path.exists() or not any(dist_path.iterdir()):
        missing.append("Frontend dist (src/frontend/dist/)")
    if missing:
        console.print("[yellow]Warning:[/yellow] Build artifacts missing:")
        for m in missing:
            console.print(f"  - {m}")
        console.print("Run [bold]python manage.py build[/bold] first.\n")

    console.print("[bold]Generating terraform.tfvars...[/bold]\n")

    template_file = TF_DIR / "terraform.tfvars.j2"
    if not template_file.exists():
        console.print(f"[red]Error:[/red] Template not found: {template_file}")
        sys.exit(1)

    template = Template(template_file.read_text())
    tfvars_content = template.render(
        profile=os.getenv("OCI_PROFILE"),
        tenancy_ocid=os.getenv("OCI_TENANCY_OCID"),
        user_ocid=os.getenv("OCI_USER_OCID"),
        fingerprint=os.getenv("OCI_FINGERPRINT"),
        oci_private_api_key=oci_private_api_key,
        compartment_ocid=os.getenv("OCI_COMPARTMENT_OCID"),
        region=os.getenv("OCI_REGION"),
        genai_region=os.getenv("OCI_GENAI_REGION"),
        genai_compartment_id=os.getenv("OCI_GENAI_COMPARTMENT_ID"),
        project_name=os.getenv("PROJECT_NAME"),
        adb_admin_password=os.getenv("ADB_ADMIN_PASSWORD"),
        oracle_db_password=os.getenv("ORACLE_DB_PASSWORD"),
        postgres_db_password=os.getenv("POSTGRES_DB_PASSWORD"),
        mongo_db_password=os.getenv("MONGO_DB_PASSWORD"),
        ssh_public_key=os.getenv("SSH_PUBLIC_KEY"),
        ssh_private_key_path=os.getenv("SSH_PRIVATE_KEY_PATH"),
    )

    tfvars_file = TF_DIR / "terraform.tfvars"
    tfvars_file.write_text(tfvars_content)

    console.print(f"[green]Generated:[/green] {tfvars_file}\n")

    console.print("[bold]Next steps:[/bold]")
    console.print("  cd deploy/tf/app")
    console.print("  terraform init")
    console.print("  terraform plan -out=tfplan")
    console.print("  terraform apply tfplan\n")
    console.print("After Terraform completes: [bold]python manage.py info[/bold]")


@cli.command()
def info():
    """Show endpoints and SSH commands after Terraform apply."""
    if not ENV_FILE.exists():
        console.print(
            "[red]Error:[/red] .env not found. Run 'python manage.py setup' first."
        )
        sys.exit(1)

    load_dotenv(ENV_FILE, override=True)

    ops_ip = lb_ip = None
    try:
        result = subprocess.run(
            ["terraform", "output", "-raw", "ops_public_ip"],
            cwd=TF_DIR,
            capture_output=True,
            text=True,
        )
        if result.returncode == 0 and result.stdout.strip():
            ops_ip = result.stdout.strip()

        result = subprocess.run(
            ["terraform", "output", "-raw", "lb_public_ip"],
            cwd=TF_DIR,
            capture_output=True,
            text=True,
        )
        if result.returncode == 0 and result.stdout.strip():
            lb_ip = result.stdout.strip()
    except FileNotFoundError:
        console.print("[red]Error:[/red] terraform CLI not found.")
        sys.exit(1)

    if not ops_ip:
        console.print(
            "[red]Error:[/red] Could not read ops_public_ip from terraform output."
        )
        console.print(
            "Run terraform apply first: cd deploy/tf/app && terraform apply"
        )
        sys.exit(1)

    ssh_private_key = os.getenv("SSH_PRIVATE_KEY_PATH", "")
    ssh_ops_cmd = (
        f"ssh -A -i {ssh_private_key} opc@{ops_ip}"
        if ssh_private_key
        else f"ssh -A opc@{ops_ip}"
    )
    ssh_add_cmd = f"ssh-add {ssh_private_key}" if ssh_private_key else "ssh-add"

    console.print(
        Panel(
            f"Frontend:     http://{lb_ip or 'N/A'}\n"
            f"Demo API:     http://{lb_ip or 'N/A'}/api/v1/demo\n"
            f"Ops bastion:  {ops_ip}\n"
            f"\n"
            f"SSH to ops (-A forwards your key so ops can jump to private tiers):\n"
            f"  {ssh_add_cmd}\n"
            f"  {ssh_ops_cmd}\n"
            f"\n"
            f"From ops, the private tiers are pre-exported as $BACK / $FRONT / $DB:\n"
            f"  ssh opc@$BACK      # Spring Boot\n"
            f"  ssh opc@$FRONT     # nginx + Angular\n"
            f"  ssh opc@$DB        # podman host (Oracle / Postgres / Mongo containers)",
            title="Deployment",
        )
    )

    console.print(
        "\nCloud-init runs Ansible on each instance automatically.\n"
        "Tail progress: ssh to ops then `sudo tail -f /var/log/cloud-init-output.log`\n"
    )


@cli.command()
def clean():
    """Clean up generated and build files. Refuses if Terraform state has resources."""
    console.print("[bold]Clean Up[/bold]\n")

    has_resources = False
    tf_state = TF_DIR / "terraform.tfstate"
    if tf_state.exists():
        try:
            state = json.loads(tf_state.read_text())
            has_resources = len(state.get("resources", [])) > 0
        except (json.JSONDecodeError, KeyError):
            has_resources = True

    if has_resources:
        console.print("[yellow]Terraform state has active resources.[/yellow]")
        console.print("Destroy infrastructure first:\n")
        console.print("  cd deploy/tf/app")
        console.print("  terraform destroy\n")
        console.print("Then re-run: [bold]python manage.py clean[/bold]")
        return

    generated = [
        ENV_FILE,
        TF_DIR / "terraform.tfvars",
        TF_DIR / "terraform.tfstate",
        TF_DIR / "terraform.tfstate.backup",
        TF_DIR / "tfplan",
        TF_DIR / ".terraform.lock.hcl",
    ]
    generated_dirs = [
        TF_DIR / "generated",
        TF_DIR / ".terraform",
        PROJECT_ROOT / "src" / "backend" / "build",
        PROJECT_ROOT / "src" / "backend" / ".gradle",
        PROJECT_ROOT / "src" / "frontend" / "dist",
        PROJECT_ROOT / "src" / "frontend" / ".angular",
        PROJECT_ROOT / "src" / "frontend" / "node_modules",
    ]

    deleted = []
    for f in generated:
        if f.exists():
            f.unlink()
            deleted.append(str(f.relative_to(PROJECT_ROOT)))
    for d in generated_dirs:
        if d.exists():
            shutil.rmtree(d)
            deleted.append(str(d.relative_to(PROJECT_ROOT)))

    if deleted:
        console.print("[green]Deleted:[/green]")
        for item in deleted:
            console.print(f"  {item}")
    else:
        console.print("Nothing to clean.")


if __name__ == "__main__":
    cli()
