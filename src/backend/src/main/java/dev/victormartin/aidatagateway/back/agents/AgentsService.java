package dev.victormartin.aidatagateway.back.agents;

import dev.victormartin.aidatagateway.back.agents.dto.AgentRunResponse;
import dev.victormartin.aidatagateway.back.agents.dto.AgentTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Service
public class AgentsService {

    private static final Logger log = LoggerFactory.getLogger(AgentsService.class);

    private static final String RUN_TEAM_SQL =
            "SELECT DBMS_CLOUD_AI_AGENT.RUN_TEAM(?, ?, ?) FROM DUAL";

    // RUN_TEAM validates the conversation_id in params against the
    // conversation catalog (USER_CLOUD_AI_CONVERSATIONS) and raises
    // ORA-20050 for ids it has never seen. Every conversation therefore
    // has to be created server-side first; the returned id is what flows
    // back to the client for multi-turn follow-ups.
    private static final String CREATE_CONVERSATION_SQL =
            "SELECT DBMS_CLOUD_AI.CREATE_CONVERSATION('{\"title\":\"AI Data Gateway assistant\"}') FROM DUAL";

    // USER_AI_AGENT_TEAM_HISTORY.CONVERSATION_ID holds the caller-supplied
    // conversation id for each team run. (Task-level CONVERSATION_PARAMS
    // holds internally generated per-task conversation ids and cannot be
    // used to resolve the caller's conversation.)
    private static final String RESOLVE_EXEC_ID_SQL = """
            SELECT TEAM_EXEC_ID FROM (
                SELECT TEAM_EXEC_ID
                FROM USER_AI_AGENT_TEAM_HISTORY
                WHERE CONVERSATION_ID = ?
                ORDER BY START_DATE DESC
            ) WHERE ROWNUM = 1
            """;

    private static final String TEAM_HISTORY_SQL =
            "SELECT TEAM_NAME, STATE FROM USER_AI_AGENT_TEAM_HISTORY WHERE TEAM_EXEC_ID = ?";

    // RUN_TEAM can raise from its return path (ORA-20000 wrapping ORA-01010,
    // inside the DBMS_CLOUD HTTP helper) *after* every task has already
    // reached SUCCEEDED. The synthesised answer is durable in the catalog at
    // that point, so the request is recoverable: read the last task's result
    // rather than handing the caller a stack trace for work that completed.
    private static final String RECOVER_ANSWER_SQL = """
            SELECT RESULT FROM (
                SELECT t.RESULT
                FROM USER_AI_AGENT_TASK_HISTORY t
                JOIN USER_AI_AGENT_TEAM_HISTORY h ON h.TEAM_EXEC_ID = t.TEAM_EXEC_ID
                WHERE h.CONVERSATION_ID = ?
                  AND h.STATE = 'SUCCEEDED'
                  AND t.STATE = 'SUCCEEDED'
                ORDER BY h.START_DATE DESC, t.TASK_ORDER DESC
            ) WHERE ROWNUM = 1
            """;

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

    // The actual LLM prompt + response pairs for a conversation. Joined
    // via the conversation_id stored in CONVERSATION_PARAMS.
    private static final String PROMPT_HISTORY_SQL = """
            SELECT t.TASK_NAME, p.PROMPT, p.PROMPT_RESPONSE,
                   TO_CHAR(p.CREATED, 'YYYY-MM-DD"T"HH24:MI:SS.FF3"Z"') AS CREATED
            FROM USER_CLOUD_AI_CONVERSATION_PROMPTS p
            LEFT JOIN USER_AI_AGENT_TASK_HISTORY t
                   ON JSON_VALUE(t.CONVERSATION_PARAMS, '$.conversation_id') = p.CONVERSATION_ID
                  AND t.TEAM_EXEC_ID = ?
            WHERE p.CONVERSATION_ID = ?
            ORDER BY p.CREATED
            """;

