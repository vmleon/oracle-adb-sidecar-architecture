package dev.victormartin.adbsidecar.back.agents;

import dev.victormartin.adbsidecar.back.agents.dto.AgentRunResponse;
import dev.victormartin.adbsidecar.back.agents.dto.AgentTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

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

    private final JdbcTemplate jdbc;
    private final String teamName;

    public AgentsService(@Qualifier("adbJdbc") JdbcTemplate jdbc,
                         @Value("${selectai.agents.team:BANKING_INVESTIGATION_TEAM}") String teamName) {
        this.jdbc = jdbc;
        this.teamName = teamName;
    }

    public AgentRunResponse runTeam(String prompt, String conversationIdOrNull) {
        String conversationId = conversationIdOrNull != null
                ? conversationIdOrNull
                : UUID.randomUUID().toString();
        String paramsJson = "{\"conversation_id\":\"" + conversationId + "\"}";

        long t0 = System.currentTimeMillis();
        String answer = jdbc.queryForObject(RUN_TEAM_SQL, String.class, teamName, prompt, paramsJson);
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
