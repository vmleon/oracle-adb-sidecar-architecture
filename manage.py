#!/usr/bin/env python3
"""Autonomous AI Database 26ai attached alongside three production databases as an AI Data Gateway."""
import configparser
import json
import os
import re
import secrets
import shutil
import string
import subprocess
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import typer
from dotenv import load_dotenv, set_key
from InquirerPy import inquirer
from rich.console import Console

console = Console()
app = typer.Typer(no_args_is_help=True, add_completion=False, help="AI Data Gateway PoC.")

PROJECT_ROOT = Path(__file__).parent
ENV_FILE = PROJECT_ROOT / ".env"
TF_DIR = PROJECT_ROOT / "deploy" / "terraform"
TFVARS_TPL = TF_DIR / "terraform.tfvars.tpl"
TFVARS_FILE = TF_DIR / "terraform.tfvars"
BACKEND_DIR = PROJECT_ROOT / "src" / "backend"
FRONTEND_DIR = PROJECT_ROOT / "src" / "frontend"
BACKEND_JAR = BACKEND_DIR / "build" / "libs" / "backend-1.0.0.jar"
FRONTEND_DIST = FRONTEND_DIR / "dist" / "frontend" / "browser"
OCI_CONFIG = Path.home() / ".oci" / "config"
SSH_KEYS = [Path.home() / ".ssh" / "id_ed25519.pub", Path.home() / ".ssh" / "id_rsa.pub"]

# What the tier bootstrap leaves on the ops box, and what `deploy` re-runs.
OPS_PLAYBOOK = "/home/opc/artifact/server.yaml"
OPS_INVENTORY = "/home/opc/ops.ini"
OPS_PARAMS = "/home/opc/ansible_params.json"
BOOTSTRAP_OK = "/var/lib/aidatagateway/bootstrap.ok"
BOOTSTRAP_LOG = "/var/log/aidatagateway-bootstrap.log"
# The backend's readiness snapshot: one state per database, read by `status`.
READY_PATH = "/api/v1/ready"
READY_TIMEOUT = 10

# Seconds an `oci` discovery call may take before it counts as a failure.
OCI_TIMEOUT = 60
# The probe gets its own, much shorter deadline: a region that does not host a
# service can hang instead of refusing, and one unresponsive region must not
# stall the whole sweep.
OCI_PROBE_TIMEOUT = 10
# Wide enough to probe every subscribed region in one pass.
OCI_PROBE_WORKERS = 16
# Proves a region hosts OCI Generative AI, which the Select AI profiles and the
# agent team call. Being subscribed to a region says nothing about that.
REGION_PROBE = ["generative-ai", "model-collection", "list-models"]
# Deploying straight into the tenancy root is legitimate, so it is offered as a
# compartment in its own right rather than only as the ancestor of the others.
TENANCY_ROOT = "(tenancy root)"

# Tool -> the command that installs it. `setup` refuses to run without these.
PREREQS = {
    "terraform": "brew install terraform",
    "oci": "brew install oci-cli",
    "ssh": "ships with macOS",
    "java": "brew install --cask temurin@25",
    "node": "brew install node",
    "npm": "brew install node",
}
# Command, display name, minimum major version.
VERSION_FLOORS = [
    (["terraform", "version"], "terraform", 1),
    (["java", "--version"], "java", 25),
    (["node", "--version"], "node", 24),
    (["npm", "--version"], "npm", 11),
]

# One secret per principal, all generated into .env by `setup`.
PASSWORD_KEYS = ["ADB_ADMIN_PASSWORD", "ORACLE_DB_PASSWORD", "POSTGRES_DB_PASSWORD", "MONGO_DB_PASSWORD"]
CONFIG_KEYS = (
    "PROJECT_NAME",
    "OCI_PROFILE",
    "TENANCY_OCID",
    "COMPARTMENT_OCID",
    "REGION",
    # The workload region and the home region are different values with
    # different uses: everything the PoC creates lives in REGION, but IAM
    # identity resources exist only in HOME_REGION, so both are stored.
    "HOME_REGION",
    # OCI Generative AI runs in a subset of regions; the gateway's Select AI
    # profiles point at one of them, which need not be the workload region.
    "GENAI_REGION",
    "GENAI_COMPARTMENT_OCID",
    "SSH_PUBLIC_KEY",
    "SSH_INGRESS_CIDR",
    "CREATE_IDENTITY_RESOURCES",
    *PASSWORD_KEYS,
)
DEFAULT_PROJECT_NAME = "aidatagateway"

