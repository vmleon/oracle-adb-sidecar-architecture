package dev.victormartin.aidatagateway.back.readiness;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReadinessService {

    private static final long PROBE_TIMEOUT_SECONDS = 3;

    private final JdbcTemplate adbJdbc;
    private final JdbcTemplate oracleJdbc;
    private final JdbcTemplate postgresJdbc;
    private final MongoTemplate mongo;
    private final String teamName;

    private static final Logger log = LoggerFactory.getLogger(ReadinessService.class);

    private final Set<String> everReady = ConcurrentHashMap.newKeySet();
    private final Map<String, String> lastState = new ConcurrentHashMap<>();

    public ReadinessService(
            @Qualifier("adbJdbc") JdbcTemplate adbJdbc,
            @Qualifier("oracleJdbc") JdbcTemplate oracleJdbc,
            @Qualifier("postgresJdbc") JdbcTemplate postgresJdbc,
            MongoTemplate mongo,
            @Value("${selectai.agents.team}") String teamName) {
        this.adbJdbc = adbJdbc;
        this.oracleJdbc = oracleJdbc;
        this.postgresJdbc = postgresJdbc;
        this.mongo = mongo;
        this.teamName = teamName;
    }

    public ReadinessSnapshot snapshot() {
        // Kick off every probe up front so they run in parallel; collect after.
        // Sequential probes would stack timeouts (worst case 6 × PROBE_TIMEOUT
        // on a network blip); in parallel the snapshot is bound by the slowest.
        Map<String, CompletableFuture<Boolean>> futures = new LinkedHashMap<>();
        futures.put("adb",        runProbe(() -> { adbJdbc.queryForObject("SELECT 1 FROM DUAL", Integer.class); return true; }));
        futures.put("oracleFree", runProbe(() -> { oracleJdbc.queryForObject("SELECT 1 FROM DUAL", Integer.class); return true; }));
        futures.put("postgres",   runProbe(() -> { postgresJdbc.queryForObject("SELECT 1", Integer.class); return true; }));
        futures.put("mongo",      runProbe(() -> { mongo.getDb().runCommand(new Document("ping", 1)); return true; }));
        futures.put("agentsTeam", runProbe(() -> {
            Integer n = adbJdbc.queryForObject(
                    "SELECT COUNT(*) FROM USER_AI_AGENT_TEAMS WHERE AGENT_TEAM_NAME = ? AND STATUS = 'ENABLED'",
                    Integer.class, teamName);
            return n != null && n > 0;
        }));
        // Rich banking schema check — /api/v1/risk needs customers (Oracle 003)
        // and rules.code (Postgres 003-compliance-rich) to be present.
        futures.put("riskDashboard", runProbe(() -> {
            oracleJdbc.queryForObject("SELECT COUNT(*) FROM customers", Integer.class);
            postgresJdbc.queryForObject("SELECT COUNT(*) FROM rules WHERE code IS NOT NULL", Integer.class);
            return true;
        }));
        // /api/v1/fraud/patterns needs the banking_graph backing tables on ADB
        // (Liquibase adb-006) and transactions.merchant_country on Oracle for
        // the cross-border wire panel.
        futures.put("fraudDashboard", runProbe(() -> {
            adbJdbc.queryForObject("SELECT COUNT(*) FROM transaction_edges", Integer.class);
            oracleJdbc.queryForObject(
                    "SELECT COUNT(*) FROM transactions WHERE merchant_country IS NOT NULL",
                    Integer.class);
            return true;
        }));

        Map<String, String> components = new LinkedHashMap<>();
        for (Map.Entry<String, CompletableFuture<Boolean>> e : futures.entrySet()) {
            components.put(e.getKey(), collect(e.getKey(), e.getValue()));
        }
        return new ReadinessSnapshot(overall(components), components);
    }

    private CompletableFuture<Boolean> runProbe(BooleanSupplier check) {
        return CompletableFuture.supplyAsync(check::getAsBoolean);
    }

    private String collect(String name, CompletableFuture<Boolean> future) {
        boolean ok;
        String cause = null;
        try {
            ok = future.get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            ok = false;
            Throwable root = e.getCause() == null ? e : e.getCause();
            cause = root.getMessage();
        }
        String state = ok ? "ready" : (everReady.contains(name) ? "error" : "bootstrapping");
        if (ok) everReady.add(name);
        // The browser polls readiness every 5 s, so only transitions are worth
        // a line — they mark exactly when a tier came up or fell over.
        String previous = lastState.put(name, state);
        if (!state.equals(previous)) {
            if ("ready".equals(state)) {
                log.info("event=readiness component={} state={} from={}", name, state, previous);
            } else {
                log.warn("event=readiness component={} state={} from={} cause=\"{}\"",
                        name, state, previous, abbrev(cause, 200));
            }
        }
        return state;
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }

    private String overall(Map<String, String> components) {
        if (components.containsValue("error")) return "error";
        if (components.containsValue("bootstrapping")) return "bootstrapping";
        return "ready";
    }
}
