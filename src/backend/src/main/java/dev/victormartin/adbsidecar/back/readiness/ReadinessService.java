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
        Map<String, String> components = new LinkedHashMap<>();
        components.put("adb",        probe("adb",        () -> { adbJdbc.queryForObject("SELECT 1 FROM DUAL", Integer.class); return true; }));
        components.put("oracleFree", probe("oracleFree", () -> { oracleJdbc.queryForObject("SELECT 1 FROM DUAL", Integer.class); return true; }));
        components.put("postgres",   probe("postgres",   () -> { postgresJdbc.queryForObject("SELECT 1", Integer.class); return true; }));
        components.put("mongo",      probe("mongo",      () -> { mongo.getDb().runCommand(new Document("ping", 1)); return true; }));
        components.put("agentsTeam", probe("agentsTeam", () -> {
            Integer n = adbJdbc.queryForObject(
                    "SELECT COUNT(*) FROM USER_CLOUD_AI_AGENT_TEAMS WHERE TEAM_NAME = ?",
                    Integer.class, teamName);
            return n != null && n > 0;
        }));
        return new ReadinessSnapshot(overall(components), components);
    }

    private String probe(String name, BooleanSupplier check) {
        boolean ok;
        try {
            ok = CompletableFuture.supplyAsync(check::getAsBoolean)
                    .get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
