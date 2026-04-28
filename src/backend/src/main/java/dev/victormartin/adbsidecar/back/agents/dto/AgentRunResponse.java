package dev.victormartin.adbsidecar.back.agents.dto;

public record AgentRunResponse(
        String prompt,
        String answer,
        String conversationId,
        long elapsedMillis,
        AgentTrace trace) {}
