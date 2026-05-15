# Presentation — Live AI Hub using the AI Data Gateway

Source of truth for the deck. One section per slide, in
presentation order. Each slide has a **headline** (what the
slide says at a glance), **talking points** (the body), and
where relevant a **show** line that names the screen in the
live demo to flip to.

The deck is built directly from this file — when the content
here changes, the slides change.

---

## Slide 1 — Title

**Headline:** Live AI Hub using the AI Data Gateway.

**Talking points:**

- One-line subtitle: "Keep your current app. Keep your current
  databases. Bolt on AI."
- Working live demo of the Oracle "AI Proxy Database" pattern
  documented in the Oracle Database 26ai Select AI User's Guide.
- This is an end-to-end PoC running on real Oracle infra, not a
  slideware mock.

---

## Slide 2 — The problem we're solving

**Headline:** AI-powered attacks are landing on AI-less defences.

**Talking points:**

- Synthetic identities at onboarding, deepfake voice on phone
  banking, automated credential stuffing, real-time
  transaction-pattern attacks tuned by adversaries with LLMs.
- Defences need the same generation of tooling — hybrid vector
  search, NL2SQL, agentic investigations.
- Most enterprise data still sits on Oracle 19c (or older). A
  multi-year platform migration is the wrong gate for shipping
  AI features now.

---

## Slide 3 — The shift

**Headline:** Bring AI to the data, not the data to the AI.

**Talking points:**

- The instinct is to copy data into a separate AI stack
  (warehouse + vector DB + agent framework + glue). That's a
  new pipeline, new copies, new governance surface, new attack
  surface.
- Flip it: leave the data where it is, run AI next to it.
- The database becomes the AI runtime — SQL, vectors, RAG, and
  agents over the same governed data.

---

## Slide 4 — The pattern

**Headline:** Attach an AI Data Gateway alongside production.

**Talking points:**

- An Autonomous AI Database 26ai instance is bolted on
  alongside the existing production databases.
- It reaches into the existing data through `DB_LINK` views
  (Oracle and PostgreSQL today; MongoDB is deferred).
- The application is unchanged. AI features call the gateway;
  everything else keeps using the production stores.
- Oracle's name for this in the docs: **AI Proxy Database** /
  **Select AI Sidecar**. We call it the **AI Data Gateway**
  because it's what the audience hears.

**Show:** the README "What stays. What's added." mermaid
diagram.

---

## Slide 5 — What stays. What's added.

**Headline:** The box on top doesn't change to get the box at
the bottom.

**Talking points:**

- Stays: the application, its connections, the production
  databases, their lifecycle.
- Added: one Autonomous AI Database 26ai instance, attached
  through `DB_LINK`.
- This is a stepping stone, not an end state. The AI Data
  Gateway buys time: ship AI features now while the rest of the
  migration runs on its own clock.
- When 26ai lands on the production side, the same architecture
  flips cleanly — the gateway either folds into the production
  database or stays as a dedicated AI tier. No rewrite either
  way.

---

## Slide 6 — Architecture at a glance

**Headline:** Six routes, four data tiers, one extra database.

**Talking points:**

- Frontend (Angular) → backend (Spring Boot) → either the
  production databases directly, or the AI Data Gateway.
- Gateway resolves `V_BNK_*` views over `DB_LINK` back into the
  production Oracle and Postgres.
- Vector index, agent team, and NL2SQL all live inside the
  gateway.

**Show:** the README Architecture mermaid diagram.

---

## Slide 7 — Deployability

**Headline:** Autonomous is the easy on-ramp. On-prem ships the
same pieces.

**Talking points:**

- Autonomous AI Database 26ai is the fastest path — managed
  credentials, zero install, ready in minutes.
- Oracle AI Database 26ai went GA on-prem (Linux x86-64) in
  January 2026. Select AI, AI Proxy, the agent framework, and
  hybrid vector indexes all ship in that release.
- A few conveniences are cloud-only (managed credentials,
  Cloud Links, Table Hyperlinks) — on-prem uses the classic
  Database Gateway and Database Links to do the same job.
- Bottom line: pick the deployment that matches your operating
  model. The architecture doesn't change.

---

## Slide 8 — Demo intro

**Headline:** Six routes, same dataset, four ways to ask
questions.

**Talking points:**

- One banking dataset: customers, accounts, branches,
  transactions (Oracle), policies and rules (Postgres), support
  tickets (Mongo).
- Six routes on the same UI: Risk Dashboard, Fraud Dashboard,
  Current System, AI Data Gateway, Agent Team, Measurements.
- We'll walk all six.

---

## Slide 9 — Demo: Risk Dashboard (`/risk`)

**Headline:** What a compliance officer sees today — no AI in
the loop.

**Talking points:**

- KPI strip + five chart cards built straight off the
  production databases.
- Every chart cites the rule and policy codes that drive it
  (`R-AML-005`, `P-CTR-01`, …).
- Baseline: this is the human view of the data. The agent team
  later narrates the same patterns in plain English.

**Show:** `/risk` live.

---

## Slide 10 — Demo: Fraud Dashboard (`/fraud`)

**Headline:** Pattern-level fraud via SQL Property Graph.

**Talking points:**

- A `banking_graph` on the gateway models accounts as vertices
  and transactions as edges.
- Three `GRAPH_TABLE MATCH` queries detect round-trip cycles
  (A→B→C→A), fan-out, and structuring (sub-$10K stacking).
