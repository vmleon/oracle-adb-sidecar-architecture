package dev.victormartin.adbsidecar.back.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class QueryExecutor {

    private final JdbcTemplate adbJdbc;
    private final JdbcTemplate oracleJdbc;
    private final JdbcTemplate postgresJdbc;
    private final MongoTemplate mongo;

    public QueryExecutor(
            @Qualifier("adbJdbc") JdbcTemplate adbJdbc,
            @Qualifier("oracleJdbc") JdbcTemplate oracleJdbc,
            @Qualifier("postgresJdbc") JdbcTemplate postgresJdbc,
            MongoTemplate mongo) {
        this.adbJdbc = adbJdbc;
        this.oracleJdbc = oracleJdbc;
        this.postgresJdbc = postgresJdbc;
        this.mongo = mongo;
    }

    public static String queryIdFor(String table) {
        return switch (table) {
            case "accounts", "transactions" -> "oracle." + table;
            case "policies", "rules"        -> "postgres." + table;
            case "support_tickets"          -> "mongo.support_tickets";
            default -> throw new IllegalArgumentException("unknown table: " + table);
        };
    }

    public QueryResult run(String table, String route) {
        long t0 = System.nanoTime();
        try {
            List<Map<String, Object>> rows = call(table, route);
            double elapsed = (System.nanoTime() - t0) / 1_000_000.0;
            return QueryResult.success(rows, elapsed);
        } catch (Exception e) {
            double elapsed = (System.nanoTime() - t0) / 1_000_000.0;
            return QueryResult.failure(e, elapsed);
        }
    }

    private List<Map<String, Object>> call(String table, String route) {
        boolean federated = "federated".equals(route);
        return switch (table) {
            case "accounts" -> federated
                    ? adbJdbc.queryForList(
                            "SELECT id, customer_name, balance FROM V_ACCOUNTS ORDER BY id")
                    : oracleJdbc.queryForList(
                            "SELECT id, customer_name, balance FROM accounts ORDER BY id");
            case "transactions" -> federated
                    ? adbJdbc.queryForList(
                            "SELECT id, account_id, amount, tx_date FROM V_TRANSACTIONS ORDER BY id")
                    : oracleJdbc.queryForList(
                            "SELECT id, account_id, amount, tx_date FROM transactions ORDER BY id");
            case "policies" -> federated
                    ? adbJdbc.queryForList(
                            "SELECT id, name, description FROM V_POLICIES ORDER BY id")
                    : postgresJdbc.queryForList(
                            "SELECT id, name, description FROM policies ORDER BY id");
            case "rules" -> federated
                    ? adbJdbc.queryForList(
                            "SELECT id, policy_id, expression FROM V_RULES ORDER BY id")
                    : postgresJdbc.queryForList(
                            "SELECT id, policy_id, expression FROM rules ORDER BY id");
            case "support_tickets" -> {
                if (federated) {
                    throw new UnsupportedOperationException(
                            "support_tickets federated is not supported; see docs");
                }
                List<Document> docs = mongo.findAll(Document.class, "support_tickets");
                docs.forEach(d -> d.remove("_id"));
                yield new ArrayList<>(docs);
            }
            default -> throw new IllegalArgumentException("unknown table: " + table);
        };
    }
}
