# Reference

A portable field guide to the Oracle building blocks this repo uses: what each
one does, the DDL that creates it here, and what to change to port it to another
domain. Banking is the demo; the shapes below are the reusable part.

---

## 1. The AI Data Gateway

**What it is.** A separate Oracle AI Database attached alongside the production
databases, reaching into them over database links. AI features (NL2SQL, RAG,
agents, vector search) run on the gateway; the application keeps its existing
connections to production. Oracle documents the pattern as the **AI Proxy
Database** / **Select AI Sidecar**.

**Why a separate database.** The production stores keep their engine, version,
and lifecycle. Nothing is rehosted, no schema is rewritten, and no data is
copied into a parallel AI stack. The gateway is additive and removable.

**What it needs.** The gateway must be able to reach the production databases on
the network, and it must be able to authenticate to the model provider.

**To port it:** point the links at your production databases and replace the
`V_BNK_*` views with views over your own tables. Nothing else in the pattern
changes.

---

## 2. Federated views over `DB_LINK`

Oracle-to-Oracle links use a credential plus a direct connect string.
Heterogeneous targets (PostgreSQL, MySQL, SQL Server, MongoDB) go through
Oracle-Managed Heterogeneous Connectivity, selected by `gateway_params`.

```sql
BEGIN
  DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK(
    db_link_name    => 'PG_LINK',
    hostname        => 'databases01.db.vcn01.oraclevcn.com',
    port            => 5432,
    service_name    => 'postgres',
    credential_name => 'PG_CRED',
    gateway_params  => JSON_OBJECT('db_type' VALUE 'postgres'),
    ssl_server_cert_dn => NULL,
    directory_name  => NULL);
END;
/

CREATE OR REPLACE VIEW v_bnk_policies AS
  SELECT * FROM policies@PG_LINK;
```

**Two hard requirements**, both easy to miss:

- The `hostname` must be **DNS-resolvable from the ADB private endpoint** — an
  IP address is rejected. This repo passes the VCN-internal FQDN of the
  `databases` instance.
- Every link and credential is created inside a DROP-if-exists guard. A
  connection drop part-way through the changelog otherwise leaves a half-applied
  link, and the retry dies on `ORA-02011: duplicate database link name`.

Full walkthrough: [`docs/federated-queries.md`](docs/federated-queries.md).

**To port it:** one link per production database, one view per table you want AI
features to see. Keep the view names stable — they are the NL2SQL surface.

### Heterogeneous gateway limits worth knowing up front

- **MongoDB is not usable through the gateway.** Every collection reports
  `object not found` regardless of how the link is created; the gateway never
  issues a `listCollections` or `find` against the target. MongoDB stays
  connected directly by the application in this repo. Reproducer:
  [`docs/known-limitation-mongodb-federation.md`](docs/known-limitation-mongodb-federation.md).
- **The PostgreSQL gateway drops idle sessions after ~5 minutes** and the
  timeout is not tunable, so the first call after an idle period can fail and
  the next succeeds. One transparent retry absorbs it. Details:
  [`docs/known-limitation-pg-link-gateway.md`](docs/known-limitation-pg-link-gateway.md).
- **Check the support matrix for your target versions** before pinning them:

  ```sql
  SELECT database_type, database_version, gateway_param_name
  FROM   HETEROGENEOUS_CONNECTIVITY_INFO
  WHERE  database_type IN ('postgres','mongodb');
  ```

---

## 3. Select AI profiles (NL2SQL, RAG, chat)

A profile binds a model provider, a credential, and a set of objects the model
is allowed to see. Three modes are used here: NL2SQL (scoped to views), RAG
(scoped to a vector index), and chat (no datasource).

```sql
BEGIN
  DBMS_CLOUD_AI.CREATE_PROFILE(
    profile_name => 'BANKING_NL2SQL_TXN',
    attributes   => '{
      "provider": "oci",
      "credential_name": "OCI$RESOURCE_PRINCIPAL",
      "object_list": [
        {"owner": "ADMIN", "name": "V_BNK_CUSTOMERS"},
        {"owner": "ADMIN", "name": "V_BNK_TRANSACTIONS"}
      ],
      "region": "us-chicago-1",
      "oci_compartment_id": "<genai-compartment-ocid>",
      "oci_apiformat": "GENERIC"
    }');
END;
/
```

**`OCI$RESOURCE_PRINCIPAL` is the credential to use.** The database
authenticates to OCI as itself; no API key, private key, or fingerprint is ever
stored in the database or passed through deployment variables. It is enabled
once, as `ADMIN`:

```sql
BEGIN
  DBMS_CLOUD_ADMIN.ENABLE_RESOURCE_PRINCIPAL();
END;
/
```

This requires a dynamic group matching the database and a policy granting it
`use generative-ai-family` (and `read objects` for any bucket a vector index
reads). Both are Terraform resources here — `oci_identity_dynamic_group` and
`oci_identity_policy` in `deploy/terraform/identity.tf` — created in the tenancy
root so one policy can reach compartments anywhere in the tenancy. See
[`DEPLOY.md`](DEPLOY.md), "Tenancy permissions".

**The profile also needs an outbound network ACL:**

```sql
BEGIN
  DBMS_NETWORK_ACL_ADMIN.APPEND_HOST_ACE(
    host => '*.oci.oraclecloud.com',
    ace  => xs$ace_type(privilege_list => xs$name_list('http'),
                        principal_name  => 'ADMIN',
                        principal_type  => xs_acl.ptype_db));
END;
/
```

