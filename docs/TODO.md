# TODO

Upgrades and fixes to track for this project.

- [ ] Implement Select AI Agents on top of the federated queries.
- [ ] Fix the MongoDB federation — currently not working (see [ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md](ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md)).
- [ ] Move the database container from 26ai to 19c to make it more realistic.
- [ ] Take the information from the standby Data Guard.
- [ ] Harden cloud-init / Ansible retries (SQLcl TTY failures, ADB Liquibase DDL idempotency).
