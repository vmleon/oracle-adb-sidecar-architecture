# Docs

Deep dives that don't belong in the root-level docs. Start at
[`../README.md`](../README.md) for what the demo is and
[`../DEPLOY.md`](../DEPLOY.md) for how to stand it up.

| File                                                                             | What it covers                                                                                                                                                                                                     |
| -------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| [ai-data-gateway.md](ai-data-gateway.md)                                         | The AI Data Gateway pattern explained for a non-specialist audience.                                                                                                                                               |
| [federated-queries.md](federated-queries.md)                                     | How Autonomous AI Database 26ai reaches the production Oracle and PostgreSQL through `DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK`, and the two hard requirements (DNS-resolvable hostname, Mongo data outside `admin`). |
| [troubleshooting.md](troubleshooting.md)                                         | Day-two playbook per tier — how to reach each host, tail each log, and probe each database from the ops bastion.                                                                                                   |
| [run-team-conversation-contract.md](run-team-conversation-contract.md)           | The catalog-registered conversation id that `DBMS_CLOUD_AI_AGENT.RUN_TEAM` requires, and how the backend satisfies it.                                                                                             |
| [known-limitation-mongodb-federation.md](known-limitation-mongodb-federation.md) | Why MongoDB is not federated through the gateway — full reproducer for the heterogeneous-gateway `object not found` behaviour.                                                                                     |
| [known-limitation-pg-link-gateway.md](known-limitation-pg-link-gateway.md)       | The two `PG_LINK` heterogeneous-gateway failure modes and the keep-warm mitigation that is always on.                                                                                                              |
