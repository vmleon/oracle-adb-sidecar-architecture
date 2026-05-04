package dev.victormartin.adbsidecar.back.diag;

import dev.victormartin.adbsidecar.back.agents.AgentsService;
import dev.victormartin.adbsidecar.back.agents.dto.AgentTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Read-only diagnostic endpoints for the AI agent stack and the
// heterogeneous-gateway DB_LINKs. All queries hit ADB. Designed to be
// curl-able from ops (private VCN, no auth). Mirrors the manual
// sqlcl steps documented in docs/ISSUE_AI_AGENT_RUN_TEAM_PG_LINK_WEDGE.md.
@RestController
@RequestMapping("/api/v1/diag")
public class DiagController {

    private static final Logger log = LoggerFactory.getLogger(DiagController.class);

    private final JdbcTemplate adb;
    private final AgentsService agents;
    private final String teamName;

    public DiagController(@Qualifier("adbJdbc") JdbcTemplate adb,
                          AgentsService agents,
                          @Value("${selectai.agents.team:BANKING_INVESTIGATION_TEAM}") String teamName) {
        this.adb = adb;
        this.agents = agents;
        this.teamName = teamName;
    }

    // --- Links ---------------------------------------------------------

    @GetMapping("/links")
    public List<Map<String, Object>> links() {
        return adb.queryForList(
                "SELECT db_link, host, valid, TO_CHAR(created,'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS created"
              + "  FROM user_db_links ORDER BY db_link");
    }

