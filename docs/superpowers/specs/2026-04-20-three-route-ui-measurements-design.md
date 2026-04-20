# Three-route UI + direct-vs-federated measurements

_Date: 2026-04-20_

## Problem

The current UI is a single page with two buttons that load the banking dataset
directly from the container databases and through the ADB 26ai sidecar. It
proves the federated path works but doesn't tell the product story
("stepping-stone modernization") and it gives us no answer to the customer
objection _"federated queries will be slower — by how much?"_

## Goals

1. Reframe the UI around the three stages of the stepping-stone pattern:
   current app, sidecar-fronted app, future AI features. Each stage gets its
   own route.
2. Measure and visualize the round-trip time of every banking query along both
   paths (direct vs federated), so the federated-tax question has a data
   answer.
3. Update the README so the coexistence narrative is front and center: keep
   your current app and its lifecycle, layer 26ai on top through the sidecar,
   consolidate datasources on your own schedule.

## Non-goals

- Building the actual 26ai AI features (Vector Search, Select AI Agents, etc.)
  — `/future` is a static placeholder in this iteration.
- Fixing the documented Mongo-via-sidecar gateway bug. The `/sidecar` page
  skips the Mongo federated request entirely.
- Any kind of authentication, per-user history, or persistence across
  environments — measurements live in the same ADB sidecar the architecture
  already provisions.
- Pretty-number formatting beyond what Chart.js gives out of the box.

## Routes

| Path            | Purpose                                                                  |
| --------------- | ------------------------------------------------------------------------ |
| `/`             | Redirect to `/app`.                                                      |
| `/app`          | Current-app path. Backend queries each container DB directly.            |
| `/sidecar`      | Sidecar path. Backend queries ADB; ADB resolves `V_*` views via DB_LINK. |
| `/future`       | Static placeholder: "Select AI Agents feature goes here."                |
| `/measurements` | Performance dashboard comparing direct vs federated.                     |

Top nav bar is shared across all routes and shows the four destinations.

## Data flow — one button, N parallel requests

Clicking **Load banking data** on `/app` or **Load banking data via ADB
sidecar** on `/sidecar` fans out into N parallel HTTP calls (one per table):

- `/app` fires 5 requests: `accounts`, `transactions`, `policies`, `rules`,
  `support_tickets` — all with `route=direct`.
- `/sidecar` fires 4 requests: `accounts`, `transactions`, `policies`, `rules`
  — all with `route=federated`. The `support_tickets` card renders a static
  "Not available via sidecar" note and no HTTP request is issued.

Each card shows a loading skeleton until its own request returns; cards fill
in independently. Every response includes the wall-clock elapsed time and the
card shows it as a badge ("47.3 ms") next to the table header.

A single button click shares one `runId` (UUIDv4 generated in the frontend and
passed as a query param) so the 5-or-4 measurements produced by that click can
be grouped on `/measurements`.

## Measurement semantics

- **Boundary:** the backend times exactly one `JdbcTemplate.query…` or
  `MongoTemplate.findAll` call. `System.nanoTime()` before, `System.nanoTime()`
  after, difference converted to milliseconds with microsecond precision
  (`NUMBER(10,3)`).
- **What's _not_ counted:** nothing else. HTTP handling, JSON serialization,
  and the measurement-row INSERT are all outside the timed region.
- **Async insert:** the measurement row is persisted after the HTTP response
  has been returned, via `@Async` / a Spring `TaskExecutor`. If the insert
  itself fails, the failure is logged and swallowed — it must never affect the
  user-facing response.
- **Wall-clock only:** no DB-side stats (`V$SQL.ELAPSED_TIME` etc.) in this
  iteration. Wall-clock is what the customer feels and is comparable across
  the heterogeneous engines.

### Schema — `QUERY_MEASUREMENTS` in ADB

Provisioned by `database/liquibase/adb/003-measurements.yaml`.

| Column          | Type                                  | Notes                                                                                                                      |
| --------------- | ------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| `id`            | `NUMBER GENERATED ALWAYS AS IDENTITY` | Primary key.                                                                                                               |
| `query_id`      | `VARCHAR2(64)`                        | Stable label: `oracle.accounts`, `oracle.transactions`, `postgres.policies`, `postgres.rules`, `mongo.support_tickets`.    |
| `route`         | `VARCHAR2(16)`                        | `direct` or `federated`.                                                                                                   |
| `elapsed_ms`    | `NUMBER(10,3)`                        | Wall-clock, ms. µs precision.                                                                                              |
| `rows_returned` | `NUMBER`                              | Size of the result set (or document count).                                                                                |
| `success`       | `NUMBER(1)`                           | 1 on success, 0 on caught exception.                                                                                       |
| `error_class`   | `VARCHAR2(128)`                       | `Throwable.getClass().getSimpleName()` on failure; NULL on success.                                                        |
| `measured_at`   | `TIMESTAMP WITH TIME ZONE`            | Defaults to `SYSTIMESTAMP`.                                                                                                |
| `run_id`        | `VARCHAR2(36)`                        | UUID that groups the N measurements produced by a single button click. Lets the stats page identify and trim warm-up runs. |