SPECIALS = "#_-"
_SECRET_FLAGS = {"-password", "--password", "-pwd"}


def _generate_password(length: int = 20) -> str:
    """Letter first, at least two digits and two specials — safe unquoted in Oracle."""
    rnd = secrets.SystemRandom()
    pw = [rnd.choice(string.ascii_letters)]
    pw += [rnd.choice(string.digits) for _ in range(2)]
    pw += [rnd.choice(SPECIALS) for _ in range(2)]
    pool = string.ascii_letters + string.digits + SPECIALS
    pw += [rnd.choice(pool) for _ in range(length - len(pw))]
    tail = pw[1:]
    rnd.shuffle(tail)
    pw[1:] = tail
    return "".join(pw)


def _run(cmd, **kw) -> subprocess.CompletedProcess:
    """Run a command, echoing it with passwords after a secret flag (or in a KEY=value secret) redacted."""
    def _redact(i, a):
        s = str(a)
        if i > 0 and any(str(cmd[i - 1]).endswith(f) for f in _SECRET_FLAGS):
            return "***"
        if any(s.startswith(f"{f}=") for f in _SECRET_FLAGS):
            return f"{s.split('=', 1)[0]}=***"
        return s

    safe = [_redact(i, a) for i, a in enumerate(cmd)]
    console.print(f"[dim]$ {' '.join(safe)}[/dim]")
    return subprocess.run(cmd, **kw)


def _sh(cmd, **kw) -> subprocess.CompletedProcess:
    """Run a command and exit with its code when it fails."""
    try:
        return _run(cmd, check=True, **kw)
    except subprocess.CalledProcessError as e:
        console.print(f"[red]{cmd[0]} exited {e.returncode}.[/red]")
        raise typer.Exit(e.returncode)


def load_config() -> dict:
    if not ENV_FILE.exists():
        console.print("[red].env not found. Run `./manage.py setup` first.[/red]")
        raise typer.Exit(1)
    load_dotenv(ENV_FILE)
    return {k: os.getenv(k) for k in CONFIG_KEYS}


def render_template(text: str, mapping: dict) -> str:
    """Substitute ${VAR} placeholders (stdlib string.Template, no Jinja)."""
    return string.Template(text).substitute(mapping)


def _parse_major(text: str) -> int | None:
    """First integer on the first line of a `--version` output, or None.

    Version banners differ wildly (`openjdk 21.0.2`, `v22.11.0`,
    `Terraform v1.9.5`), but every one of them leads with its major number.
    """
    line = text.strip().splitlines()[0] if text.strip() else ""
    m = re.search(r"(\d+)", line)
    return int(m.group(1)) if m else None


def _check_version(cmd: list[str], name: str, min_major: int) -> bool:
    """True when `cmd` reports a major version at or above `min_major`.

    Returns False rather than raising when the binary is absent, so it
    composes with `_check_prereqs` instead of duplicating its check.
    """
    if not shutil.which(cmd[0]):
        console.print(f"  [red]✗[/red] {name} not found — need {min_major}+")
        return False
    r = subprocess.run(cmd, capture_output=True, text=True)
    major = _parse_major(r.stdout or r.stderr)
    if major is None or major < min_major:
        console.print(f"  [red]✗[/red] {name} {major or 'unknown'} — need {min_major}+")
        return False
    console.print(f"  [green]✓[/green] {name} {major}")
    return True


def _check_prereqs(prereqs: dict[str, str]) -> None:
    """Exit listing an install hint for every missing tool.

    A hint per tool is the difference between "sqlcl missing" and knowing
    which command installs it, which is the whole cost of a first run.
    """
    missing = [(t, h) for t, h in prereqs.items() if not shutil.which(t)]
    for tool in prereqs:
        if tool not in dict(missing):
            console.print(f"  [green]✓[/green] {tool}")
    for tool, hint in missing:
        console.print(f"  [red]✗[/red] {tool} — install with: {hint}")
    if missing:
        raise typer.Exit(1)