    // Per-task scheduler additional_info (the only place that holds the
    // actual ORA-* on failure — the agent history views drop the message).
    private static final String SCHEDULER_INFO_FOR_TASK_SQL = """
            SELECT additional_info
            FROM   user_scheduler_job_run_details
            WHERE  job_name = ?
            ORDER  BY log_date DESC
            FETCH  FIRST 1 ROWS ONLY
            """;

    // Latest non-success scheduler-job error for any task in this team.
    // Used to enrich exception messages bubbled from RUN_TEAM.
    private static final String LATEST_TEAM_ERROR_SQL = """
            SELECT additional_info
            FROM   user_scheduler_job_run_details
            WHERE  job_name LIKE ? || '_TASK_%'
              AND  status <> 'SUCCEEDED'
              AND  log_date > SYSTIMESTAMP - INTERVAL '5' MINUTE
            ORDER  BY log_date DESC
            FETCH  FIRST 1 ROWS ONLY
            """;

    private static final String TEAM_EXISTS_SQL =
            "SELECT COUNT(*) FROM USER_AI_AGENT_TEAMS WHERE AGENT_TEAM_NAME = ? AND STATUS = 'ENABLED'";

    // One transparent retry on the PG_LINK heterogeneous-gateway flap
    // (mode A in docs/ISSUE_AI_AGENT_RUN_TEAM_PG_LINK_WEDGE.md). The
    // gateway drops idle RPC connections at HS_IDLE_TIMEOUT (~5 min) and
    // the very next call lands on a fresh worker. Without this retry,
    // any user prompt that arrives in the few-hundred-ms window between
    // a keep-warm flap and the gateway recovering surfaces ORA-28511.
    private static final int RUN_TEAM_GATEWAY_RETRIES = 1;
    private static final long RUN_TEAM_RETRY_DELAY_MS = 250L;

