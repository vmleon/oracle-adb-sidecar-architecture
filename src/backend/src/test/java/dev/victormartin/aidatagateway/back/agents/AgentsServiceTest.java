package dev.victormartin.aidatagateway.back.agents;

import dev.victormartin.aidatagateway.back.agents.dto.AgentRunResponse;
import dev.victormartin.aidatagateway.back.agents.dto.AgentTrace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import org.mockito.ArgumentMatchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentsServiceTest {

    private JdbcTemplate jdbc;
    private AgentsService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.getDataSource()).thenReturn(mock(DataSource.class));
        service = new AgentsService(jdbc, "BANKING_INVESTIGATION_TEAM", false, false);
    }

    @Test
    void runTeam_calls_run_team_with_team_prompt_and_conversation_id() {
        when(jdbc.queryForObject(contains("RUN_TEAM"), eq(String.class), any(), any(), any()))
                .thenReturn("Final answer.");
        when(jdbc.queryForObject(contains("TEAM_EXEC_ID"), eq(String.class), any()))
                .thenReturn("EXEC-42");
        when(jdbc.queryForMap(contains("USER_AI_AGENT_TEAM_HISTORY"), any()))
                .thenReturn(Map.of("TEAM_NAME", "BANKING_INVESTIGATION_TEAM", "STATE", "SUCCEEDED"));
        when(jdbc.query(
                contains("USER_AI_AGENT_TASK_HISTORY"),
                ArgumentMatchers.<RowMapper<AgentTrace.TaskTrace>>any(),
                ArgumentMatchers.<Object>any()))
                .thenReturn(List.<AgentTrace.TaskTrace>of());
        when(jdbc.query(
                contains("USER_AI_AGENT_TOOL_HISTORY"),
                ArgumentMatchers.<RowMapper<AgentTrace.ToolTrace>>any(),
                ArgumentMatchers.<Object>any()))
                .thenReturn(List.<AgentTrace.ToolTrace>of());

        AgentRunResponse resp = service.runTeam("Hello", "conv-1");

        assertThat(resp.answer()).isEqualTo("Final answer.");
        assertThat(resp.conversationId()).isEqualTo("conv-1");
        assertThat(resp.trace()).isNotNull();
        assertThat(resp.trace().teamExecId()).isEqualTo("EXEC-42");

        verify(jdbc).queryForObject(
                contains("DBMS_CLOUD_AI_AGENT.RUN_TEAM"),
                eq(String.class),
                eq("BANKING_INVESTIGATION_TEAM"),
                eq("Hello"),
                eq("{\"conversation_id\":\"conv-1\"}"));
    }

    @Test
    void runTeam_generates_conversation_id_when_absent() {
        when(jdbc.queryForObject(anyString(), eq(String.class), any(), any(), any()))
                .thenReturn("ok");
        when(jdbc.queryForObject(contains("TEAM_EXEC_ID"), eq(String.class), any()))
                .thenReturn(null);
        AgentRunResponse resp = service.runTeam("Hi", null);
        assertThat(resp.conversationId()).matches("[0-9a-f-]{36}");
        assertThat(resp.trace()).isNull();
    }

    @Test
    void runTeam_retries_once_on_pg_link_gateway_drop() {
        String oraMsg = "ORA-28511: lost RPC connection to heterogeneous remote agent\n"
                + "ORA-02063: preceding line from PG_LINK";
        when(jdbc.queryForObject(contains("RUN_TEAM"), eq(String.class), any(), any(), any()))
                .thenThrow(new RuntimeException(oraMsg))
                .thenReturn("ok after retry");
        when(jdbc.queryForObject(contains("TEAM_EXEC_ID"), eq(String.class), any()))
                .thenReturn(null);

        AgentRunResponse resp = service.runTeam("Hello", "conv-retry");

        assertThat(resp.answer()).isEqualTo("ok after retry");
        verify(jdbc, times(2)).queryForObject(
                contains("DBMS_CLOUD_AI_AGENT.RUN_TEAM"),
                eq(String.class), any(), any(), any());
    }

    @Test
    void runTeam_does_not_retry_on_non_gateway_error() {
        when(jdbc.queryForObject(contains("RUN_TEAM"), eq(String.class), any(), any(), any()))
                .thenThrow(new RuntimeException("ORA-12345: something unrelated"));

        assertThatThrownBy(() -> service.runTeam("Hello", "conv-x"))
                .hasMessageContaining("ORA-12345");
        verify(jdbc, times(1)).queryForObject(
                contains("DBMS_CLOUD_AI_AGENT.RUN_TEAM"),
                eq(String.class), any(), any(), any());
    }

    @Test
    void runTeam_returns_null_trace_when_catalog_query_fails() {
        when(jdbc.queryForObject(contains("RUN_TEAM"), eq(String.class), any(), any(), any()))
                .thenReturn("ok");
        when(jdbc.queryForObject(contains("TEAM_EXEC_ID"), eq(String.class), any()))
                .thenThrow(new RuntimeException("ORA-00942"));
        AgentRunResponse resp = service.runTeam("Hi", "conv-2");
        assertThat(resp.trace()).isNull();
        assertThat(resp.answer()).isEqualTo("ok");
    }
}
