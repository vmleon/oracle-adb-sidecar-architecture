package dev.victormartin.adbsidecar.back.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/risk")
public class RiskController {

    private static final String KPI_KYC_ATTENTION =
            "SELECT COUNT(*) FROM customers WHERE kyc_status IN ('PENDING','EXPIRED')";

    private static final String KPI_FROZEN_ACCOUNTS =
            "SELECT COUNT(*) FROM accounts WHERE status = 'FROZEN'";

    private static final String KPI_HIGH_RISK =
            "SELECT COUNT(*) FROM customers WHERE risk_tier = 'HIGH'";

    private static final String KPI_SUB_CTR =
            "SELECT COUNT(*) FROM transactions WHERE ABS(amount) BETWEEN 9000 AND 9999";

    // Accounts with three or more DECLINED card authorisations inside any rolling
    // one-hour window. Same logic powers rule R-FRAUD-007 (velocity-triggered freeze).
    private static final String KPI_DECLINE_VELOCITY = """
            SELECT COUNT(DISTINCT account_id) FROM (
              SELECT account_id,
                     COUNT(*) OVER (
                       PARTITION BY account_id
                       ORDER BY occurred_at
                       RANGE BETWEEN INTERVAL '1' HOUR PRECEDING AND CURRENT ROW
                     ) AS rolling
              FROM transactions
              WHERE status = 'DECLINED'
            ) WHERE rolling >= 3
            """;

    private static final String CHART_SUB_CTR_WATCHLIST = """
            SELECT c.name AS "customer",
                   SUM(CASE WHEN t.txn_type = 'WIRE' THEN 1 ELSE 0 END) AS "wireCount",
                   SUM(CASE WHEN t.txn_type = 'ATM'  THEN 1 ELSE 0 END) AS "cashCount",
                   SUM(CASE WHEN t.txn_type NOT IN ('WIRE','ATM') THEN 1 ELSE 0 END) AS "otherCount",
                   COUNT(*) AS "total"
            FROM transactions t
            JOIN accounts  a ON a.id = t.account_id
            JOIN customers c ON c.id = a.customer_id
            WHERE ABS(t.amount) BETWEEN 9000 AND 9999
            GROUP BY c.name
            ORDER BY COUNT(*) DESC, c.name
            """;

