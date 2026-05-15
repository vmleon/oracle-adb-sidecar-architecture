# AI Data Gateway — Explained for DBAs

## 1. What it actually is

An **AI Data Gateway** is **not a new product**. It's a _role_ that an Oracle database instance plays when it hosts **Select AI** on behalf of data living elsewhere.

> "An AI Data Gateway is an Autonomous AI Database instance that runs Select AI on behalf of local or external data sources. It does not contain the external data."

Naming history (this trips people up):

- **2024:** "Sidecar" (informal blog name)
- **2025:** "AI Proxy Database" (first official docs)
- **2026:** "AI Data Gateway" (current name; the older terms are still scattered in the docs)

So if a DBA reads "sidecar," "AI Proxy DB," and "AI Data Gateway" — they're the same thing.

## 2. Why it exists (the problem it solves)

Select AI does **NL → SQL** by sending an LLM:

1. Your natural-language prompt
2. **Metadata** (table/column names, comments, sample rows) from objects in the AI profile

That metadata has to live _inside_ an Autonomous AI Database (or AI Database 26ai) for Select AI to pick it up. The Gateway pattern lets you **expose remote tables as local objects** (views, external tables, federated tables) so Select AI can read their metadata — without copying the data.

The LLM never sees your data. It only sees metadata and writes SQL. Execution happens locally; the gateway pushes work to the source DBs via distributed query.

## 3. Relationship to Select AI and Autonomous

```
NL prompt ──► Select AI (in Gateway DB) ──► augmented prompt + metadata ──► LLM
                       │                                                    │
                       │◄──────── generated SQL ────────────────────────────┘
                       ▼
            Federated SQL execution
            ├── local tables in Gateway
            ├── Database Link → Postgres / MySQL / Snowflake / on-prem Oracle ...
            ├── Cloud Link    → another ADB
            ├── Table Hyperlink (external table) → another ADB
            └── Federated Table → another ADB
```

- **Select AI** = the NL2SQL / NL2Analytics feature (`SELECT AI ... USING profile`)
- **Autonomous AI DB** = the _preferred_ home for the Gateway (fully managed, all 4 federation mechanisms work)
- **AI Database 26ai (on-prem / DBaaS / Exadata)** = can also host Select AI and play the Gateway role, but practically **only Database Links** are available (see §5)

## 4. The four federation mechanisms compared

| Aspect                       | **Database Links**                                                                                                | **Cloud Links**                                            | **Table Hyperlinks**                                               | **Federated Tables**                                               |
| ---------------------------- | ----------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------- | ------------------------------------------------------------------ | ------------------------------------------------------------------ |
| What it is                   | Classic distributed-Oracle link, uses Heterogeneous Services / Gateway agents for non-Oracle                      | Provider-published shared object, consumer just "finds" it | Signed REST URL → exposed as external table on consumer            | Auto-managed wrapper around Hyperlinks across many objects/regions |
| Source databases             | Oracle (any edition) + Postgres, MySQL, SQL Server, DB2, Teradata, Redshift, Snowflake, Databricks, Salesforce, … | **ADB ↔ ADB only**                                         | **ADB ↔ ADB only**                                                 | **ADB ↔ ADB only**                                                 |
| Network                      | Direct TCP/SQL\*Net (or HS agent)                                                                                 | OCI internal, no SQL\*Net needed                           | HTTPS (REST)                                                       | HTTPS (REST)                                                       |
| Auth                         | Wallets, usernames/passwords, credentials                                                                         | None for the consumer — provider governs                   | Signed URL (time-limited)                                          | Scopes + grants, managed automatically                             |
| Direction                    | Bidirectional possible                                                                                            | Provider → audience (read-only)                            | Provider → consumer (read-only)                                    | Provider → consumer (read-only)                                    |
| Best for                     | Heterogeneous, on-prem, legacy, third-party clouds                                                                | Many-to-many sharing inside one org's ADBs                 | Ad-hoc, fine-grained, time-limited shares                          | Long-term, multi-object, cross-region                              |
| DBA effort                   | High (credentials per link)                                                                                       | Low (publish once, audience finds it)                      | Medium (URL per object)                                            | Low (define scope, consumer pulls)                                 |
| Works in AI DB 26ai on-prem? | **Yes**                                                                                                           | No                                                         | No                                                                 | No                                                                 |
| Created with                 | `CREATE DATABASE LINK` (+ `DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK` on ADB)                                         | `DBMS_CLOUD_LINK.REGISTER` (provider)                      | `DBMS_DATA_ACCESS.CREATE_URL` + `DBMS_CLOUD.CREATE_EXTERNAL_TABLE` | `DBMS_DATA_ACCESS.CREATE_FEDERATED_TABLE`                          |

Oracle's own positioning: _"Database Links in Autonomous Database are the past — Cloud Links are the future"_ (data warehousing blog). Translation: **inside an all-ADB estate, prefer Cloud Links / Hyperlinks / Federated Tables**. Database Links remain mandatory the moment a non-ADB participant joins the party.

## 5. Is this a pure cloud feature?

**Mostly yes, with one important nuance.**

- **Hosting the Gateway:** Select AI is supported in two places — Autonomous AI DB (cloud) and Oracle **AI Database 26ai** (on-prem / DBaaS / Exadata, January 2026 RU). So in principle a 26ai box on-prem can be the Gateway.
- **Federation mechanisms:** Three of the four (Cloud Links, Table Hyperlinks, Federated Tables) are **Autonomous-only** OCI features. They don't exist on-prem.
- **Therefore:** an on-prem AI Data Gateway is limited to **Database Links** for reaching external data. That works fine for talking to other on-prem Oracle, third-party DBs, or even ADB endpoints — but you lose the no-credential, sharing-model goodies.

