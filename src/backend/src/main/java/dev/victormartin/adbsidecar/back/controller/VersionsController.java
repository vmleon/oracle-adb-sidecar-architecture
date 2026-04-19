package dev.victormartin.adbsidecar.back.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class VersionsController {

    private final JdbcTemplate adbJdbc;
    private final JdbcTemplate oracleJdbc;
    private final JdbcTemplate postgresJdbc;
    private final MongoTemplate mongo;

    public VersionsController(
            @Qualifier("adbJdbc") JdbcTemplate adbJdbc,
            @Qualifier("oracleJdbc") JdbcTemplate oracleJdbc,
            @Qualifier("postgresJdbc") JdbcTemplate postgresJdbc,
            MongoTemplate mongo) {
        this.adbJdbc = adbJdbc;
        this.oracleJdbc = oracleJdbc;
        this.postgresJdbc = postgresJdbc;
        this.mongo = mongo;
    }

    @GetMapping("/demo")
    public Map<String, Object> demo() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("oracle", oracleSectionDirect());
        result.put("postgres", postgresSectionDirect());
        result.put("mongo", mongoSectionDirect());
        return result;
    }

    @GetMapping("/demo/via-sidecar")
    public Map<String, Object> demoViaSidecar() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("oracle", oracleSectionViaSidecar());
        result.put("postgres", postgresSectionViaSidecar());
        result.put("mongo", mongoSectionViaSidecar());
        return result;
    }

    private Map<String, Object> oracleSectionDirect() {
        return section(
                () -> oracleJdbc.queryForList("SELECT id, customer_name, balance FROM accounts ORDER BY id"),
                () -> oracleJdbc.queryForList("SELECT id, account_id, amount, tx_date FROM transactions ORDER BY id"),
                "accounts", "transactions");
    }

    private Map<String, Object> oracleSectionViaSidecar() {
        return section(
                () -> adbJdbc.queryForList("SELECT id, customer_name, balance FROM V_ACCOUNTS ORDER BY id"),
                () -> adbJdbc.queryForList("SELECT id, account_id, amount, tx_date FROM V_TRANSACTIONS ORDER BY id"),
                "accounts", "transactions");
    }

    private Map<String, Object> postgresSectionDirect() {
        return section(
                () -> postgresJdbc.queryForList("SELECT id, name, description FROM policies ORDER BY id"),
                () -> postgresJdbc.queryForList("SELECT id, policy_id, expression FROM rules ORDER BY id"),
                "policies", "rules");
    }

    private Map<String, Object> postgresSectionViaSidecar() {
        return section(
                () -> adbJdbc.queryForList("SELECT id, name, description FROM V_POLICIES ORDER BY id"),
                () -> adbJdbc.queryForList("SELECT id, policy_id, expression FROM V_RULES ORDER BY id"),
                "policies", "rules");
    }

    private Map<String, Object> mongoSectionDirect() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            List<Document> docs = mongo.findAll(Document.class, "support_tickets");
            docs.forEach(d -> d.remove("_id"));
            out.put("support_tickets", docs);
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    private Map<String, Object> mongoSectionViaSidecar() {
        // V_SUPPORT_TICKETS is intentionally not created in ADB — every SELECT
        // through MONGO_LINK fails inside ADB's managed heterogeneous gateway
        // (DataDirect MongoDB ODBC driver) regardless of collection placement,
        // service_name, or MongoDB version. See
        // docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md.
        return Map.of("error",
                "ADB heterogeneous MongoDB gateway returns "
                + "\"object not found\" for every collection via @MONGO_LINK. "
                + "Bug logged — see docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md. "
                + "The Direct path (backend → MongoDB) works; only the sidecar view is blocked.");
    }

    private Map<String, Object> section(
            SqlSupplier<List<Map<String, Object>>> first,
            SqlSupplier<List<Map<String, Object>>> second,
            String firstKey,
            String secondKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            out.put(firstKey, first.get());
        } catch (Exception e) {
            out.put(firstKey + "_error", e.getMessage());
        }
        try {
            out.put(secondKey, second.get());
        } catch (Exception e) {
            out.put(secondKey + "_error", e.getMessage());
        }
        return out;
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws Exception;
    }
}