Index on `(query_id, route, measured_at DESC)` to keep the "recent 200 for
this query + route" read cheap.

## Backend API

Package: `dev.victormartin.adbsidecar.back.controller`. Delete the existing
`VersionsController` entirely — `/api/v1/demo` and `/api/v1/demo/via-sidecar`
have no consumers once the frontend is rewritten.

New controllers:

### `QueryController`

`GET /api/v1/query`

Query parameters:

| Name    | Required | Values                                                                     |
| ------- | -------- | -------------------------------------------------------------------------- |
| `table` | yes      | `accounts` \| `transactions` \| `policies` \| `rules` \| `support_tickets` |
| `route` | yes      | `direct` \| `federated`                                                    |
| `runId` | yes      | UUID string the frontend generates per click                               |

Behavior:

- Maps `(table, route)` to one of nine live call sites (the tenth combo,
  `support_tickets` federated, short-circuits before any DB call — see below).
  The existing method bodies in `VersionsController` (lines 53–100) are the
  starting point; they get one-to-one pulled into a `QueryExecutor` that
  returns `{rows, rowsReturned, elapsedMs, success, errorClass}`.
- `(support_tickets, federated)` returns `501 Not Implemented` with body
  `{error: "Not available via sidecar — see docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md"}`
  and does NOT write a measurement row (the frontend does not call this path;
  this check is defense-in-depth for curl users).
- Any other failure returns `200` with `{rows: [], rowsReturned: 0, elapsedMs, error: "<message>"}`
  so the frontend can render a per-card error without losing the timing data.
  Measurement row is written with `success=0`, `error_class=<class>`.

Response shape (success):

```json
{
  "rows": [...],
  "rowsReturned": 3,
  "elapsedMs": 47.321
}
```

### `MeasurementsController`

`GET /api/v1/measurements?trim={none|iqr}`

Returns one aggregate row per `(query_id, route)`:

```json
[
  {
    "queryId": "oracle.accounts",
    "route": "direct",
    "count": 112,
    "mean": 12.4,
    "median": 11.8,
    "p95": 24.1,
    "p99": 38.7,
    "stddev": 4.2,
    "min": 7.3,
    "max": 58.0
  }
]
```

When `trim=iqr`, rows outside `[Q1 - 1.5·IQR, Q3 + 1.5·IQR]` per
`(query_id, route)` are removed before computing the aggregates. Default is
`trim=none`.

Aggregates are computed server-side with a SQL `CTE` + `PERCENTILE_CONT`
(ADB is Oracle — this is one query).

`GET /api/v1/measurements/series?queryId={id}&route={direct|federated}&limit={n}`

Returns the `limit` most recent rows for the pair, ordered by `measured_at`:

```json
[
  {
    "measuredAt": "2026-04-20T10:12:33.001Z",
    "elapsedMs": 12.5,
    "rowsReturned": 3,
    "success": 1,
    "runId": "..."
  }
]
```

Default `limit=200`, cap at 1000.

### Async-insert wiring

- Enable `@EnableAsync` on the Spring application class.
- A dedicated `ThreadPoolTaskExecutor` (small pool, e.g., `corePoolSize=2,
queueCapacity=200`) so measurement inserts can't starve the main request
  thread or pile up unboundedly.
- `MeasurementRecorder.record(measurement)` is `@Async` and wraps the INSERT
  in a try/catch that only logs.

## Frontend

Angular 21, standalone components, signals. One component per route.

### Shared

- **Nav bar** component at the top of the shell. Four links.
- **`queryService.run(table, route, runId): Observable<CardResult>`** returns
  `{rows, rowsReturned, elapsedMs, error}`.
- **`runId`** generated via `crypto.randomUUID()` in the page component on
  button click.

### `/app` and `/sidecar`

