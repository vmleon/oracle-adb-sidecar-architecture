# Roadmap

Ordered low-hanging-fruit → most complex. Each item is self-contained
unless flagged otherwise; sequencing reflects effort and blast radius,
not dependency.

---

## 1. Dynamic dashboard filtering — remaining work

The first slice landed: the Fraud Dashboard now has a date-range
filter (defaults to last 30 days) that re-issues
`/api/v1/fraud/patterns?from=…&to=…`, plus a "Loaded at HH:MM ·
window from → to" stamp. What's still ahead:

**Per-pattern threshold knobs.**

- Surface in the UI, defaulted to the current SQL constants (fanout
  ≥5, structuring ≥3 / ≥$25K, cycle min-amount $1000). Promote
  those constants to controller request params.

**Seed-data expansion (so filters have range).**

- `database/liquibase/adb/006-fraud-graph.yaml`: add ~3 more
  fan-out sources spread across different days, ~2 more
  structuring sources across different weeks, and a second
  cycle. Keep the canonical demo accounts (33, 1) intact.
- `database/liquibase/oracle/003-banking-rich.yaml`: a handful
  more cross-border WIRE rows in a wider date span, so the
  date-range filter visibly changes the cross-border card.

**Risk Dashboard.**

- Apply the same filter shell (date range + a couple of category
  toggles for rule violations / risk tier).

## 2. GitHub repo move under `oracle-autonomous-database-samples/select-ai/ai-data-gateway`

Per Mark's suggestion the canonical home is a subfolder of
`oracle-autonomous-database-samples/select-ai/` named
`ai-data-gateway`. Mark handles the GitHub-side transfer
mechanics; this item tracks the repo-side prep.

- README badge URLs / clone URL / "Repo:" lines updated to the
  new location.
- Any demo deck or collateral material produced after this point
  uses the new URL from day one.
- Sweep the codebase for hard-coded GitHub references (today
  the only hit is in `docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md`).
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
