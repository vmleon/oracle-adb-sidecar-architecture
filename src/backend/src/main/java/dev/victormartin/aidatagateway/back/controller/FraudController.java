package dev.victormartin.aidatagateway.back.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.victormartin.aidatagateway.back.util.OfacCountries;

@RestController
@RequestMapping("/api/v1/fraud")
public class FraudController {

    // Cycles A -> B -> C -> A. The `a.id < b.id AND a.id < c.id` predicate
    // canonicalises cycle direction so each underlying triangle emits once
    // instead of three times (the graph match returns every starting vertex).
    // The window filter is applied on every edge — outer WHERE rather than
    // inside GRAPH_TABLE so the bind variables sit in plain SQL — so the
    // whole triangle has to fall inside the requested date range; partial
    // cycles spanning the boundary would be misleading.
    private static final String CYCLES_SQL = """
            SELECT "aId", "bId", "cId", "aName", "bName", "cName", "amount"
            FROM (
              SELECT a_id   AS "aId",   b_id   AS "bId",   c_id   AS "cId",
                     a_name AS "aName", b_name AS "bName", c_name AS "cName",
                     amount AS "amount",
                     t1_at, t2_at, t3_at
              FROM GRAPH_TABLE (banking_graph
                MATCH (a) -[t1]-> (b) -[t2]-> (c) -[t3]-> (a)
                WHERE a.id < b.id AND a.id < c.id
                  AND t1.amount > 1000 AND t2.amount > 1000 AND t3.amount > 1000
                COLUMNS (
                  a.id            AS a_id,
                  b.id            AS b_id,
                  c.id            AS c_id,
                  a.customer_name AS a_name,
                  b.customer_name AS b_name,
                  c.customer_name AS c_name,
                  t1.amount       AS amount,
                  t1.occurred_at  AS t1_at,
                  t2.occurred_at  AS t2_at,
                  t3.occurred_at  AS t3_at
                )
              )
            )
            WHERE t1_at BETWEEN ? AND ?
              AND t2_at BETWEEN ? AND ?
              AND t3_at BETWEEN ? AND ?
            """;

    // Fan-out: a single source with ≥5 distinct destinations inside any
    // rolling 30-minute window. Real fraud detection uses sliding windows;
    // a lifetime distinct-counterparty count would flag normal payroll
    // accounts (50 employees over a year) as fraud.
    //
    // Implemented via a self-join (anchor edge t1; counterparties in
    // (t1 - 30min, t1]) rather than COUNT(DISTINCT) OVER (...) because
    // Oracle rejects DISTINCT analytic aggregates with a windowing clause
    // and reports it as ORA-30487 "ORDER BY not allowed here".
    private static final String FANOUT_SQL = """
            WITH edges AS (
              SELECT src_id, src_name, dst_id, occurred_at
              FROM GRAPH_TABLE (banking_graph
                MATCH (a) -[t]-> (b)
                COLUMNS (
                  a.id            AS src_id,
                  a.customer_name AS src_name,
                  b.id            AS dst_id,
                  t.occurred_at   AS occurred_at
                )
              )
              WHERE occurred_at BETWEEN ? AND ?
            ),
            windows AS (
              SELECT e1.src_id, e1.src_name,
                     COUNT(DISTINCT e2.dst_id) AS rolling_distinct
              FROM edges e1
              JOIN edges e2
                ON  e2.src_id = e1.src_id
                AND e2.occurred_at >  e1.occurred_at - INTERVAL '30' MINUTE
                AND e2.occurred_at <= e1.occurred_at
              GROUP BY e1.src_id, e1.src_name, e1.occurred_at
            )
            SELECT src_id        AS "srcId",
                   MIN(src_name) AS "srcName",
                   MAX(rolling_distinct) AS "fanout"
            FROM windows
            GROUP BY src_id
            HAVING MAX(rolling_distinct) >= 5
            ORDER BY MAX(rolling_distinct) DESC
            """;

    // Structuring: ≥3 sub-$10K transfers from one source totalling ≥$25K
    // inside any rolling 7-day window. Filter on the rolling values BEFORE
    // aggregating per source so both thresholds are met simultaneously in
    // at least one window — otherwise MAX(count) and MAX(sum) could come
    // from different windows and falsely trigger.
    private static final String STRUCTURING_SQL = """
            SELECT src_id   AS "srcId",
                   src_name AS "srcName",
                   MAX(rolling_count) AS "transferCount",
                   MAX(rolling_sum)   AS "totalAmount"
            FROM (
              SELECT src_id, src_name,
                     COUNT(*) OVER (
                       PARTITION BY src_id
                       ORDER BY occurred_at
                       RANGE BETWEEN INTERVAL '7' DAY PRECEDING AND CURRENT ROW
                     ) AS rolling_count,
                     SUM(amt) OVER (
                       PARTITION BY src_id
                       ORDER BY occurred_at
                       RANGE BETWEEN INTERVAL '7' DAY PRECEDING AND CURRENT ROW
                     ) AS rolling_sum
              FROM (
                SELECT src_id, src_name, occurred_at, amt
                FROM GRAPH_TABLE (banking_graph
                  MATCH (a) -[t]-> (b)
                  WHERE t.amount BETWEEN 8000 AND 9999
                  COLUMNS (
                    a.id            AS src_id,
                    a.customer_name AS src_name,
                    t.occurred_at   AS occurred_at,
                    t.amount        AS amt
                  )
                )
                WHERE occurred_at BETWEEN ? AND ?
              )
            )
            WHERE rolling_count >= 3 AND rolling_sum >= 25000
            GROUP BY src_id, src_name
            ORDER BY MAX(rolling_sum) DESC
            """;

