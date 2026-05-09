# Roadmap

Ordered low-hanging-fruit → most complex. Each item is self-contained
unless flagged otherwise; sequencing reflects effort and blast radius,
not dependency.

---

## 1. Active Data Guard standby as federation source

Architecturally significant — real Oracle infra work. Stand up an
ADG standby of the production Oracle, then point the federation
layer's DB_LINKs at the active standby instead of the primary so
read-heavy demo traffic doesn't load the primary.

- Provision the standby (Terraform module, networking, redo
  transport, broker config).
- Rewrite `database/liquibase/adb/002-db-links.yaml` so
  `ORAFREE_LINK` targets the standby service.
- Confirm the heterogeneous gateway (`PG_LINK`) is unaffected — it
  has nothing to do with ADG.
- Validate failover: standby promotion shouldn't break the
  federated path.