    private static final String CHART_CROSS_BORDER_WIRES = """
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

    private static final String CHART_KYC_BUCKETS =
            "SELECT kyc_status AS \"status\", COUNT(*) AS \"count\" "
            + "FROM customers GROUP BY kyc_status ORDER BY kyc_status";

    private static final String CHART_KYC_DRILLDOWN = """
            SELECT id           AS "id",
                   name         AS "name",
                   country_code AS "country",
                   kyc_status   AS "kycStatus",
                   risk_tier    AS "riskTier",
                   TO_CHAR(joined_at, 'YYYY-MM-DD') AS "joinedAt"
            FROM customers
            WHERE kyc_status <> 'VERIFIED'
            ORDER BY kyc_status, joined_at
            """;

    private static final String CHART_RISK_BY_STATUS = """
            SELECT c.risk_tier AS "riskTier",
                   a.status    AS "accountStatus",
                   COUNT(*)    AS "count"
            FROM customers c
            JOIN accounts  a ON a.customer_id = c.id
            GROUP BY c.risk_tier, a.status
            ORDER BY c.risk_tier, a.status
            """;

    private static final String RULES_REFERENCE = """
            SELECT code        AS "code",
                   name        AS "name",
                   severity    AS "severity",
                   description AS "description",
                   policy_code AS "policyCode"
            FROM rules
            ORDER BY CASE severity
                       WHEN 'VIOLATION' THEN 1
                       WHEN 'WARNING'   THEN 2
                       WHEN 'INFO'      THEN 3
                       ELSE 4
                     END, code
            """;

    // Per-rule violation counts. Only rules whose evaluation maps cleanly to seed
    // data are computed; the rest stay null and the UI renders a dash.
    private static final Map<String, String> RULE_QUERIES = Map.ofEntries(
            Map.entry("R-AML-005", """
                    SELECT COUNT(*) FROM (
                      SELECT account_id FROM transactions
                      WHERE ABS(amount) BETWEEN 9000 AND 9999
                      GROUP BY account_id HAVING COUNT(*) >= 3
                    )
                    """),
            Map.entry("R-AML-002", """
                    SELECT COUNT(*) FROM (
                      SELECT account_id FROM transactions
                      WHERE channel = 'BRANCH' AND amount > 0
                        AND occurred_at >= SYSTIMESTAMP - INTERVAL '30' DAY
                      GROUP BY account_id HAVING SUM(amount) > 40000
                    )
                    """),
            Map.entry("R-FRAUD-007", """
                    SELECT COUNT(DISTINCT account_id) FROM (
                      SELECT account_id,
                             COUNT(*) OVER (
                               PARTITION BY account_id
                               ORDER BY occurred_at
                               RANGE BETWEEN INTERVAL '1' HOUR PRECEDING AND CURRENT ROW
                             ) AS rolling
                      FROM transactions WHERE status = 'DECLINED'
                    ) WHERE rolling >= 3
                    """),
            Map.entry("R-OFAC-001",
                    "SELECT COUNT(*) FROM transactions "
                    + "WHERE merchant_country IN ('BY','IR','KP','RU','SY','VE','MM','CU')"),
            Map.entry("R-CTR-001",
                    "SELECT COUNT(*) FROM transactions "
                    + "WHERE channel = 'BRANCH' AND amount > 10000"),
            Map.entry("R-WIRE-001",
                    "SELECT COUNT(*) FROM transactions "
                    + "WHERE txn_type = 'WIRE' AND ABS(amount) > 10000 "
                    + "AND merchant_country IS NOT NULL AND merchant_country <> 'US'"),
            Map.entry("R-FRAUD-001",
                    "SELECT COUNT(*) FROM transactions "
                    + "WHERE channel = 'ONLINE' AND ABS(amount) > 5000"),
            Map.entry("R-KYC-001",
                    "SELECT COUNT(*) FROM customers WHERE kyc_status = 'EXPIRED'"),
            Map.entry("R-KYC-002",
                    "SELECT COUNT(*) FROM customers "
                    + "WHERE risk_tier = 'HIGH' AND joined_at < ADD_MONTHS(SYSDATE, -36)")
    );

    private final JdbcTemplate oracleJdbc;
    private final JdbcTemplate postgresJdbc;
    private final MongoTemplate mongo;

    public RiskController(
            @Qualifier("oracleJdbc") JdbcTemplate oracleJdbc,
            @Qualifier("postgresJdbc") JdbcTemplate postgresJdbc,
            MongoTemplate mongo) {
        this.oracleJdbc = oracleJdbc;
        this.postgresJdbc = postgresJdbc;
        this.mongo = mongo;
    }

    @GetMapping
    public Map<String, Object> dashboard() {
        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("kycAttention", oracleJdbc.queryForObject(KPI_KYC_ATTENTION, Long.class));
        kpis.put("frozenAccounts", oracleJdbc.queryForObject(KPI_FROZEN_ACCOUNTS, Long.class));
        kpis.put("highRiskCustomers", oracleJdbc.queryForObject(KPI_HIGH_RISK, Long.class));
        kpis.put("subCtrActivity", oracleJdbc.queryForObject(KPI_SUB_CTR, Long.class));
        kpis.put("declineVelocity", oracleJdbc.queryForObject(KPI_DECLINE_VELOCITY, Long.class));
        kpis.put("openHighPriorityTickets", openHighPriorityTickets());

        Map<String, Object> kycPipeline = new LinkedHashMap<>();
        kycPipeline.put("counts", oracleJdbc.queryForList(CHART_KYC_BUCKETS));
        kycPipeline.put("nonVerified", oracleJdbc.queryForList(CHART_KYC_DRILLDOWN));

        List<Map<String, Object>> rules = postgresJdbc.queryForList(RULES_REFERENCE);
        Map<String, Long> counts = ruleViolationCounts();
        for (Map<String, Object> rule : rules) {
            rule.put("violationCount", counts.get((String) rule.get("code")));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kpis", kpis);
        body.put("subCtrWatchlist", oracleJdbc.queryForList(CHART_SUB_CTR_WATCHLIST));
        body.put("crossBorderWires", oracleJdbc.queryForList(CHART_CROSS_BORDER_WIRES));
        body.put("kycPipeline", kycPipeline);
        body.put("riskByStatus", oracleJdbc.queryForList(CHART_RISK_BY_STATUS));
        body.put("ticketsByPriority", ticketsByPriority());
        body.put("rules", rules);
        return body;
    }

    private long openHighPriorityTickets() {
        Query q = Query.query(
                Criteria.where("status").is("OPEN").and("priority").is("HIGH"));
        return mongo.count(q, "support_tickets");
    }

    private List<Map<String, Object>> ticketsByPriority() {
        List<Document> tickets = mongo.findAll(Document.class, "support_tickets");
        Map<String, Map<String, Long>> bucket = new HashMap<>();
        for (Document d : tickets) {
            Object createdAt = d.get("created_at");
            String priority = d.getString("priority");
            if (!(createdAt instanceof java.util.Date) || priority == null) {
                continue;
            }
            String day = ((java.util.Date) createdAt).toInstant().toString().substring(0, 10);
            bucket
                    .computeIfAbsent(day, k -> new HashMap<>())
                    .merge(priority, 1L, Long::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        bucket.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    for (Map.Entry<String, Long> p : e.getValue().entrySet()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("date", e.getKey());
                        row.put("priority", p.getKey());
                        row.put("count", p.getValue());
                        out.add(row);
                    }
                });
        return out;
    }

    private Map<String, Long> ruleViolationCounts() {
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, String> e : RULE_QUERIES.entrySet()) {
            try {
                Long n = oracleJdbc.queryForObject(e.getValue(), Long.class);
                out.put(e.getKey(), n == null ? 0L : n);
            } catch (Exception ex) {
                out.put(e.getKey(), null);
            }
        }
        return out;
    }
}