Practical reading for a DBA:

- **All-on-prem shop, no OCI:** AI DB 26ai + Database Links. Works, but you're back to wallet/credential management.
- **Hybrid (on-prem ↔ ADB):** Either side can be the Gateway. Use Database Links across the boundary.
- **All-OCI / multi-cloud ADB:** Use Autonomous as Gateway; prefer Cloud Links / Federated Tables; fall back to Database Links only for non-Oracle sources.

## 6. Typical DBA Q&A

**Q: Do I have to move data into ADB to use Select AI?**
No. That's the whole point. Data stays put; only metadata is registered, and only metadata is sent to the LLM.

**Q: Does the LLM see my data?**
Not unless you ask Select AI to _run the result_ and feed rows back to a chat profile. NL2SQL alone sends schema/metadata only.

**Q: Will my Snowflake / Redshift / Postgres work?**
Yes, via Database Links. Heterogeneous-Services gateway agents (or DBMS_CLOUD-managed credentials on ADB) handle non-Oracle.

**Q: Can I use this with an old 19c on-prem database I can't upgrade?**
Yes, as a _source_. Put the Gateway on ADB or 26ai, point a DB Link at the 19c instance, expose views, register them in the AI profile.

**Q: How is this different from Oracle Database Gateway (the Heterogeneous Services product, e.g. DG4ODBC)?**
The classic **Oracle Database Gateway** is the _plumbing_ (a per-source agent + ODBC driver) that lets a DB Link reach a non-Oracle system. The **AI Data Gateway** is a _role_ an Autonomous/AI DB plays for Select AI. They're complementary: an AI Data Gateway often _uses_ a Database Gateway under the hood to reach Postgres, DB2, etc.

**Q: Licensing?**
Select AI is included with Autonomous AI Database (billed by ECPU + storage as usual). For AI DB 26ai on-prem, Select AI is part of Enterprise Edition AI features. Classic Oracle Database Gateway products (DG4ODBC, DG4DB2, …) are **separately licensed per source-system type** — that's the line item DBAs often miss. No specific "AI Data Gateway SKU" exists; the cost is the underlying ADB/AI DB + any classical gateway licenses you need.

**Q: Security?**
Local roles, RAS, and the source DB's own ACLs all apply. The Gateway is just another Oracle session running federated SQL. Wallets/credentials for DB Links live in `DBMS_CREDENTIAL`. Cloud Links and Federated Tables avoid that altogether by using provider-side scopes.

**Q: Latency / performance?**
You're doing distributed query. Push predicates down, materialize hot data in the Gateway if needed, and watch the explain plan — same rules as classic Oracle distributed SQL.

**Q: Can the Gateway itself also have local data?**
Yes — and it usually does. Local tables, views over remote tables, and federated objects can all sit in the same AI profile, and a single Select AI prompt can join them.

## 7. One-line definitions to keep handy

- **AI Data Gateway** — an Autonomous AI DB (or AI DB 26ai) acting as Select AI's home, exposing remote data via local metadata objects.
- **Database Link** — credential-based, classic Oracle pipe to any DB, Oracle or not, on-prem or cloud.
- **Cloud Link** — Autonomous-only, provider publishes once, consumers discover and read without credentials.
- **Table Hyperlink** — Autonomous-only, signed REST URL pointing at an Autonomous table; consumed as an external table.
- **Federated Table** — Autonomous-only, managed wrapper that automates Table Hyperlinks across many objects/regions.

## Sources

- [AI Data Gateway / sidecar — ADB Serverless docs](https://docs.oracle.com/en-us/iaas/autonomous-database-serverless/doc/select-ai-dblinks.html)
- [AI Proxy Database — AI DB 26ai docs](https://docs.oracle.com/en/database/oracle/oracle-database/26/selai/select-ai-sidecar-databases.html)
- [Select AI examples](https://docs.oracle.com/en-us/iaas/autonomous-database-serverless/doc/select-ai-examples.html)
- [Create Federated Tables using Table Hyperlinks](https://docs.oracle.com/en-us/iaas/autonomous-database-serverless/doc/create-federated-tables.html)
- [About Table Hyperlinks on Autonomous AI Database](https://docs.oracle.com/en-us/iaas/autonomous-database-serverless/doc/autonomous-table-hyperlink-about.html)
- [Provider-Scoped Table Hyperlink release notes (Dec 2025)](https://docs.oracle.com/en-us/iaas/releasenotes/autonomous-database-serverless/2025-12-provider-scoped-table-hyperlink.htm)
- [Oracle blog — Database Links are the past, Cloud Links the future](https://blogs.oracle.com/datawarehousing/database-links-in-autonomous-database-shared-are-the-past---cloud-links-are-the-future)
- [Oracle blog — Sidecar / Select AI for business users](https://blogs.oracle.com/autonomous-ai-database/unlocking-data-for-all-with-sidecar-empowering-business-users-with-aidriven-insights)
- [Database Heterogeneous Connectivity User's Guide (DG/HS agents)](https://docs.oracle.com/en/database/oracle/oracle-database/26/heter/heterogeneous-services-agents.html)
- [Oracle AI Database 26ai brings AI to on-prem](https://erp.today/oracle-ai-database-26ai-brings-enterprise-ai-to-on-premises-deployments/)
- [Autonomous AI Database Select AI product page](https://www.oracle.com/autonomous-database/select-ai/)