    private final JdbcTemplate jdbc;
    // Separate template with a 120 s query timeout for warm-up + keep-warm
    // RUN_TEAM calls. Without this, a single hung RUN_TEAM blocks Spring's
    // single-threaded scheduler indefinitely (fixedDelay only fires after
    // the previous returns), the gateway HS_IDLE_TIMEOUT fires at 5 min,
    // and the wedge becomes inevitable on the next user request.
    private final JdbcTemplate keepWarmJdbc;
    private final String teamName;
    private final boolean warmUpEnabled;
    private final boolean keepWarmEnabled;
    private final Executor warmUpExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agents-warmup");
        t.setDaemon(true);
        return t;
    });

    public AgentsService(@Qualifier("adbJdbc") JdbcTemplate jdbc,
                         @Value("${selectai.agents.team:BANKING_INVESTIGATION_TEAM}") String teamName,
                         @Value("${selectai.agents.warmup.enabled:true}") boolean warmUpEnabled,
                         @Value("${selectai.agents.keepwarm.enabled:true}") boolean keepWarmEnabled) {
        this.jdbc = jdbc;
        this.keepWarmJdbc = new JdbcTemplate(jdbc.getDataSource());
        // Generous timeout: cancelling a RUN_TEAM mid-flight (ORA-01013)
        // aborts DBMS_CLOUD between UTL_HTTP calls and leaks open HTTP
        // handles in the pooled session; after ~5 leaks that session fails
        // every RUN_TEAM with ORA-29270. RUN_TEAM legitimately runs 30-150 s
        // under load, so the timeout only exists to unblock the scheduler
        // thread from a truly hung call.
        this.keepWarmJdbc.setQueryTimeout(300);
        this.teamName = teamName;
        this.warmUpEnabled = warmUpEnabled;
        this.keepWarmEnabled = keepWarmEnabled;
    }

    public AgentRunResponse runTeam(String prompt, String conversationIdOrNull) {
        String conversationId = conversationIdOrNull != null
                ? conversationIdOrNull
                : createConversation(jdbc);

        long t0 = System.currentTimeMillis();
        String answer;
        try {
            try {
                answer = runTeamWithGatewayRetry(prompt, paramsJson(conversationId), conversationId);
            } catch (RuntimeException e) {
                // A client-supplied id can be stale (conversation dropped or
                // past retention). Start a fresh conversation and answer
                // without the old context rather than failing the request.
                if (conversationIdOrNull == null || !isUnknownConversation(e)) throw e;
                log.warn("event=run_team_stale_conversation conv={} team={} — creating a fresh conversation",
                        conversationId, teamName);
                conversationId = createConversation(jdbc);
                answer = runTeamWithGatewayRetry(prompt, paramsJson(conversationId), conversationId);
            }
        } catch (RuntimeException e) {
            refreshAdbPoolIfPoisoned(e);
            long failedMs = System.currentTimeMillis() - t0;

            // The agents may well have finished before the call blew up. If the
            // catalog says the run SUCCEEDED, serve that answer — the user gets
            // their result instead of an ORA stack for completed work.
            String recovered = recoverAnswer(conversationId);
            if (recovered != null && !recovered.isBlank()) {
                log.warn("event=run_team_recovered conv={} team={} elapsed_ms={} answer_chars={} ora=\"{}\"",
                        conversationId, teamName, failedMs, recovered.length(),
                        abbrev(e.getMessage(), 240));
                answer = recovered;
            } else {
                String schedulerInfo = fetchLatestTeamSchedulerError();
                log.error("event=run_team_failed conv={} team={} elapsed_ms={} ora=\"{}\" scheduler_additional_info=\"{}\"",
                        conversationId, teamName, failedMs,
                        abbrev(e.getMessage(), 240), abbrev(schedulerInfo, 240));
                String enriched = (e.getMessage() == null ? "RUN_TEAM failed" : e.getMessage())
                        + (schedulerInfo == null ? ""
                           : " | scheduler additional_info: " + schedulerInfo);
                throw new RuntimeException(enriched, e);
            }
        }
        long elapsed = System.currentTimeMillis() - t0;
        log.info("event=run_team_done conv={} team={} elapsed_ms={} answer_chars={}",
                conversationId, teamName, elapsed, answer == null ? 0 : answer.length());

        AgentTrace trace = null;
        try {
            String execId = jdbc.queryForObject(RESOLVE_EXEC_ID_SQL, String.class, conversationId);
            if (execId != null) {
                trace = buildTrace(execId, conversationId);
            }
        } catch (Exception e) {
            log.warn("Trace assembly failed for conversation {}: {}", conversationId, e.getMessage());
        }

        return new AgentRunResponse(prompt, answer, conversationId, elapsed, trace);
    }

    private String runTeamWithGatewayRetry(String prompt, String paramsJson, String conversationId) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= RUN_TEAM_GATEWAY_RETRIES; attempt++) {
            try {
                return jdbc.queryForObject(RUN_TEAM_SQL, String.class, teamName, prompt, paramsJson);
            } catch (RuntimeException e) {
                last = e;
                if (attempt >= RUN_TEAM_GATEWAY_RETRIES || !isGatewayTransient(e)) throw e;
                log.warn("event=run_team_retry conv={} team={} attempt={} cause=\"{}\"",
                        conversationId, teamName, attempt + 1,
                        abbrev(NestedExceptionUtils.getMostSpecificCause(e).getMessage(), 240));
                try { Thread.sleep(RUN_TEAM_RETRY_DELAY_MS); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    private static boolean isGatewayTransient(Throwable t) {
        Throwable cur = t;
        for (int depth = 0; cur != null && depth < 10; depth++, cur = cur.getCause()) {
            String m = cur.getMessage();
            if (m == null) continue;
            if (m.contains("ORA-28511") || m.contains("ORA-28509") || m.contains("ORA-02063")) return true;
        }
        return false;
    }

    private static boolean isUnknownConversation(Throwable t) {
        return causeContains(t, "ORA-20050");
    }

    // ORA-01013: a cancelled RUN_TEAM just leaked open UTL_HTTP handles in
    // its pooled session. ORA-29270: a session already carries >= 5 leaked
    // handles and fails every RUN_TEAM it serves. Either way the affected
    // connections are poison — refresh the pool so subsequent calls get
    // fresh sessions instead of failing until the next backend restart.
    private static boolean isSessionPoisoning(Throwable t) {
        return causeContains(t, "ORA-29270") || causeContains(t, "ORA-01013");
    }

    private static boolean causeContains(Throwable t, String marker) {
        Throwable cur = t;
        for (int depth = 0; cur != null && depth < 10; depth++, cur = cur.getCause()) {
            String m = cur.getMessage();
            if (m != null && m.contains(marker)) return true;
        }
        return false;
    }

    private void refreshAdbPoolIfPoisoned(Throwable t) {
        if (!isSessionPoisoning(t)) return;
        try {
            oracle.ucp.admin.UniversalConnectionPoolManagerImpl
                    .getUniversalConnectionPoolManager()
                    .refreshConnectionPool("adb-ucp");
            log.warn("event=adb_pool_refreshed reason=leaked_utl_http_handles");
        } catch (Exception e) {
            log.warn("event=adb_pool_refresh_failed cause=\"{}\"", abbrev(e.getMessage(), 240));
        }
    }

    // Best-effort: never let a recovery attempt mask the original failure.
    private String recoverAnswer(String conversationId) {
        try {
            return jdbc.queryForObject(RECOVER_ANSWER_SQL, String.class, conversationId);
        } catch (Exception e) {
            log.debug("No recoverable answer for conversation {}: {}", conversationId, e.getMessage());
            return null;
        }
    }

    private static String paramsJson(String conversationId) {
        return "{\"conversation_id\":\"" + conversationId + "\"}";
    }

    private String createConversation(JdbcTemplate template) {
        return template.queryForObject(CREATE_CONVERSATION_SQL, String.class);
    }

    // Every tick gets its own conversation. Re-running a team against one
    // long-lived conversation replays a history that only ever grows, and the
    // call slows down with it — measured at 8.2 s for a conversation's first
    // run and 18.2 s for its second. Sharing one id across ticks eventually
    // pushed the call past the query timeout, after which it never recovered.
    private volatile String keepWarmConversationId;

    private String keepWarmConversation() {
        String id = createConversation(keepWarmJdbc);
        keepWarmConversationId = id;
        return id;
    }

    // Fire one throwaway RUN_TEAM call after the backend is up so the GenAI
    // workers are warm before the first real user request. Polls in a
    // background thread until the team is ENABLED — the backend can boot
    // before Liquibase has finished creating the team — then runs once.
    // Failures are logged and dropped: if warm-up never completes, user
    // requests still work, they just pay the cold-start cost themselves.
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
                    String conv = keepWarmConversation();
                    keepWarmJdbc.queryForObject(RUN_TEAM_SQL, String.class, teamName, "warm-up ping; reply with OK.", paramsJson(conv));
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

    // PG_LINK-via-heterogeneous-gateway connections cached in DBMS_SCHEDULER
    // worker sessions die after HS_IDLE_TIMEOUT (~5 min) and the next TASK_0
    // wedges with ORA-01010 / ORA-02063 from PG_LINK until the worker is
    // reaped (~10 min). Continuous traffic on the agent path prevents the
    // wedge — see docs/ISSUE_AI_AGENT_RUN_TEAM_PG_LINK_WEDGE.md. fixedDelay
    // (not fixedRate) so a slow RUN_TEAM never overlaps with the next tick.
    @Scheduled(
            fixedDelayString = "${selectai.agents.keepwarm.interval-ms:60000}",
            initialDelayString = "${selectai.agents.keepwarm.initial-delay-ms:90000}")
    public void keepWarm() {
        if (!keepWarmEnabled) return;
        try {
            Integer count = jdbc.queryForObject(TEAM_EXISTS_SQL, Integer.class, teamName);
            if (count == null || count == 0) return;
            long t0 = System.currentTimeMillis();
            String conv = keepWarmConversation();
            keepWarmJdbc.queryForObject(RUN_TEAM_SQL, String.class, teamName, "keep-warm; reply with OK.", paramsJson(conv));
            log.debug("event=keepwarm_ok team={} conv={} elapsed_ms={}", teamName, conv, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            if (isUnknownConversation(e)) keepWarmConversationId = null;
            refreshAdbPoolIfPoisoned(e);
            Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
            log.warn("event=keepwarm_failed team={} cause=\"{}\"", teamName, abbrev(root.getMessage(), 240));
        }
    }

    public AgentTrace traceForConversation(String conversationId) {
        String execId;
        try {
            execId = jdbc.queryForObject(RESOLVE_EXEC_ID_SQL, String.class, conversationId);
        } catch (RuntimeException e) {
            return null;
        }
        return execId == null ? null : buildTrace(execId, conversationId);
    }

    private AgentTrace buildTrace(String execId, String conversationId) {
        Map<String, Object> team = jdbc.queryForMap(TEAM_HISTORY_SQL, execId);
        List<AgentTrace.TaskTrace> tasks = jdbc.query(TASK_HISTORY_SQL, (rs, n) -> {
            String state = rs.getString("STATE");
            String taskName = rs.getString("TASK_NAME");
            String additional = "FAILED".equalsIgnoreCase(state)
                    ? fetchSchedulerAdditionalInfo(teamName + "_" + taskName)
                    : null;
            return new AgentTrace.TaskTrace(
                    rs.getString("AGENT_NAME"),
                    taskName,
                    rs.getInt("TASK_ORDER"),
                    rs.getString("INPUT"),
                    rs.getString("RESULT"),
                    state,
                    rs.getLong("DURATION_MS"),
                    additional);
        }, execId);
        List<AgentTrace.ToolTrace> tools = jdbc.query(TOOL_HISTORY_SQL, (rs, n) -> new AgentTrace.ToolTrace(
                rs.getString("AGENT_NAME"),
                rs.getString("TOOL_NAME"),
                rs.getString("TASK_NAME"),
                rs.getInt("TASK_ORDER"),
                rs.getString("INPUT"),
                rs.getString("OUTPUT"),
                rs.getString("TOOL_OUTPUT"),
                rs.getLong("DURATION_MS")), execId);
        List<AgentTrace.PromptTrace> prompts = fetchPrompts(execId, conversationId);
        return new AgentTrace(execId, (String) team.get("TEAM_NAME"), (String) team.get("STATE"),
                tasks, tools, prompts);
    }

    private List<AgentTrace.PromptTrace> fetchPrompts(String execId, String conversationId) {
        try {
            return jdbc.query(PROMPT_HISTORY_SQL, (rs, n) -> new AgentTrace.PromptTrace(
                    rs.getString("TASK_NAME"),
                    rs.getString("PROMPT"),
                    rs.getString("PROMPT_RESPONSE"),
                    rs.getString("CREATED")), execId, conversationId);
        } catch (RuntimeException e) {
            // USER_CLOUD_AI_CONVERSATION_PROMPTS may not exist on every ADB
            // build. Don't break the trace if it isn't there.
            log.debug("Prompt history unavailable for conversation {}: {}", conversationId, e.getMessage());
            return List.of();
        }
    }

    private String fetchSchedulerAdditionalInfo(String jobName) {
        try {
            List<String> rows = jdbc.queryForList(SCHEDULER_INFO_FOR_TASK_SQL, String.class, jobName);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException e) {
            log.debug("Scheduler info lookup failed for job {}: {}", jobName, e.getMessage());
            return null;
        }
    }

    private String fetchLatestTeamSchedulerError() {
        try {
            List<String> rows = jdbc.queryForList(LATEST_TEAM_ERROR_SQL, String.class, teamName);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException e) {
            log.debug("Latest team scheduler error lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
    }
}
