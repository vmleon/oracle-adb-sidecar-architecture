# Backlog

Unbuilt slices, each described as a self-contained addition. Ordered
low-hanging-fruit → most complex; the sequence reflects effort and blast radius,
not dependency.

---

## 1. Dashboard filter controls

The Fraud Dashboard date-range filter and "loaded at" stamp are in place. What
remains:

- Per-pattern threshold knobs in the UI, defaulted to the current SQL constants
  (fan-out ≥5, structuring ≥3 / ≥$25K, cycle min-amount $1000). Promote those
  constants to controller request params.
- Seed-data expansion so the filters have range to work over:
  `database/liquibase/adb/006-fraud-graph.yaml` gains ~3 more fan-out sources
  across different days, ~2 more structuring sources across different weeks, and
  a second cycle (keeping accounts 33 and 1 intact);
  `database/liquibase/oracle/003-banking-rich.yaml` gains more cross-border
  `WIRE` rows across a wider date span so the date filter visibly changes the
  cross-border card.
- Risk Dashboard: the same filter shell (date range plus category toggles for
  rule violations and risk tier).

## 2. Support tickets through the gateway

`V_BNK_SUPPORT_TICKETS` ships as a commented-out changeset and the Mongo seed
already carries ~25 documents. If the ADB heterogeneous MongoDB gateway starts
resolving collections, three edits bring the Customer Care agent fully online:
uncomment the view changeset, add the view to the `BANKING_NL2SQL_CARE`
`object_list`, and drop the "unavailable" note from the agent role.

Blocked on the behaviour documented in
[`docs/known-limitation-mongodb-federation.md`](docs/known-limitation-mongodb-federation.md).

## 3. Verify the heterogeneous connectivity support matrix

The repo pins PostgreSQL 18 and MongoDB 8 without confirming those versions
appear in the matrix published by the ADB release it lands on. After a deploy,
run on ADB:

```sql
SELECT database_type, database_version, gateway_param_name, gateway_param_description
FROM   HETEROGENEOUS_CONNECTIVITY_INFO
WHERE  database_type IN ('postgres','mongodb');
```

If the listed range doesn't cover those versions, drop the container tags in
`deploy/ansible/databases/roles/dbstack/templates/*.service.j2` to a supported
one and update [`docs/federated-queries.md`](docs/federated-queries.md).

## 4. Secrets in OCI Vault

Passwords live in the git-ignored `.env` and in `terraform.tfvars` (mode
`0600`), which is proportionate for a PoC. Moving them to OCI Vault removes the
last plaintext secret from the working tree and from cloud-init's
`ansible_params.json`.

## 5. Harden the edge

- Replace the jump-host bastion with OCI Bastion sessions only, dropping the
  public IP from the ops instance.
- TLS termination at the load balancer.

## 6. Apache Iceberg support

Iceberg querying — Native Iceberg, Unified Metadata Catalog, Data Lake
Accelerator, GoldenGate→Iceberg streaming — is a **Lakehouse-workload**
capability. The ADB module provisions `db_workload = "OLTP"` because the
headline value here is Vector Search plus Select AI federated against production
data, both of which work on OLTP. Iceberg does not.

Three credible paths, each its own slice:

- **Switch to Lakehouse** (`db_workload = "DW"`). One line. Loses fast-path OLTP
  characteristics — fine if the gateway will only run analytical, federated, and
  Iceberg workloads.
- **Add a second ADB** (`module "adbs_lake"` with `db_workload = "DW"`) alongside
  the OLTP one. Keeps OLTP for Select AI and chat-style workloads and uses the
  Lakehouse instance for Iceberg. Roughly doubles ADB cost.
- **Wait for Iceberg on OLTP.** No public roadmap commitment; track the Iceberg
  query reference page.

Whichever path, add Iceberg changesets under `database/liquibase/adb/`
(`DBMS_CLOUD.CREATE_EXTERNAL_TABLE` against the relevant catalog) and a frontend
page that runs a federated Iceberg query.

References: [Workload
Types](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/about-autonomous-database-workloads.html),
[Iceberg
announcement](https://blogs.oracle.com/datawarehousing/post/iceberg-tables-autonomous-database).

## 7. Move the repo under the Oracle samples org

The canonical home is a subfolder of `oracle-autonomous-database-samples/select-ai/`
named `ai-data-gateway`. The transfer itself happens on the GitHub side; this
item tracks the repo-side prep.

- Update the clone URL and any "Repo:" lines to the new location. The only
  hard-coded reference left in the tree is in
  [`docs/known-limitation-mongodb-federation.md`](docs/known-limitation-mongodb-federation.md).
- Decide what happens to the current location: archive it with a pointer in the
  README, or leave it as a frozen mirror.

## 8. Active Data Guard standby as the federation source

Architecturally significant — real Oracle infrastructure work. Stand up an ADG
standby of the production Oracle and point the federation layer's links at the
active standby instead of the primary, so read-heavy demo traffic doesn't load
the primary.

- Provision the standby (Terraform module, networking, redo transport, broker
  config).
- Rewrite `database/liquibase/adb/002-db-links.yaml` so `ORAFREE_LINK` targets
  the standby service.
- Confirm the heterogeneous gateway (`PG_LINK`) is unaffected — it has nothing
  to do with ADG.
- Validate failover: standby promotion shouldn't break the federated path.
