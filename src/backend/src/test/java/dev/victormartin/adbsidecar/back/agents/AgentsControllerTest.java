package dev.victormartin.adbsidecar.back.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.victormartin.adbsidecar.back.agents.dto.AgentRunRequest;
import dev.victormartin.adbsidecar.back.agents.dto.AgentRunResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AgentsController.class)
class AgentsControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean AgentsService service;

    @Test
    void post_agents_returns_200_with_run_response() throws Exception {
        String convId = "550e8400-e29b-41d4-a716-446655440000";
        when(service.runTeam(any(), any()))
                .thenReturn(new AgentRunResponse("hi", "answer", convId, 100L, null));
        mvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new AgentRunRequest("hi", convId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("answer"))
                .andExpect(jsonPath("$.conversationId").value(convId))
                .andExpect(jsonPath("$.elapsedMillis").value(100));
    }

    @Test
    void post_agents_returns_400_on_blank_prompt() throws Exception {
        mvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new AgentRunRequest("   ", null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_agents_returns_400_on_too_long_prompt() throws Exception {
        String tooLong = "a".repeat(1001);
        mvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new AgentRunRequest(tooLong, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_agents_returns_502_on_service_exception() throws Exception {
        when(service.runTeam(any(), any())).thenThrow(new RuntimeException("ORA-29024"));
        mvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new AgentRunRequest("hi", null))))
                .andExpect(status().isBadGateway());
    }

    @Test
    void post_agents_returns_400_on_malformed_conversation_id() throws Exception {
        mvc.perform(post("/api/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AgentRunRequest("hi", "not-a-uuid\",\"injected\":\"x"))))
                .andExpect(status().isBadRequest());
    }
}