- Same layout. A header, a subtitle explaining the path ("backend opens a
  direct connection to each database" vs "backend queries the ADB 26ai
  sidecar; ADB resolves `V_*` views over DB_LINK"), one button, and 5 cards
  (4 live + 1 static placeholder on `/sidecar`).
- Each card is its own component in a `signal<LoadingState<CardResult>>`:
  `idle | loading | success | error`. The page component kicks off all 5 (or 4) request signals when the button is clicked; cards render independently.
- Card header shows a badge with `elapsedMs` (e.g., "47.3 ms") on success.
- `/sidecar`'s `support_tickets` card is always in a static "Not available via
  sidecar — see troubleshooting" state.

### `/future`

```html
<h2>AI features</h2>
<p>Select AI Agents feature goes here.</p>
```

### `/measurements` — performance-engineer view

Three stacked panels. Shared controls at the top: **"Trigger N runs"** button
(N defaults to 20; fires the full 9 valid `(table, route)` combos N times in
the background to seed data — each of the N rounds gets its own `runId`, so a
round produces 9 measurement rows that share one `runId`), and a **raw / trim
outliers (IQR)** toggle that re-fetches both the summary table and the box
plot.

1. **Summary table** (full width)  
   Columns: `query_id`, direct mean / median / p95, federated mean / median /
   p95, `Δ mean (ms)`, `Δ mean (%)`. Sort by `Δ mean (%)` by default so the
   worst-offending queries float to the top. Support-tickets row is always
   federated = N/A.

2. **Box-plot panel** (one chart per query_id, direct and federated side by
   side)  
   `ng2-charts` + `chartjs-chart-boxplot` plugin. Feeds off
   `/api/v1/measurements/series` (not the aggregate endpoint, so we have the
   raw points per route).

3. **Time-series scatter** (one chart, dropdowns for `query_id` and `route`,
   default = `oracle.accounts` / `direct`)  
   X = `measured_at`, Y = `elapsedMs`. Point colored by `run_id` hue so the
   first click of a new `run_id` (= warm-up) is visibly separable from the
   following runs.

### Chart library

`ng2-charts` (Chart.js wrapper, the most popular Angular option) +
`chartjs-chart-boxplot` for box plots. Installed via `npm install
ng2-charts chart.js chartjs-chart-boxplot --save`. Registered once in a
standalone `provideCharts()` provider. No NgModules.

## Copy change — remove "demo" from the UI

Every UI string that says "demo" becomes "banking data" or "banking dataset."
README narrative keeps "banking demo" (it is a demo). Specifically:

- `Load banking demo` → `Load banking data`
- `Load banking demo via ADB sidecar` → `Load banking data via ADB sidecar`
- `Banking demo — federated across three engines` (page subtitle) → `Banking
dataset — federated across three engines`

## README updates

1. **Opening paragraph** — rewrite to lead with the coexistence story:
   keep your app, keep your existing databases and their lifecycle, attach
   ADB 26ai as a sidecar, build AI features on top of the same data,
   consolidate datasources on your own schedule.
2. **Architecture diagram** — replace the single-frontend flowchart with one
   that shows the four browser routes (`/app`, `/sidecar`, `/future`,
   `/measurements`) → backend → (container DBs | ADB sidecar → DB_LINKs).
3. **New section: "Three paths, one dataset"** — one paragraph per route
   explaining purpose and data flow.
4. **New section: "Measuring the federated tax"** — explains why we measure
   (customer objection), how (wall-clock at backend boundary, async insert,
   IQR outlier trim for the comparison view), where (ADB
   `QUERY_MEASUREMENTS`), and how to read the `/measurements` page.
5. **Verifying section** — add the new endpoints:
   `GET /api/v1/query?table=accounts&route=direct&runId=$(uuidgen)` and
   `GET /api/v1/measurements`.
6. **Remove references** to `/api/v1/demo` and `/api/v1/demo/via-sidecar`.

## What can stay as-is

- Liquibase master changelog — just add `003-measurements.yaml` to the list.
- All infrastructure (Terraform, Ansible, cloud-init, PARs). No new
  computes, no new OCI services, no wallet changes.
- The existing `DataSourceConfig.java` — the new `QueryController` uses the
  same four beans (`adbJdbc`, `oracleJdbc`, `postgresJdbc`, `mongo`).
- The V\_\* views in ADB — no schema change; the new endpoint queries exactly
  what `VersionsController` queries today.

## Success criteria

- [ ] `/app`, `/sidecar`, `/future`, `/measurements` all render with shared
      top nav and correct content per the spec.
- [ ] One click on `/app` fires 5 parallel HTTP calls and independently fills
      each card as its response returns; per-card badge shows elapsed ms.
- [ ] One click on `/sidecar` fires 4 parallel calls; the `support_tickets`
      card is permanently "Not available via sidecar" and no request is made
      for it.
- [ ] `QUERY_MEASUREMENTS` gets one row per completed call (async-inserted,
      never in the timed region). Failed calls still produce a row with
      `success=0`.
- [ ] `/measurements` summary table, box plots, and scatter render against a
      fresh deploy after clicking **Trigger N runs** once.
- [ ] README lead and new sections communicate the stepping-stone /
      coexistence story with the new route set.
- [ ] No UI string contains the word "demo".
- [ ] `/api/v1/demo` and `/api/v1/demo/via-sidecar` are gone.