def _check_versions(floors=VERSION_FLOORS) -> None:
    """Exit when any tool is older than its floor; every floor is reported first."""
    results = [_check_version(cmd, name, floor) for cmd, name, floor in floors]
    if not all(results):
        raise typer.Exit(1)


def _oci_profiles(path: Path = OCI_CONFIG) -> list[str]:
    """Profile names from the OCI CLI config, DEFAULT first. Empty when absent."""
    if not Path(path).exists():
        return []
    cfg = configparser.ConfigParser()
    cfg.read(path)
    return (["DEFAULT"] if cfg.defaults() else []) + cfg.sections()


def _oci_run(args: list[str], profile: str, region: str | None = None,
             timeout: int = OCI_TIMEOUT) -> subprocess.CompletedProcess | None:
    """Run the `oci` CLI, or None when it times out or the binary is absent.

    Discovery is read-only and runs many regions wide at once, so it queries
    directly rather than echoing every call through `_run`.
    """
    cmd = ["oci", *args, "--profile", profile, "--output", "json"]
    if region:
        cmd += ["--region", region]
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except (subprocess.TimeoutExpired, FileNotFoundError):
        return None


def _oci_json(args: list[str], profile: str, region: str | None = None,
              timeout: int = OCI_TIMEOUT):
    """Parsed `data` from an `oci` call, or None on any failure."""
    r = _oci_run(args, profile, region, timeout)
    if r is None or r.returncode != 0:
        return None
    try:
        return json.loads(r.stdout)["data"]
    except (json.JSONDecodeError, KeyError):
        return None  # a `list` that matches nothing prints nothing at all


def _oci_tenancy(profile: str, path: Path = OCI_CONFIG) -> str | None:
    """The tenancy OCID for a profile, straight out of the OCI CLI config."""
    if not Path(path).exists():
        return None
    cfg = configparser.ConfigParser()
    cfg.read(path)
    if profile == "DEFAULT":
        return cfg.defaults().get("tenancy")
    return cfg[profile].get("tenancy") if cfg.has_section(profile) else None


def _oci_regions(profile: str) -> tuple[list[str], str | None]:
    """Subscribed region names and the home region name, from one subscription list."""
    data = _oci_json(["iam", "region-subscription", "list"], profile) or []
    subscribed = sorted(d["region-name"] for d in data)
    # The home region is one entry of that same list, and identity resources
    # exist nowhere else — a dynamic group or policy applied against the
    # workload region lands in the wrong region and says so only obliquely.
    home = next((d["region-name"] for d in data if d.get("is-home-region")), None)
    return subscribed, home


def _regions_with_service(regions: list[str], probe: list[str], profile: str) -> list[str]:
    """The subset of `regions` where the probed service answers."""
    def answers(region: str) -> bool:
        r = _oci_run(probe, profile, region, OCI_PROBE_TIMEOUT)
        # A timeout counts as "not available" exactly like a refusal: a region
        # without the service can hold the connection open instead of saying no.
        return r is not None and r.returncode == 0

    with ThreadPoolExecutor(max_workers=OCI_PROBE_WORKERS) as pool:
        reachable = list(pool.map(answers, regions))
    return [r for r, ok in zip(regions, reachable) if ok]


def _compartment_choices(compartments: list[dict], tenancy_ocid: str) -> dict[str, str]:
    """`Parent/Child` display path -> OCID for every ACTIVE compartment, root first."""
    # Deleted and deleting compartments come back in the same list and are
    # pure noise in a picker.
    active = [c for c in compartments if c.get("lifecycle-state") == "ACTIVE"]
    by_id = {c["id"]: c for c in active}

    def path(c: dict) -> str:
        # Leaf names repeat across branches, so a bare name cannot tell the
        # operator which compartment they just picked.
        parts = [c["name"]]
        seen = {c["id"]}
        parent = c.get("compartment-id")
        # Stop on the tenancy, on a parent the list never returned, and on a
        # parent already walked — any of the three otherwise loops forever.
        while parent and parent != tenancy_ocid and parent in by_id and parent not in seen:
            seen.add(parent)
            parts.append(by_id[parent]["name"])
            parent = by_id[parent].get("compartment-id")
        return "/".join(reversed(parts))

    paths = sorted((path(c), c["id"]) for c in active)
    return {TENANCY_ROOT: tenancy_ocid, **dict(paths)}


