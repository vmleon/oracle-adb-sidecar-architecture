# Select AI Agents — demo runbook

After a successful deploy, open the LB IP and click `/agents` in the nav.
Each of the five chips below maps to a deterministic narrative seeded into
the banking dataset. Expected high-level findings are listed so a runner
can spot-check that the trace is producing the right shape.

## Quick checks

- `curl http://<lb_public_ip>/api/v1/health` returns 200.
- The `/agents` page renders with 5 chips visible and an empty conversation.
- A new conversation gets a fresh `conversationId` on first response.

## Narrative 1 — Carol Diaz, structuring

**Chip:** _Are there any suspicious patterns on Carol Diaz's accounts this month?_

Expected:

- TxAnalyst surfaces 4 wires of $9,500–$9,800 to BY between 2026-04-09 and 2026-04-14.
- Compliance cites `R-AML-005` (structuring) and `R-OFAC-001` (BY high-risk),
  with at least one quoted line from `aml-and-ctr-procedures.md` §3.3.
- CARE returns Carol's KYC `VERIFIED`, risk `LOW`, joined 2017-11-04.
- Synthesiser concludes HIGH risk; recommends SAR review within 30 days.

## Narrative 2 — Bob Chen, Reg E dispute

**Chip:** _Bob Chen disputed a $230 charge — what should we do?_

Expected:

- TxAnalyst returns the two $230 Acme Hardware charges 4 minutes apart.
- Compliance cites `R-REGE-002` (60-day window) and quotes
  `reg-e-dispute-handling.md` §2 / §3.
- CARE returns Bob's KYC `VERIFIED`, risk `MEDIUM`.
- Synthesiser recommends provisional credit within 10 business days.

## Narrative 3 — Alice Morgan, risk profile

**Chip:** _Summarise Alice Morgan's risk profile._

Expected:

- TxAnalyst surfaces 5 cash deposits $9,000–$9,400 over 10 days.
- Compliance cites `R-AML-002` and `R-KYC-003`.
- CARE flags KYC `EXPIRED`, risk `HIGH`.
- Synthesiser recommends KYC refresh + EDD before next outbound activity.

## Narrative 4 — Jamal Reed, frozen account

**Chip:** _Why is Jamal Reed's checking account frozen?_

Expected:

- TxAnalyst returns 3 declined card auth in 1 hour, account `FROZEN`.
- Compliance cites `R-FRAUD-007` (velocity).
- CARE returns Jamal KYC `VERIFIED`, risk `LOW`.
- Synthesiser recommends card reissue + freeze release on identity confirm.

## Narrative 5 — policy-only

**Chip:** _What policies apply to international wires above $10K?_

Expected:

- TxAnalyst returns "no transaction context required."
- Compliance cites `R-WIRE-001` and quotes `wire-transfer-sop.md` §2 and `ofac-sanctions-screening.md` §3.
- CARE returns "no relevant context."
- Synthesiser composes a 1-paragraph guidance answer.

## Multi-turn check

After narrative 1, type _"focus on the OFAC angle"_. The next response
should reuse the same `conversationId` (visible in the network panel)
and produce a focused follow-up about Belarus.

## Deferred state — Mongo support tickets

Until `docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md` is
resolved, the CARE agent does **not** see support tickets and answers
about ticket history return "support-ticket lookups are temporarily
unavailable." This is by design; flipping the switch is documented in
`docs/superpowers/specs/2026-04-28-select-ai-agents-design.md` §14.