    // Cross-border wires: outbound WIRE traffic by destination country, with
    // OFAC-sanctioned jurisdictions flagged. Runs against the production
    // `transactions` table directly (the blockchain ledger) — not the graph.
    private static final String CROSS_BORDER_WIRES_SQL = """
            SELECT t.merchant_country AS "country",
                   COUNT(*)           AS "txnCount",
                   SUM(ABS(t.amount)) AS "totalAmount"
            FROM transactions t
            WHERE t.txn_type = 'WIRE'
              AND t.merchant_country IS NOT NULL
              AND t.merchant_country <> 'US'
              AND t.occurred_at BETWEEN ? AND ?
            GROUP BY t.merchant_country
            ORDER BY SUM(ABS(t.amount)) DESC
            """;

    // The demo datasets carry fixed seed timestamps, so a wall-clock
    // "last 30 days" default drifts past the data and renders every panel
    // empty. When the caller doesn't pick a window, anchor it to the
    // newest transaction across both sources instead of today.
    private static final String MAX_EDGE_TS_SQL =
            "SELECT MAX(occurred_at) FROM transaction_edges";
    private static final String MAX_TXN_TS_SQL =
            "SELECT MAX(occurred_at) FROM transactions";

    private final JdbcTemplate adbJdbc;
    private final JdbcTemplate oracleJdbc;

    public FraudController(
            @Qualifier("adbJdbc") JdbcTemplate adbJdbc,
            @Qualifier("oracleJdbc") JdbcTemplate oracleJdbc) {
        this.adbJdbc = adbJdbc;
        this.oracleJdbc = oracleJdbc;
    }

    @GetMapping("/patterns")
    public Map<String, Object> patterns(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        LocalDate toDate = to != null && !to.isBlank() ? LocalDate.parse(to) : newestDataDate();
        LocalDate fromDate = from != null && !from.isBlank() ? LocalDate.parse(from) : toDate.minusDays(30);
        LocalDateTime fromTs = fromDate.atStartOfDay();
        LocalDateTime toTs = toDate.atTime(LocalTime.MAX);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", fromDate.toString());
        body.put("to", toDate.toString());
        body.put("loadedAt", OffsetDateTime.now().toString());
        body.put("cycles", scoreCycles(adbJdbc.queryForList(CYCLES_SQL,
                fromTs, toTs, fromTs, toTs, fromTs, toTs)));
        body.put("fanout", scoreFanout(adbJdbc.queryForList(FANOUT_SQL, fromTs, toTs)));
        body.put("structuring", scoreStructuring(adbJdbc.queryForList(STRUCTURING_SQL, fromTs, toTs)));
        body.put("crossBorderWires", crossBorderWires(fromTs, toTs));
        return body;
    }

    private LocalDate newestDataDate() {
        LocalDateTime edges = maxTimestamp(adbJdbc, MAX_EDGE_TS_SQL);
        LocalDateTime txns = maxTimestamp(oracleJdbc, MAX_TXN_TS_SQL);
        LocalDateTime newest = edges == null ? txns
                : txns == null ? edges
                : edges.isAfter(txns) ? edges : txns;
        return newest == null ? LocalDate.now() : newest.toLocalDate();
    }

    private static LocalDateTime maxTimestamp(JdbcTemplate jdbc, String sql) {
        try {
            return jdbc.queryForObject(sql, LocalDateTime.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<Map<String, Object>> crossBorderWires(LocalDateTime fromTs, LocalDateTime toTs) {
        List<Map<String, Object>> rows = oracleJdbc.queryForList(CROSS_BORDER_WIRES_SQL, fromTs, toTs);
        for (Map<String, Object> row : rows) {
            row.put("sanctioned", OfacCountries.SET.contains(row.get("country")));
        }
        return rows;
    }

    private static List<Map<String, Object>> scoreCycles(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            row.put("riskScore", 80);
        }
        return rows;
    }

    private static List<Map<String, Object>> scoreFanout(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            long fanout = ((Number) row.get("fanout")).longValue();
            row.put("riskScore", Math.min(95L, 50 + (fanout - 5) * 5));
        }
        return rows;
    }

    private static List<Map<String, Object>> scoreStructuring(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            long count = ((Number) row.get("transferCount")).longValue();
            row.put("riskScore", Math.min(95L, 60 + (count - 3) * 5));
        }
        return rows;
    }
}
