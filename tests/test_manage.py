import re
from pathlib import Path

import pytest

import manage


def test_generate_password_rules():
    pw = manage._generate_password(20)
    assert len(pw) == 20
    assert pw[0].isalpha()
    assert sum(c.isdigit() for c in pw) >= 2
    assert sum(c in manage.SPECIALS for c in pw) >= 2


def test_generate_password_is_not_constant():
    assert manage._generate_password(20) != manage._generate_password(20)


def test_render_template_substitutes():
    out = manage.render_template("pwd=${DB_PASSWORD}", {"DB_PASSWORD": "x#1_2y"})
    assert out == "pwd=x#1_2y"


def test_render_template_missing_key_raises():
    with pytest.raises(KeyError):
        manage.render_template("${MISSING}", {})


def test_render_tfvars_fills_every_placeholder():
    tokens = re.findall(r"\$\{(\w+)\}", manage.TFVARS_TPL.read_text())
    mapping = {token: f"dummy-{token}" for token in tokens}
    out = manage._render_tfvars(mapping)
    assert "${" not in out


def test_config_keys_cover_every_tfvars_token():
    tokens = set(re.findall(r"\$\{(\w+)\}", manage.TFVARS_TPL.read_text()))
    assert tokens <= set(manage.CONFIG_KEYS)


def test_every_password_key_is_a_config_key():
    assert set(manage.PASSWORD_KEYS) <= set(manage.CONFIG_KEYS)


def test_parse_major_reads_common_version_shapes():
    assert manage._parse_major("openjdk 25.0.4.1 2026-08-18 LTS") == 25
    assert manage._parse_major("v24.21.0") == 24
    assert manage._parse_major("11.19.0") == 11
    assert manage._parse_major("Terraform v1.16.4") == 1


def test_parse_major_uses_only_the_first_line():
    assert manage._parse_major("openjdk 21.0.2\nOpenJDK Runtime 99") == 21


def test_parse_major_returns_none_without_a_number():
    assert manage._parse_major("") is None
    assert manage._parse_major("no digits here") is None


def test_prereqs_maps_every_tool_to_a_nonempty_hint():
    assert manage.PREREQS
    for tool, hint in manage.PREREQS.items():
        assert tool and isinstance(hint, str) and hint.strip()


def test_version_floors_name_tools_that_are_prereqs():
    for cmd, name, floor in manage.VERSION_FLOORS:
        assert cmd[0] in manage.PREREQS
        assert floor >= 1


def test_tf_args_targets_the_terraform_directory():
    args = manage._tf_args("apply", "-auto-approve")
    assert args[0] == "terraform"
    assert args[1] == f"-chdir={manage.TF_DIR}"
    assert args[2:] == ["apply", "-auto-approve"]


def test_terraform_dir_sits_under_deploy():
    assert manage.TF_DIR.parts[-2:] == ("deploy", "terraform")


def test_oci_profiles_lists_default_and_sections(tmp_path: Path):
    cfg = tmp_path / "config"
    cfg.write_text("[DEFAULT]\nregion=eu-frankfurt-1\n\n[SANDBOX]\nregion=uk-london-1\n")
    assert manage._oci_profiles(cfg) == ["DEFAULT", "SANDBOX"]


def test_oci_profiles_is_empty_when_the_file_is_missing(tmp_path: Path):
    assert manage._oci_profiles(tmp_path / "absent") == []


def test_oci_tenancy_reads_the_profile_section(tmp_path: Path):
    cfg = tmp_path / "config"
    cfg.write_text("[DEFAULT]\ntenancy=ocid1.tenancy..d\n\n[SANDBOX]\ntenancy=ocid1.tenancy..s\n")
    assert manage._oci_tenancy("DEFAULT", cfg) == "ocid1.tenancy..d"
    assert manage._oci_tenancy("SANDBOX", cfg) == "ocid1.tenancy..s"
    assert manage._oci_tenancy("ABSENT", cfg) is None


def _compartment(id_, name, parent, state="ACTIVE"):
    return {"id": id_, "name": name, "compartment-id": parent, "lifecycle-state": state}


def test_compartment_choices_offers_the_tenancy_root_first():
    choices = manage._compartment_choices([], "ocid1.tenancy..t")
    assert list(choices) == [manage.TENANCY_ROOT]
    assert choices[manage.TENANCY_ROOT] == "ocid1.tenancy..t"


def test_compartment_choices_drops_everything_not_active():
    data = [
        _compartment("a", "live", "t"),
        _compartment("b", "gone", "t", state="DELETED"),
        _compartment("c", "going", "t", state="DELETING"),
    ]
    assert set(manage._compartment_choices(data, "t")) == {manage.TENANCY_ROOT, "live"}


def test_compartment_choices_builds_parent_child_paths():
    data = [_compartment("a", "top", "t"), _compartment("b", "mid", "a"), _compartment("c", "leaf", "b")]
    assert set(manage._compartment_choices(data, "t")) == {
        manage.TENANCY_ROOT, "top", "top/mid", "top/mid/leaf"
    }


def test_compartment_choices_disambiguates_duplicate_leaf_names():
    data = [
        _compartment("a", "prod", "t"),
        _compartment("b", "dev", "t"),
        _compartment("c", "testlz-appdev-cmp", "a"),
        _compartment("d", "testlz-appdev-cmp", "b"),
    ]
    choices = manage._compartment_choices(data, "t")
    assert choices["prod/testlz-appdev-cmp"] == "c"
    assert choices["dev/testlz-appdev-cmp"] == "d"


def test_compartment_choices_survives_a_parent_cycle():
    data = [_compartment("a", "one", "b"), _compartment("b", "two", "a")]
    assert set(manage._compartment_choices(data, "t")) == {manage.TENANCY_ROOT, "two/one", "one/two"}


def test_compartment_choices_survives_a_parent_outside_the_list():
    data = [_compartment("a", "orphan", "ocid1.compartment..missing")]
    assert set(manage._compartment_choices(data, "t")) == {manage.TENANCY_ROOT, "orphan"}
