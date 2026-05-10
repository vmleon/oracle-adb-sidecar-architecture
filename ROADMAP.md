# Roadmap

Ordered low-hanging-fruit → most complex. Each item is self-contained
unless flagged otherwise; sequencing reflects effort and blast radius,
not dependency.

---

## 1. Dynamic dashboard filtering — remaining work

The Fraud Dashboard date-range filter and "loaded at" stamp already
shipped; what's still ahead:

- Per-pattern threshold knobs in the UI, defaulted to the current
  SQL constants (fanout ≥5, structuring ≥3 / ≥$25K, cycle
  min-amount $1000). Promote those constants to controller request
  params.
- Seed-data expansion so filters have range:
  `database/liquibase/adb/006-fraud-graph.yaml` gets ~3 more fan-out
  sources across different days, ~2 more structuring sources across
  different weeks, and a second cycle (keep accounts 33, 1 intact);
  `database/liquibase/oracle/003-banking-rich.yaml` gets a handful
  more cross-border WIRE rows in a wider date span, so the
  date-range filter visibly changes the cross-border card.
- Risk Dashboard: apply the same filter shell (date range + a couple
  of category toggles for rule violations / risk tier).

## 2. GitHub repo move under `oracle-autonomous-database-samples/select-ai/ai-data-gateway`

Per Mark's suggestion the canonical home is a subfolder of
`oracle-autonomous-database-samples/select-ai/` named
`ai-data-gateway`. Mark handles the GitHub-side transfer; this
item tracks the repo-side prep.

- Update README badge URLs, clone URL, and "Repo:" lines to the new
  location. The only hard-coded reference left in the codebase
  today is in
  `docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md`.
- Decide what to do with the current
  `vmleon/oracle-adb-sidecar-architecture` repo: archive with a
  pointer in the README, or leave as a frozen mirror.

## 3. Active Data Guard standby as federation source

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