**Scoping is the design decision.** One profile per audience, each seeing only
the views that audience is allowed to query, rather than one profile over
everything. That is what makes per-agent least privilege possible below.

**To port it:** change `object_list` to your views and the profile names to your
domain. Table and column comments matter — the model reads them, so invest in
them.

---

## 4. The Select AI Agent framework

Four objects, in order: **tools** wrap profiles, **agents** own a profile and a
role, **tasks** carry the instruction and name their tools, and a **team**
sequences agents onto tasks.

```sql
DBMS_CLOUD_AI_AGENT.CREATE_TOOL(
  tool_name  => 'TXN_SQL_TOOL',
  attributes => '{"tool_type":"SQL","tool_params":{"profile_name":"BANKING_NL2SQL_TXN"}}');

DBMS_CLOUD_AI_AGENT.CREATE_AGENT(
  agent_name => 'TRANSACTION_ANALYST',
  attributes => '{"profile_name":"BANKING_NL2SQL_TXN",
                  "role":"Retrieve concrete facts only. Do not interpret.",
                  "enable_human_tool":"false"}');

DBMS_CLOUD_AI_AGENT.CREATE_TASK(
  task_name  => 'PULL_TXN_FACTS',
  attributes => '{"instruction":"Retrieve facts for: {query}.",
                  "tools":["TXN_SQL_TOOL"]}');

DBMS_CLOUD_AI_AGENT.CREATE_TEAM(
  team_name  => 'BANKING_INVESTIGATION_TEAM',
  attributes => '{"agents":[{"name":"TRANSACTION_ANALYST","task":"PULL_TXN_FACTS"}],
                  "process":"sequential"}');
```

**Contract details that cost time if you learn them late:**

- `CREATE_TASK` accepts a **single** prior task name in `"input"`. Passing
  several (as a string or a JSON array) yields `ORA-20051: Invalid value for
task object`. In a sequential team each task already receives its immediate
  predecessor's output, and the LLM can reach earlier tasks through the team's
  conversation history.
- `RUN_TEAM` requires a **catalog-registered conversation id**. It has to be
  created server-side before the call, not invented by the client. See
  [`docs/run-team-conversation-contract.md`](docs/run-team-conversation-contract.md).
- Give each agent the narrowest profile that lets it do its job. The
  `object_list` on that profile is the agent's data boundary.

**To port it:** keep the four-object shape and the sequential process; swap the
agent roles and instructions for your domain. A "gather facts → assess against
policy → add context → synthesise" pipeline generalises well beyond banking.

---

## 5. Vector RAG over documents

`CREATE_VECTOR_INDEX` ingests an Object Storage prefix, chunks it, embeds it,
and exposes it to a RAG-mode profile. No separate vector database, no embedding
pipeline to operate.

```sql
BEGIN
  DBMS_CLOUD_AI.CREATE_VECTOR_INDEX(
    index_name => 'BANKING_POLICY_INDEX',
    attributes => '{"vector_db_provider":"oracle",
                    "location":"https://objectstorage.<region>.oraclecloud.com/n/<ns>/b/<bucket>/o/",
                    "object_storage_credential_name":"OCI$RESOURCE_PRINCIPAL",
                    "profile_name":"BANKING_RAG",
                    "chunk_size":1500,
                    "chunk_overlap":300}');
END;
/
```

The index is attached to a profile via `"vector_index_name"`, and that profile is
wrapped in a `RAG`-type tool for an agent to call.

**To port it:** drop your policy/procedure/manual documents in a bucket and
point `location` at it. Chunk size 1500 with 300 overlap is a reasonable default
for prose policy documents.

---

## 6. SQL Property Graph

Graph pattern matching in SQL, over ordinary tables. No separate graph database
and no export step.

```sql
CREATE PROPERTY GRAPH banking_graph
  VERTEX TABLES (local_accounts KEY (account_id))
  EDGE TABLES (
    transaction_edges KEY (edge_id)
      SOURCE KEY (src_account_id) REFERENCES local_accounts (account_id)
      DESTINATION KEY (dst_account_id) REFERENCES local_accounts (account_id));

SELECT * FROM GRAPH_TABLE (banking_graph
  MATCH (a)-[t1]->(b)-[t2]->(c)-[t3]->(a)
  COLUMNS (a.account_id AS a_id, t1.amount AS amt));
```

**To port it:** any domain with entities and transfers between them — claims and
providers, devices and sessions, suppliers and shipments. Cycle, fan-out, and
threshold-stacking patterns are domain-independent fraud shapes.

**Constraint worth planning around:** edges need source and destination key
columns on the edge table. If the underlying table is a blockchain table it
cannot be altered to add them, so the graph has to be built on a mirrored copy
(which is what this repo does on the gateway).

---

## 7. Blockchain tables

A tamper-evident ledger table, hash-chained by the database. The application
reads it like any other table — the chain columns are hidden.

```sql
CREATE BLOCKCHAIN TABLE transactions (...)
  NO DROP UNTIL 0 DAYS IDLE
  NO DELETE UNTIL 16 DAYS AFTER INSERT
  HASHING USING "SHA2_512" VERSION "v1";
```

Verify with `DBMS_BLOCKCHAIN_TABLE.VERIFY_ROWS`, run on the owning database
directly (not through a database link).

**Constraints inherited from the feature:** single-row `INSERT` only (no
`MERGE`, no `INSERT /*+ APPEND */`, no distributed-transaction inserts), no
`UPDATE` or `DELETE`, and no `ALTER TABLE` shape changes.

**To port it:** any append-only record of consequence — payments, audit events,
consent records, chain-of-custody.