    // Foreground-session probe per link: SELECT 1 FROM dual@<LINK>.
    // Tests the same code path as a normal JDBC SELECT through the link.
    // Catches and reports ORA-* per link rather than failing the whole call.
    @GetMapping("/links/probe")
    public List<Map<String, Object>> linksProbeForeground() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> row : links()) {
            String link = (String) row.get("DB_LINK");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("link", link);
            long t0 = System.currentTimeMillis();
            try {
                Integer one = adb.queryForObject("SELECT 1 FROM dual@" + link, Integer.class);
                r.put("ok", one != null && one == 1);
                r.put("elapsedMs", System.currentTimeMillis() - t0);
            } catch (RuntimeException e) {
                r.put("ok", false);
                r.put("elapsedMs", System.currentTimeMillis() - t0);
                r.put("error", abbrev(e.getMessage()));
            }
            out.add(r);
        }
        return out;
    }

    // Scheduler-worker probe per link: submits a one-shot DBMS_SCHEDULER
    // job with auto_drop, polls run_details until it lands. Tests the same
    // session pool the AI agent's TASK_0 uses.
    @GetMapping("/links/scheduler-probe")
    public List<Map<String, Object>> linksProbeScheduler() throws InterruptedException {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> row : links()) {
            String link = (String) row.get("DB_LINK");
            String jobName = "DIAG_PROBE_" + link + "_" + System.currentTimeMillis();
            adb.update("BEGIN DBMS_SCHEDULER.CREATE_JOB("
                     + "  job_name   => ?,"
                     + "  job_type   => 'PLSQL_BLOCK',"
                     + "  job_action => 'DECLARE n NUMBER; BEGIN SELECT COUNT(*) INTO n FROM dual@" + link + "; END;',"
                     + "  enabled    => TRUE, auto_drop => TRUE); END;",
                     jobName);
            // Poll up to ~5 s for the run to land.
            Map<String, Object> result = null;
            for (int i = 0; i < 25 && result == null; i++) {
                Thread.sleep(200);
                List<Map<String, Object>> rows = adb.queryForList(
                        "SELECT status, additional_info FROM user_scheduler_job_run_details"
                      + " WHERE job_name = ? ORDER BY log_date DESC FETCH FIRST 1 ROWS ONLY",
                        jobName);
                if (!rows.isEmpty()) result = rows.get(0);
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("link", link);
            r.put("job", jobName);
            if (result == null) {
                r.put("ok", false);
                r.put("status", "TIMEOUT");
            } else {
                String status = (String) result.get("STATUS");
                r.put("ok", "SUCCEEDED".equalsIgnoreCase(status));
                r.put("status", status);
                Object info = result.get("ADDITIONAL_INFO");
                if (info != null) r.put("additionalInfo", abbrev(info.toString()));
            }
            out.add(r);
        }
        return out;
    }

    // --- Profiles & inventory -----------------------------------------

    @GetMapping("/profiles")
    public List<Map<String, Object>> profiles() {
        return adb.queryForList(
                "SELECT profile_name, attribute_name, attribute_value"
              + "  FROM user_cloud_ai_profile_attributes"
              + " ORDER BY profile_name, attribute_name");
    }

    // Bundles teams, agents, tasks, tools and their attributes in one call
    // so a single curl shows the entire configured agent stack. Each
    // sub-query is wrapped so a single bad view name doesn't kill the
    // whole bundle — the response carries an "error" string in place of
    // the rows for any view that can't be read.
    @GetMapping("/agents/inventory")
    public Map<String, Object> agentsInventory() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teams",        safeRows("SELECT * FROM user_ai_agent_teams           ORDER BY 1"));
        out.put("teamAttrs",    safeRows("SELECT * FROM user_ai_agent_team_attributes ORDER BY 1, 2"));
        out.put("agents",       safeRows("SELECT * FROM user_ai_agents                ORDER BY 1"));
        out.put("agentAttrs",   safeRows("SELECT * FROM user_ai_agent_attributes      ORDER BY 1, 2"));
        out.put("tasks",        safeRows("SELECT * FROM user_ai_agent_tasks           ORDER BY 1"));
        out.put("taskAttrs",    safeRows("SELECT * FROM user_ai_agent_task_attributes ORDER BY 1, 2"));
        out.put("tools",        safeRows("SELECT * FROM user_ai_agent_tools           ORDER BY 1"));
        out.put("toolAttrs",    safeRows("SELECT * FROM user_ai_agent_tool_attributes ORDER BY 1, 2"));
        out.put("profileAttrs", safeRows("SELECT profile_name, attribute_name, attribute_value"
                                       + "  FROM user_cloud_ai_profile_attributes ORDER BY 1, 2"));
        return out;
    }

    // Catalog-introspection helper. Use to verify exact column names of
    // any USER_AI_AGENT_* / USER_CLOUD_AI_* view before writing SQL
    // against it. Avoids guessing from doc pages that drift.
    //
    // Uses ALL_TAB_COLUMNS rather than USER_TAB_COLUMNS because the
    // dictionary views we care about (USER_AI_AGENT_*, USER_CLOUD_AI_*)
    // are SYS-owned with public synonyms — they're queryable from ADMIN
    // but don't appear under USER_TAB_COLUMNS for this user.
    @GetMapping("/columns")
    public List<Map<String, Object>> columns(@RequestParam String view) {
        return adb.queryForList(
                "SELECT owner, column_name, data_type, nullable, column_id"
              + "  FROM all_tab_columns WHERE table_name = ?"
              + "  ORDER BY owner, column_id",
                view.toUpperCase());
    }

    private Object safeRows(String sql) {
        try {
            return adb.queryForList(sql);
        } catch (RuntimeException e) {
            return Map.of("error", abbrev(e.getMessage()));
        }
    }

    // --- Runs & failures ----------------------------------------------

    // Recent team runs joined LEFT to the smoking-gun scheduler row for
    // any failed task in the same window. A single curl that answers
    // "what just failed and what was the real ORA-*?".
    @GetMapping("/agents/runs")
    public List<Map<String, Object>> recentRuns(@RequestParam(defaultValue = "20") int limit,
                                                @RequestParam(defaultValue = "60") int sinceMinutes) {
        return adb.queryForList(
                "SELECT * FROM ("
              + "  SELECT h.team_exec_id, h.team_name, h.state,"
              + "         TO_CHAR(h.start_date AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS started,"
              + "         TO_CHAR(h.end_date   AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS ended,"
              + "         (SELECT MAX(SUBSTR(d.additional_info, 1, 240))"
              + "            FROM user_scheduler_job_run_details d"
              + "           WHERE d.job_name LIKE h.team_name || '_TASK_%'"
              + "             AND d.log_date BETWEEN h.start_date AND NVL(h.end_date, SYSTIMESTAMP)"
              + "             AND d.status <> 'SUCCEEDED') AS scheduler_error"
              + "    FROM user_ai_agent_team_history h"
              + "   WHERE h.start_date > SYSTIMESTAMP - NUMTODSINTERVAL(?, 'MINUTE')"
              + "   ORDER BY h.start_date DESC"
              + ") WHERE ROWNUM <= ?",
                sinceMinutes, limit);
    }

    @GetMapping("/agents/runs/{conversationId}")
    public ResponseEntity<AgentTrace> traceByConversation(@PathVariable String conversationId) {
        AgentTrace trace = agents.traceForConversation(conversationId);
        if (trace == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(trace);
    }

    // Raw scheduler-job failures for the configured team — same query the
    // ISSUE doc tells operators to run manually in sqlcl.
    @GetMapping("/agents/scheduler-failures")
    public List<Map<String, Object>> schedulerFailures(@RequestParam(defaultValue = "60") int sinceMinutes,
                                                       @RequestParam(defaultValue = "20") int limit) {
        return adb.queryForList(
                "SELECT * FROM ("
              + "  SELECT job_name,"
              + "         TO_CHAR(log_date AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS at_utc,"
              + "         status, additional_info"
              + "    FROM user_scheduler_job_run_details"
              + "   WHERE job_name LIKE ? || '_TASK_%'"
              + "     AND status <> 'SUCCEEDED'"
              + "     AND log_date > SYSTIMESTAMP - NUMTODSINTERVAL(?, 'MINUTE')"
              + "   ORDER BY log_date DESC"
              + ") WHERE ROWNUM <= ?",
                teamName, sinceMinutes, limit);
    }

    // Active end-to-end smoke test. Fires a tiny RUN_TEAM and reports
    // success/failure with timing. Burns a small amount of LLM budget per
    // call — only run on demand.
    @GetMapping("/agents/sanity")
    public Map<String, Object> agentsSanity() {
        long t0 = System.currentTimeMillis();
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            var resp = agents.runTeam("reply with OK", null);
            r.put("ok", true);
            r.put("elapsedMs", resp.elapsedMillis());
            r.put("answerChars", resp.answer() == null ? 0 : resp.answer().length());
            r.put("conversationId", resp.conversationId());
        } catch (RuntimeException e) {
            r.put("ok", false);
            r.put("elapsedMs", System.currentTimeMillis() - t0);
            r.put("error", abbrev(e.getMessage()));
        }
        return r;
    }

    // --- Generic error handler -----------------------------------------

    @org.springframework.web.bind.annotation.ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, String>> error(RuntimeException e) {
        log.error("diag endpoint failed: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", abbrev(e.getMessage())));
    }

    private static String abbrev(String s) {
        if (s == null) return "";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= 800 ? oneLine : oneLine.substring(0, 800) + "...";
    }
}
