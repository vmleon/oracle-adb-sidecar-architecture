# Roadmap

Ordered low-hanging-fruit → most complex. Each item is self-contained
unless flagged otherwise; sequencing reflects effort and blast radius,
not dependency.

---

## 1. Docs naming + accuracy alignment

Mark's review (May 2026) called out five things that need to land
across all the prose:

- The pattern is **Live AI Hub using the AI Data Gateway** — _Live
  AI Hub_ is the marketing umbrella, _AI Data Gateway_ is the
  feature. Drop "ADB sidecar" / "sidecar" everywhere.
- The database product is **Autonomous AI Database 26ai**, not
  "ADB 26ai". Note that the same pattern works on **Oracle AI
  Database** generally, including 19c minus vector / RAG —
  even though this demo requires 26ai.
- The "Oracle 19c" production box actually runs Oracle Database
  Free 26ai (no free 19c binary). Reword to: _"Oracle Database
  Free 26ai container, used as a stand-in for an existing
  Oracle 19c production database."_
- Federation reach today is **Oracle + PostgreSQL only**.
  MongoDB is connected directly by the app and is _deferred
  sidecar coverage_. Reword the lede to match what works.
- Agent text reads **Oracle Select AI Agent framework** on first
  use, **Select AI Agent framework** thereafter. Phrase like
  _"Agent teams built using the Select AI Agent framework…"_.

Files in scope (text only — no code or schema changes):

- `README.md`: lede, all three mermaid diagrams (architecture,
  deployment, runtime), the `/measurements` and `/agents`
  sections, screenshot captions, deferred-MongoDB note.
- `DEPLOY.md`: title + the "Provisions VCN, ADB 26ai…" line.
- `NOTES.md`: pass over the iteration-history references.
- `docs/FEDERATED_QUERIES.md`,
  `docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md`,
  `docs/TROUBLESHOOTING.md`: pattern-name + product-name pass.

Mermaid labels move in this item. The PNG screenshots in
`images/` still show "Oracle 19c" and "ADB sidecar" — flag a
re-render but defer until item #2 lands and the deployed
frontend matches.

## 2. Frontend naming alignment + chart title

Mirror the docs naming pass through the Angular app so what the
demo audience sees lines up with the new terminology, and apply
the chart title rename Mark called out verbatim.

- `src/frontend/src/index.html`: `<title>`.
- `src/frontend/src/app/app.ts`: top-of-page header.
- `src/frontend/src/app/nav/nav.component.ts`: "ADB sidecar"
  pill → "AI Data Gateway".
- `src/frontend/src/app/pages/sidecar-page.component.ts`: rename
  the file to `ai-data-gateway-page.component.ts`, update the
  H2 + body copy, button labels, and the readiness probe label.
- `src/frontend/src/app/app.routes.ts`: route `/sidecar` →
  `/ai-data-gateway`. Add a redirect from `/sidecar` so any
  existing share links keep resolving.
- `src/frontend/src/app/pages/measurements-page.component.ts`:
  box-plot H3 → "Distribution of direct vs federated query
  runtime (ms)". Pass over surrounding copy to switch
  "Select AI Agents" → "Select AI Agent framework" phrasing.
- `images/sidecar.png` → `images/ai-data-gateway.png`; update
  the README reference.

## 3. Dynamic dashboard filtering (with seed-data expansion)

Make the dashboards feel live — show that data has actually
landed and that the user can adjust the lens. Start small on the
Fraud Dashboard (the most recently stabilised page), expand the
seed data so filtering is visibly meaningful, then extend the
same filter shape to the Risk Dashboard.

**Phase 1 — Fraud Dashboard filters.**

- Date-range filter (default "last 30 days") that re-issues
  `/api/v1/fraud/patterns` with `from`/`to` query params. The
  controller threads them into `WHERE occurred_at BETWEEN…` for
  the three graph queries and into the cross-border-wires SQL.
