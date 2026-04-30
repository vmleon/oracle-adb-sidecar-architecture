package dev.victormartin.adbsidecar.back.readiness;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.bson.Document;
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

    private final Set<String> everReady = ConcurrentHashMap.newKeySet();

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
            if (n == null || n <= 0) return false;
            // Warm both heterogeneous-gateway sessions on every poll.
            // Observed: an idle PG_LINK drops, and the next RUN_TEAM call
            // fails on TASK_0 (Transaction Analyst) with
            //   ORA-01010 / ORA-02063 from PG_LINK
            // even though that agent only reads Oracle Free views — the
            // task framework enumerates metadata across every DB_LINK
            // during warm-up. Touching one Oracle-backed and one
            // Postgres-backed V_BNK_* view here keeps both gateways alive
            // and turns the dot red the moment a link genuinely dies.
            adbJdbc.queryForObject("SELECT COUNT(*) FROM v_bnk_customers", Integer.class);
            adbJdbc.queryForObject("SELECT COUNT(*) FROM v_bnk_policies",  Integer.class);
            return true;
        }));
        // Rich banking schema check — /api/v1/risk needs customers (Oracle 003)
        // and rules.code (Postgres 003-compliance-rich) to be present.
        futures.put("riskDashboard", runProbe(() -> {
            oracleJdbc.queryForObject("SELECT COUNT(*) FROM customers", Integer.class);
            postgresJdbc.queryForObject("SELECT COUNT(*) FROM rules WHERE code IS NOT NULL", Integer.class);
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
        try {
            ok = future.get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            ok = false;
        }
        if (ok) {
            everReady.add(name);
            return "ready";
        }
        return everReady.contains(name) ? "error" : "bootstrapping";
    }

    private String overall(Map<String, String> components) {
        if (components.containsValue("error")) return "error";
        if (components.containsValue("bootstrapping")) return "bootstrapping";
        return "ready";
    }
}