def _fuzzy(message: str, choices: dict[str, str], current: str | None = None) -> str:
    """Fuzzy-filtered pick over a display-name -> value mapping, returning the value."""
    # On a fuzzy prompt `default` is the initial *search text*, so typing would
    # append to it instead of starting a new search. The current value is moved
    # to the top instead, where it opens highlighted: Enter keeps it, and the
    # first keystroke searches from scratch.
    names = sorted(choices, key=lambda name: choices[name] != current)
    picked = inquirer.fuzzy(message=message, choices=names, max_height="70%").execute()
    return choices[picked]


def _ssh_public_key() -> str:
    """The first public key on disk, or empty when the operator has none yet."""
    return next((k.read_text().strip() for k in SSH_KEYS if k.exists()), "")


def _tf_args(*args: str) -> list[str]:
    """Terraform invoked against deploy/terraform, never by cd-ing into it."""
    return ["terraform", f"-chdir={TF_DIR}", *args]


def _tf(*args: str, **kw) -> subprocess.CompletedProcess:
    return _sh(_tf_args(*args), **kw)


def _tf_outputs() -> dict:
    r = subprocess.run(_tf_args("output", "-json"), capture_output=True, text=True)
    outputs = json.loads(r.stdout) if r.returncode == 0 and r.stdout.strip() else {}
    if not outputs:
        console.print("[red]No Terraform outputs. Run `./manage.py provision` first.[/red]")
        raise typer.Exit(1)
    return {k: v["value"] for k, v in outputs.items()}


def _render_tfvars(mapping: dict) -> str:
    return render_template(TFVARS_TPL.read_text(), mapping)


def _ops_ssh(ops_ip: str, remote: str) -> subprocess.CompletedProcess:
    return _sh(["ssh", f"opc@{ops_ip}", remote])


@app.callback()
def main():
    """AI Data Gateway PoC CLI."""