- Per-pattern threshold knobs in the UI, defaulted to the
  current SQL constants (fanout ≥5, structuring ≥3 / ≥$25K,
  cycle min-amount $1000). Promote those constants to controller
  request params.
- "Data loaded at HH:MM" stamp per card so the audience can see
  the dashboard isn't static.

**Phase 2 — seed-data expansion (so filters have range).**

- `database/liquibase/adb/006-fraud-graph.yaml`: add ~3 more
  fan-out sources spread across different days, ~2 more
  structuring sources across different weeks, and a second
  cycle. Keep the canonical demo accounts (33, 1) intact.
- `database/liquibase/oracle/003-banking-rich.yaml`: a handful
  more cross-border WIRE rows in a wider date span, so the
  date-range filter visibly changes the cross-border card.

**Phase 3 — Risk Dashboard.**

- Apply the same filter shell (date range + a couple of category
  toggles for rule violations / risk tier).

## 4. Code + infra rename — `adbsidecar` → `ai_data_gateway`

The string `adbsidecar` is wired through the build, the runtime,
the cloud resources, and the schema comments. Renaming aligns
the deployed system with Mark's feedback but has real blast
radius — sequence this **after** the Dev/PM walkthrough so the
demo isn't sitting on a half-renamed deployment.

- Java package: `dev.victormartin.adbsidecar.back.*` →
  `dev.victormartin.aidatagateway.back.*`. Touches every
  controller / service / config; let the IDE do the move and
  follow with an `import` audit.
- `src/backend/build.gradle`: group / artifact name.
- `.env` `PROJECT_NAME="adbsidecar"` →
  `PROJECT_NAME="ai-data-gateway"` and the matching default in
  `deploy/tf/app/variables.tf`. This forces `terraform destroy`
  / re-apply because the value is baked into VCN, compute, LB,
  and ADB display names.
- `deploy/ansible/back/roles/java/files/back.service.j2`:
  `Description=AI Data Gateway Backend`.
- `manage.py`: docstring + the "ADB Sidecar Architecture — Setup"
  banner.
- `deploy/tf/modules/ops/userdata/bootstrap.tftpl`,
  `deploy/tf/app/network.tf`: header comments.
- Liquibase YAML headers in
  `database/liquibase/{adb,oracle,postgres}/`: pattern-name
  comment pass; no table or column identifiers change.
- `database/mongo/init.js`: comment header.

After the rename, regenerate the architecture screenshots so
`images/*.png` match the deployed labels.

## 5. GitHub repo move under `oracle-autonomous-database-samples/select-ai/ai-data-gateway`

Per Mark's suggestion the canonical home is a subfolder of
`oracle-autonomous-database-samples/select-ai/` named
`ai-data-gateway`. Mark handles the GitHub-side transfer
mechanics; this item tracks the repo-side prep.

- README badge URLs / clone URL / "Repo:" lines updated to the
  new location.
- Any demo deck or collateral material produced after this point
  uses the new URL from day one.
- Sweep the codebase for hard-coded GitHub references (today
  the only hit is in `README.md`).
- Decide what to do with the current
  `vmleon/oracle-adb-sidecar-architecture` repo: archive with a
  pointer in the README, or leave as a frozen mirror.

## 6. Active Data Guard standby as federation source

Architecturally significant — real Oracle infra work. Stand up
an ADG standby of the production Oracle, then point the
federation layer's DB_LINKs at the active standby instead of the
primary so read-heavy demo traffic doesn't load the primary.

- Provision the standby (Terraform module, networking, redo
  transport, broker config).
- Rewrite `database/liquibase/adb/002-db-links.yaml` so
  `ORAFREE_LINK` targets the standby service.
- Confirm the heterogeneous gateway (`PG_LINK`) is unaffected —
  it has nothing to do with ADG.
- Validate failover: standby promotion shouldn't break the
  federated path.
