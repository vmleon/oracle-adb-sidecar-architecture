# Pending

## bootstrap.tftpl — `ansible_params.json` is appended on cloud-init re-run

`deploy/tf/modules/ops/userdata/bootstrap.tftpl` line 80 uses `cat <<EOT >>`,
so a re-run concatenates a second JSON object onto the file and Ansible
fails to parse it. Change `>>` to `>` so the file is overwritten.

Workaround until then (run on ops):

```bash
python3 -c "import json; raw=open('/home/opc/ansible_params.json').read().lstrip(); obj,_=json.JSONDecoder().raw_decode(raw); open('/home/opc/ansible_params.json','w').write(json.dumps(obj,indent=2))"
```

## Optional — Layer 4 RAG track

Add `BANKING_RAG` profile + `BANKING_POLICY_INDEX` vector index +
`COMPLIANCE_RAG_TOOL`, wired into `COMPLIANCE_OFFICER` alongside the
SQL tool. Only the `005-select-ai-agents.yaml` changeset needs the
additions.