- Plus cross-border wire flows with OFAC-flagged jurisdictions.
- Each match comes with a deterministic risk score.
- Tech callout: **Oracle SQL Property Graph** — graph patterns
  in SQL, no separate graph database.

**Show:** `/fraud` live.

---

## Slide 11 — Demo: Current System (`/app`)

**Headline:** What the application sees today.

**Talking points:**

- Backend opens direct JDBC/Mongo connections to each
  production database.
- Five cards, one per table, each with a wall-clock latency
  badge.
- This is the baseline the AI Data Gateway path is compared
  against.

**Show:** `/app` live, click once and let cards fill in.

---

## Slide 12 — Demo: AI Data Gateway (`/ai-data-gateway`)

**Headline:** Same data, same cards — now routed through the
gateway.

**Talking points:**

- Backend now talks to Autonomous AI Database 26ai instead of
  the production databases.
- The gateway resolves `V_BNK_*` views over `DB_LINK` back into
  Oracle and Postgres.
- Latency badges show the federated hop's cost (compare with
  `/app` side by side).
- Tech callout: **AI Data Gateway** — `DBMS_CLOUD_ADMIN
.CREATE_DATABASE_LINK` to a heterogeneous gateway, then plain
  SQL views over the result.

**Show:** `/ai-data-gateway` live, compare badges to `/app`.

---

## Slide 13 — Demo: Agent Team (`/agents`)

**Headline:** Four agents collaborating inside the database.

**Talking points:**

- One prompt → one `DBMS_CLOUD_AI_AGENT.RUN_TEAM` call →
  Transaction Analyst, Compliance Officer (SQL + RAG over a
  policy-doc vector index), Customer Care Liaison, Case
  Synthesiser.
- The UI shows the final synthesised answer plus a per-task
  execution trace.
- Tech callout: **Select AI Agent framework** + **Hybrid Vector
  Index** for the policy RAG tool.

**Show:** `/agents` live, run one of the five demo questions,
expand the trace.

---

## Slide 14 — Demo: Measurements (`/measurements`)

**Headline:** How much does the federated path cost? Here's the
data.

**Talking points:**

- Wall-clock timing for every query, persisted asynchronously
  to the gateway.
- Side-by-side direct vs federated means and p95, plus a
  distribution chart with outlier trim.
- Answers "is federated slower?" with a real number instead of
  a hand-wave.

**Show:** `/measurements` live.

---

## Slide 15 — Technology callouts

**Headline:** What's powering each screen.

**Talking points (one row per tech):**

- **Autonomous AI Database 26ai** — the AI Data Gateway itself.
  Visible on every screen that hits the gateway (`/fraud`,
  `/ai-data-gateway`, `/agents`, `/measurements`).
- **Select AI / NL2SQL** — `V_BNK_*` views as the NL2SQL
  surface. Drives every agent tool on `/agents`.
- **Select AI Agent framework** — `DBMS_CLOUD_AI_AGENT
.RUN_TEAM` orchestrates the four agents. `/agents`.
- **AI Data Gateway** (a.k.a. AI Proxy Database / Select AI
  Sidecar) — `DB_LINK` views into Oracle and Postgres.
  `/ai-data-gateway`.
- **Oracle SQL Property Graph** — `GRAPH_TABLE MATCH` on
  `banking_graph` for cycle / fan-out / structuring detection.
  `/fraud`.
- **Blockchain Tables** — production `transactions` is created
  as a `BLOCKCHAIN TABLE` with SHA2_512 hash chaining; tamper
  detection via `DBMS_BLOCKCHAIN_TABLE.VERIFY_ROWS`. Invisible
  to the app — the chain columns are hidden, reads are
  unchanged. Mention once during `/app` or in this slide.
- **Hybrid Vector Index** — policy-doc RAG used by the
  Compliance Officer agent. `/agents`.

---

## Slide 16 — Domain framing

**Headline:** Banking is the demo. The pattern travels.

**Talking points:**

- The shape — production stores stay, gateway federates, AI
  features layer on top — is industry-agnostic.
- Swap points (what changes per industry, what doesn't):
  - **Data model** changes (banking → claims, EHRs, telco CDRs,
    retail orders, manufacturing telemetry).
  - **Policies and rules** change (AML/OFAC → HIPAA, GDPR,
    PCI-DSS, supplier compliance).
  - **Agent roles** change (Transaction Analyst → Claims
    Examiner / Clinician / Network Ops / Buyer).
  - **The pattern stays the same**: production stores
    unchanged, gateway federates, vector + agents on top.

---

## Slide 17 — Migration runway

**Headline:** Ship AI now. Migrate on your own clock.

**Talking points:**

- No big-bang re-platform. No parallel rewrite. No application
  freeze.
- The current architecture stays the current architecture —
  the gateway is additive.
- When the production side moves to 26ai, the gateway either
  folds into the production database or stays as a dedicated
  AI tier. The application doesn't care either way.
- Closes the "attackers have AI, defenders don't" window today,
  on data you already have.

---

## Slide 18 — Closing

**Headline:** Try it on your data.

**Talking points:**

- The repo is a working deployment — Terraform + Ansible +
  Liquibase, runnable end-to-end.
- The same pattern lifts to your production estate: pick the
  databases, drop in `V_BNK_*`-style views, point your AI
  features at the gateway.
- Pointer to the Oracle docs page that defines the pattern
  formally (Oracle Database 26ai Select AI User's Guide —
  "Use Autonomous AI Database as an AI Proxy for Select AI").
- Pointer to this repo.
