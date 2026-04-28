package dev.victormartin.adbsidecar.back.agents.dto;

import java.util.List;

public record AgentTrace(
        String teamExecId,
        String teamName,
        String state,
        List<TaskTrace> tasks,
        List<ToolTrace> tools) {

    public record TaskTrace(
            String agentName,
            String taskName,
            int taskOrder,
            String input,
            String result,
            String state,
            long durationMillis) {}

    public record ToolTrace(
            String agentName,
            String toolName,
            String taskName,
            int taskOrder,
            String input,
            String output,
            String toolOutput,
            long durationMillis) {}
}
