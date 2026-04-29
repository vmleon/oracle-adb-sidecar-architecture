package dev.victormartin.adbsidecar.back.agents;

import dev.victormartin.adbsidecar.back.agents.dto.AgentRunResponse;
import dev.victormartin.adbsidecar.back.agents.dto.AgentTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Service
public class AgentsService {

    private static final Logger log = LoggerFactory.getLogger(AgentsService.class);

    private static final String RUN_TEAM_SQL =
            "SELECT DBMS_CLOUD_AI_AGENT.RUN_TEAM(?, ?, ?) FROM DUAL";

    // NOTE: Oracle typo on the catalog column name — COVERSATION_PARAM (sic).
    // Do not "fix" — the catalog itself spells it that way.
    private static final String RESOLVE_EXEC_ID_SQL = """
            SELECT TEAM_EXEC_ID FROM (
                SELECT DISTINCT TEAM_EXEC_ID, START_DATE
                FROM USER_AI_AGENT_TASK_HISTORY
                WHERE JSON_VALUE(COVERSATION_PARAM, '$.conversation_id') = ?
                ORDER BY START_DATE DESC
            ) WHERE ROWNUM = 1
            """;

    private static final String TEAM_HISTORY_SQL =
            "SELECT TEAM_NAME, STATE FROM USER_AI_AGENT_TEAM_HISTORY WHERE TEAM_EXEC_ID = ?";

    private static final String TASK_HISTORY_SQL = """
            SELECT AGENT_NAME, TASK_NAME, TASK_ORDER, INPUT, RESULT, STATE,
                   EXTRACT(DAY FROM (END_DATE - START_DATE)) * 86400000 +
                   EXTRACT(HOUR FROM (END_DATE - START_DATE)) * 3600000 +
                   EXTRACT(MINUTE FROM (END_DATE - START_DATE)) * 60000 +
                   ROUND(EXTRACT(SECOND FROM (END_DATE - START_DATE)) * 1000) AS DURATION_MS
            FROM USER_AI_AGENT_TASK_HISTORY
            WHERE TEAM_EXEC_ID = ?
            ORDER BY TASK_ORDER
            """;

    private static final String TOOL_HISTORY_SQL = """
            SELECT AGENT_NAME, TOOL_NAME, TASK_NAME, TASK_ORDER, INPUT, OUTPUT, TOOL_OUTPUT,
                   EXTRACT(DAY FROM (END_DATE - START_DATE)) * 86400000 +
                   EXTRACT(HOUR FROM (END_DATE - START_DATE)) * 3600000 +
                   EXTRACT(MINUTE FROM (END_DATE - START_DATE)) * 60000 +
                   ROUND(EXTRACT(SECOND FROM (END_DATE - START_DATE)) * 1000) AS DURATION_MS
            FROM USER_AI_AGENT_TOOL_HISTORY
            WHERE TEAM_EXEC_ID = ?
            ORDER BY TASK_ORDER, START_DATE
            """;

    private static final String TEAM_EXISTS_SQL =
            "SELECT COUNT(*) FROM USER_AI_AGENT_TEAMS WHERE AGENT_TEAM_NAME = ? AND STATUS = 'ENABLED'";