@app.command()
def setup(yes: bool = typer.Option(False, "--yes", help="Skip the interactive prompts.")):
    """Discover profile, regions and compartments, generate .env, render terraform.tfvars."""
    console.print("[bold]Prerequisites[/bold]")
    _check_prereqs(PREREQS)
    _check_versions()

    load_dotenv(ENV_FILE)
    prior = {k: os.getenv(k) for k in CONFIG_KEYS}

    profiles = _oci_profiles()
    if not profiles:
        console.print(f"[red]No profiles in {OCI_CONFIG}. Run `oci setup config` first.[/red]")
        raise typer.Exit(1)
    profile = prior["OCI_PROFILE"] or profiles[0]
    if not yes:
        profile = _fuzzy("OCI profile", {p: p for p in profiles}, profile)

    tenancy = _oci_tenancy(profile)
    if not tenancy:
        console.print(f"[red]Profile {profile} has no tenancy OCID in {OCI_CONFIG}.[/red]")
        raise typer.Exit(1)

    with console.status("Listing region subscriptions…"):
        subscribed, home_region = _oci_regions(profile)
    if not subscribed or not home_region:
        console.print(f"[red]Could not list region subscriptions for profile {profile}.[/red]")
        raise typer.Exit(1)

    with console.status(f"Probing {len(subscribed)} regions for {REGION_PROBE[0]}…"):
        genai_regions = _regions_with_service(subscribed, [*REGION_PROBE, "--compartment-id", tenancy], profile)
    if not genai_regions:
        console.print(f"[yellow]{REGION_PROBE[0]} answered in no subscribed region — offering all.[/yellow]")
        genai_regions = subscribed
    console.print(f"  home region [cyan]{home_region}[/cyan], "
                  f"{len(genai_regions)} of {len(subscribed)} regions host {REGION_PROBE[0]}")

    region = prior["REGION"] or home_region
    if not yes:
        region = _fuzzy("Region", {r: r for r in subscribed}, region)
    genai_region = prior["GENAI_REGION"] or (region if region in genai_regions else genai_regions[0])
    if not yes:
        genai_region = _fuzzy("GenAI region", {r: r for r in genai_regions}, genai_region)

    with console.status("Listing compartments…"):
        compartments = _oci_json(
            ["iam", "compartment", "list", "--compartment-id", tenancy,
             "--compartment-id-in-subtree", "true", "--all"], profile) or []
    choices = _compartment_choices(compartments, tenancy)
    console.print(f"  {len(choices) - 1} active compartments of {len(compartments)} returned")
    compartment = prior["COMPARTMENT_OCID"] or tenancy
    if not yes:
        compartment = _fuzzy("Compartment", choices, compartment)
    genai_compartment = prior["GENAI_COMPARTMENT_OCID"] or compartment
    if not yes:
        genai_compartment = _fuzzy("GenAI compartment", choices, genai_compartment)

    # The gateway authenticates to GenAI and Object Storage as itself, which
    # needs a dynamic group and a policy in the tenancy root. Creating them
    # takes tenancy-level IAM rights; without them an administrator applies
    # the statements `provision` prints instead.
    create_identity = prior["CREATE_IDENTITY_RESOURCES"] or "true"
    if not yes:
        create_identity = "true" if typer.confirm(
            "Create the dynamic group and policy (needs tenancy IAM rights)?",
            default=create_identity == "true") else "false"

    values = {
        "PROJECT_NAME": prior["PROJECT_NAME"] or DEFAULT_PROJECT_NAME,
        "OCI_PROFILE": profile,
        "TENANCY_OCID": tenancy,
        "COMPARTMENT_OCID": compartment,
        "REGION": region,
        "HOME_REGION": home_region,
        "GENAI_REGION": genai_region,
        "GENAI_COMPARTMENT_OCID": genai_compartment,
        "SSH_PUBLIC_KEY": prior["SSH_PUBLIC_KEY"] or _ssh_public_key(),
        "SSH_INGRESS_CIDR": prior["SSH_INGRESS_CIDR"] or "0.0.0.0/0",
        "CREATE_IDENTITY_RESOURCES": create_identity,
        # Passwords already in .env are kept: re-running setup against a live
        # deployment must not rotate credentials the running databases hold.
        **{k: prior[k] or _generate_password() for k in PASSWORD_KEYS},
    }
    for key in CONFIG_KEYS:
        set_key(ENV_FILE, key, values[key])
    TFVARS_FILE.write_text(_render_tfvars(values))
    TFVARS_FILE.chmod(0o600)
    console.print(f"[green]✓[/green] wrote {ENV_FILE.name} and {TFVARS_FILE.name}")
    console.print("Next: [bold]./manage.py build[/bold]")


@app.command()
def build():
    """Build the backend jar and the frontend dist."""
    _check_versions([f for f in VERSION_FLOORS if f[1] != "terraform"])
    _sh(["./gradlew", "build"], cwd=BACKEND_DIR)
    _sh(["npm", "install"], cwd=FRONTEND_DIR)
    _sh(["npm", "run", "build"], cwd=FRONTEND_DIR)
    console.print(f"[green]✓[/green] {BACKEND_JAR.relative_to(PROJECT_ROOT)} and "
                  f"{FRONTEND_DIST.relative_to(PROJECT_ROOT)}/")
    console.print("Next: [bold]./manage.py provision[/bold]")


@app.command()
def provision(yes: bool = typer.Option(False, "--yes", help="Auto-approve the apply.")):
    """terraform init and apply."""
    cfg = load_config()
    if not TFVARS_FILE.exists():
        console.print("[red]terraform.tfvars not found. Run `./manage.py setup` first.[/red]")
        raise typer.Exit(1)
    missing = [p for p in (BACKEND_JAR, FRONTEND_DIST) if not p.exists()]
    if missing:
        for p in missing:
            console.print(f"[red]missing {p.relative_to(PROJECT_ROOT)}[/red]")
        console.print("Run `./manage.py build` first.")
        raise typer.Exit(1)

    _tf("init", "-input=false")
    _tf("apply", *(["-auto-approve"] if yes else []))

    if cfg["CREATE_IDENTITY_RESOURCES"] != "true":
        console.print("\n[yellow]Identity objects were not created.[/yellow] Select AI cannot "
                      "authenticate until a tenancy administrator applies:")
        for statement in _tf_outputs()["resource_principal_statements"]:
            console.print(f"  {statement}")

    console.print("\nCloud-init now runs Ansible on each instance and Liquibase from ops; "
                  "this takes several minutes.")
    console.print("Next: [bold]./manage.py status[/bold]")


