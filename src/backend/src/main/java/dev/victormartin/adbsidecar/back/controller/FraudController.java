package dev.victormartin.adbsidecar.back.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.victormartin.adbsidecar.back.util.OfacCountries;

@RestController
@RequestMapping("/api/v1/fraud")
public class FraudController {

    // Cycles A -> B -> C -> A. The `a.id < b.id AND a.id < c.id` predicate
    // canonicalises cycle direction so each underlying triangle emits once
    // instead of three times (the graph match returns every starting vertex).
    private static final String CYCLES_SQL = """
            SELECT a_id   AS "aId",   b_id   AS "bId",   c_id   AS "cId",
                   a_name AS "aName", b_name AS "bName", c_name AS "cName",
                   amount AS "amount"
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
                t1.amount       AS amount
              )
            )
            """;

    // Fan-out: a single source with many distinct destinations.
    private static final String FANOUT_SQL = """
            SELECT src_id   AS "srcId",
                   src_name AS "srcName",
                   COUNT(DISTINCT dst_id) AS "fanout"
            FROM GRAPH_TABLE (banking_graph
              MATCH (a) -[t]-> (b)
              COLUMNS (
                a.id            AS src_id,
                a.customer_name AS src_name,
                b.id            AS dst_id
              )
            )
            GROUP BY src_id, src_name
            HAVING COUNT(DISTINCT dst_id) >= 5
            ORDER BY COUNT(DISTINCT dst_id) DESC
            """;

    // Structuring: many sub-CTR transfers from one source.
    private static final String STRUCTURING_SQL = """
            SELECT src_id   AS "srcId",
                   src_name AS "srcName",
                   COUNT(*) AS "transferCount",
                   SUM(amt) AS "totalAmount"
            FROM GRAPH_TABLE (banking_graph
              MATCH (a) -[t]-> (b)
              WHERE t.amount BETWEEN 8000 AND 9999
              COLUMNS (
                a.id            AS src_id,
                a.customer_name AS src_name,
                t.amount        AS amt
              )
            )
            GROUP BY src_id, src_name
            HAVING COUNT(*) >= 3 AND SUM(amt) >= 25000
            ORDER BY SUM(amt) DESC
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
            GROUP BY t.merchant_country
            ORDER BY SUM(ABS(t.amount)) DESC
            """;

    private final JdbcTemplate adbJdbc;
    private final JdbcTemplate oracleJdbc;

    public FraudController(
            @Qualifier("adbJdbc") JdbcTemplate adbJdbc,
            @Qualifier("oracleJdbc") JdbcTemplate oracleJdbc) {
        this.adbJdbc = adbJdbc;
        this.oracleJdbc = oracleJdbc;
    }

    @GetMapping("/patterns")
    public Map<String, Object> patterns() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cycles", scoreCycles(adbJdbc.queryForList(CYCLES_SQL)));
        body.put("fanout", scoreFanout(adbJdbc.queryForList(FANOUT_SQL)));
        body.put("structuring", scoreStructuring(adbJdbc.queryForList(STRUCTURING_SQL)));
        body.put("crossBorderWires", crossBorderWires());
        return body;
    }

    private List<Map<String, Object>> crossBorderWires() {
        List<Map<String, Object>> rows = oracleJdbc.queryForList(CROSS_BORDER_WIRES_SQL);
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