    private final JdbcTemplate jdbc;
    private final String teamName;
    private final boolean warmUpEnabled;
    private final Executor warmUpExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agents-warmup");
        t.setDaemon(true);
        return t;
    });

    public AgentsService(@Qualifier("adbJdbc") JdbcTemplate jdbc,
                         @Value("${selectai.agents.team:BANKING_INVESTIGATION_TEAM}") String teamName,
                         @Value("${selectai.agents.warmup.enabled:true}") boolean warmUpEnabled) {
        this.jdbc = jdbc;
        this.teamName = teamName;
        this.warmUpEnabled = warmUpEnabled;
    }

    public AgentRunResponse runTeam(String prompt, String conversationIdOrNull) {
        String conversationId = conversationIdOrNull != null
                ? conversationIdOrNull
                : UUID.randomUUID().toString();
        String paramsJson = "{\"conversation_id\":\"" + conversationId + "\"}";

        long t0 = System.currentTimeMillis();
        String answer = runTeamWithRetry(prompt, paramsJson);
        long elapsed = System.currentTimeMillis() - t0;
        log.info("RUN_TEAM completed in {}ms (conversation={}, team={})", elapsed, conversationId, teamName);

        AgentTrace trace = null;
        try {
            String execId = jdbc.queryForObject(RESOLVE_EXEC_ID_SQL, String.class, conversationId);
            if (execId != null) {
                trace = buildTrace(execId);
            }
        } catch (Exception e) {
            log.warn("Trace assembly failed for conversation {}: {}", conversationId, e.getMessage());
        }

        return new AgentRunResponse(prompt, answer, conversationId, elapsed, trace);
    }

    // Fire one throwaway RUN_TEAM call after the backend is up so the GenAI
    // workers and heterogeneous gateways are warm before the first real user
    // request. Polls in a background thread until the team is ENABLED — the
    // backend can boot before Liquibase has finished creating the team — then
    // runs once. Failures are logged and dropped: if warm-up never completes,
    // user requests still work, they just pay the cold-start cost themselves.
    @EventListener(ApplicationReadyEvent.class)
    public void scheduleWarmUp() {
        if (!warmUpEnabled) {
            log.info("Agents warm-up disabled (selectai.agents.warmup.enabled=false)");
            return;
        }
        warmUpExecutor.execute(this::warmUpLoop);
    }

    private void warmUpLoop() {
        int maxAttempts = 60;       // ~10 minutes total when paired with the 10s sleep
        long sleepMs = 10_000L;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                Integer count = jdbc.queryForObject(TEAM_EXISTS_SQL, Integer.class, teamName);
                if (count != null && count > 0) {
                    log.info("Warming up agents team {}...", teamName);
                    long t0 = System.currentTimeMillis();
                    String params = "{\"conversation_id\":\"_warmup_" + UUID.randomUUID() + "\"}";
                    runTeamWithRetry("warm-up ping; reply with OK.", params);
                    log.info("Agents warm-up complete in {}ms", System.currentTimeMillis() - t0);
                    return;
                }
            } catch (Exception e) {
                log.debug("Warm-up probe attempt {} failed: {}", i + 1, e.getMessage());
            }
            try { Thread.sleep(sleepMs); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Agents warm-up gave up after {} attempts; team {} never reported ENABLED",
                maxAttempts, teamName);
    }

    // First-contact ORA-* codes that surface from RUN_TEAM during cold-start
    // of the underlying GenAI worker / heterogeneous gateway and consistently
    // succeed on a second attempt. Add to this list when new transients are
    // observed in the field; do not catch arbitrary errors — a real failure
    // (ORA-20051 task validation, ORA-00942 missing view) must not be retried.
    private static final String[] RETRYABLE_ORA = {
            "ORA-28511", // lost RPC connection to heterogeneous remote agent
            "ORA-01010", // invalid OCI operation (GenAI worker first-call flake)
    };

    private String runTeamWithRetry(String prompt, String paramsJson) {
        try {
            return jdbc.queryForObject(RUN_TEAM_SQL, String.class, teamName, prompt, paramsJson);
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            String hit = null;
            for (String code : RETRYABLE_ORA) {
                if (msg.contains(code)) { hit = code; break; }
            }
            if (hit == null) throw e;
            log.warn("RUN_TEAM hit {} on first attempt. Retrying once.", hit);
            return jdbc.queryForObject(RUN_TEAM_SQL, String.class, teamName, prompt, paramsJson);
        }
    }

    private AgentTrace buildTrace(String execId) {
        Map<String, Object> team = jdbc.queryForMap(TEAM_HISTORY_SQL, execId);
        List<AgentTrace.TaskTrace> tasks = jdbc.query(TASK_HISTORY_SQL, (rs, n) -> new AgentTrace.TaskTrace(
                rs.getString("AGENT_NAME"),
                rs.getString("TASK_NAME"),
                rs.getInt("TASK_ORDER"),
                rs.getString("INPUT"),
                rs.getString("RESULT"),
                rs.getString("STATE"),
                rs.getLong("DURATION_MS")), execId);
        List<AgentTrace.ToolTrace> tools = jdbc.query(TOOL_HISTORY_SQL, (rs, n) -> new AgentTrace.ToolTrace(
                rs.getString("AGENT_NAME"),
                rs.getString("TOOL_NAME"),
                rs.getString("TASK_NAME"),
                rs.getInt("TASK_ORDER"),
                rs.getString("INPUT"),
                rs.getString("OUTPUT"),
                rs.getString("TOOL_OUTPUT"),
                rs.getLong("DURATION_MS")), execId);
        return new AgentTrace(execId, (String) team.get("TEAM_NAME"), (String) team.get("STATE"), tasks, tools);
    }
}