@app.command()
def deploy():
    """Re-run the ops playbook: Liquibase on all four engines, from the ops box."""
    load_config()
    ops_ip = _tf_outputs()["ops_public_ip"]
    if _run(["ssh", f"opc@{ops_ip}", f"test -f {BOOTSTRAP_OK}"]).returncode != 0:
        console.print(f"[red]ops has not finished its first bootstrap ({BOOTSTRAP_OK} missing).[/red] "
                      f"Follow it with: ssh opc@{ops_ip} sudo tail -f {BOOTSTRAP_LOG}")
        raise typer.Exit(1)
    _ops_ssh(ops_ip, f"sudo ansible-playbook -i {OPS_INVENTORY} -e @{OPS_PARAMS} {OPS_PLAYBOOK}")
    console.print("[green]✓[/green] database provisioning re-applied")


@app.command()
def status():
    """Print endpoints, the readiness of every database, and where to look when a tier is not up."""
    load_config()
    out = _tf_outputs()
    lb, ops = out["lb_public_ip"], out["ops_public_ip"]
    console.print(f"Frontend      http://{lb}")
    console.print(f"Readiness     http://{lb}{READY_PATH}")
    console.print(f"Ops           ssh opc@{ops}")
    for tier in ("backend", "frontend", "databases"):
        console.print(f"{tier:<13} {out[f'{tier}_private_ip']}  (from ops: ssh opc@${tier.upper()})")

    try:
        with urllib.request.urlopen(f"http://{lb}{READY_PATH}", timeout=READY_TIMEOUT) as r:
            ready = json.load(r)
        console.print(f"\nBackend readiness: [bold]{ready['overall']}[/bold]")
        for component, state in ready["components"].items():
            mark = "[green]✓[/green]" if state == "UP" else "[yellow]·[/yellow]"
            console.print(f"  {mark} {component:<10} {state}")
    except (urllib.error.URLError, TimeoutError, OSError, ValueError) as e:
        console.print(f"\n[yellow]Backend not answering yet[/yellow] ({e}).")

    console.print(f"\nA tier is built when {BOOTSTRAP_OK} exists on it; "
                  f"until then follow {BOOTSTRAP_LOG}.")


@app.command()
def reset(yes: bool = typer.Option(False, "--yes", help="Skip the confirmation prompt.")):
    """Re-run deploy against the live infrastructure."""
    if not yes and not typer.confirm("Re-run Liquibase on all four engines from ops?", default=True):
        raise typer.Exit(0)
    deploy()


@app.command()
def teardown(
    yes: bool = typer.Option(False, "--yes", help="Auto-approve the destroy."),
    purge: bool = typer.Option(False, "--purge", help="Also delete .env and terraform.tfvars."),
):
    """terraform destroy and remove local build output. Keeps .env and terraform.tfvars unless --purge."""
    if (TF_DIR / "terraform.tfstate").exists():
        _tf("destroy", *(["-auto-approve"] if yes else []))
    else:
        console.print("[dim]No Terraform state — nothing to destroy.[/dim]")

    files = [TF_DIR / "terraform.tfstate", TF_DIR / "terraform.tfstate.backup", TF_DIR / "tfplan"]
    dirs = [
        TF_DIR / "generated", TF_DIR / ".terraform",
        BACKEND_DIR / "build", BACKEND_DIR / ".gradle",
        FRONTEND_DIR / "dist", FRONTEND_DIR / ".angular", FRONTEND_DIR / "node_modules",
    ]
    if purge:
        files += [ENV_FILE, TFVARS_FILE]
    for f in files:
        if f.exists():
            f.unlink()
            console.print(f"  removed {f.relative_to(PROJECT_ROOT)}")
    for d in dirs:
        if d.exists():
            shutil.rmtree(d)
            console.print(f"  removed {d.relative_to(PROJECT_ROOT)}/")
    console.print("Next: [bold]./manage.py setup[/bold]" if purge
                  else "Next: [bold]./manage.py build && ./manage.py provision[/bold]")


if __name__ == "__main__":
    app()
