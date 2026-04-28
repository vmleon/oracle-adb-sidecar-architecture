package dev.victormartin.adbsidecar.back.agents;

import dev.victormartin.adbsidecar.back.agents.dto.AgentRunRequest;
import dev.victormartin.adbsidecar.back.agents.dto.AgentRunResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/agents")
public class AgentsController {

    private static final Logger log = LoggerFactory.getLogger(AgentsController.class);
    private static final int MAX_PROMPT_LEN = 1000;
    private static final Pattern ALLOWED_PROMPT =
            Pattern.compile("^[\\p{L}\\p{N}\\s,\\.\\-()\\?!':;\"/&#%$]+$");
    private static final Pattern UUID_LIKE = Pattern.compile("^[0-9a-fA-F-]{36}$");

    private final AgentsService service;

    public AgentsController(AgentsService service) {
        this.service = service;
    }

    @PostMapping
    public AgentRunResponse run(@RequestBody AgentRunRequest req) {
        String prompt = validatePrompt(req.prompt());
        String validatedId = validateConversationId(req.conversationId());
        log.info("Agent run: prompt='{}' conversation={}", prompt, validatedId);
        return service.runTeam(prompt, validatedId);
    }

    private String validatePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("Prompt cannot be empty");
        String trimmed = prompt.trim();
        if (trimmed.length() > MAX_PROMPT_LEN) throw new IllegalArgumentException("Prompt too long (max " + MAX_PROMPT_LEN + " characters)");
        if (!ALLOWED_PROMPT.matcher(trimmed).matches()) throw new IllegalArgumentException("Prompt contains invalid characters");
        return trimmed;
    }

    private String validateConversationId(String id) {
        if (id == null || id.isBlank()) return null;
        if (!UUID_LIKE.matcher(id).matches()) {
            throw new IllegalArgumentException("conversationId must be a UUID");
        }
        return id;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, String>> upstream(RuntimeException e) {
        log.error("Agent run failed: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }
}
