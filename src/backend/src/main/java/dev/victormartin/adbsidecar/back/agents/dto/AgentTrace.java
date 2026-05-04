package dev.victormartin.adbsidecar.back.agents.dto;

import java.util.List;

public record AgentTrace(
        String teamExecId,
        String teamName,
        String state,
        List<TaskTrace> tasks,
        List<ToolTrace> tools,
        List<PromptTrace> prompts) {

    public record TaskTrace(
            String agentName,
            String taskName,
            int taskOrder,
            String input,
            String result,
            String state,
            long durationMillis,
            // additional_info from USER_SCHEDULER_JOB_RUN_DETAILS for the
            // scheduler job that backed this task. Null on success; carries
            // the real ORA-* (e.g. ORA-02063 from PG_LINK) on failure.
            String schedulerAdditionalInfo) {}

    public record ToolTrace(
            String agentName,
            String toolName,
            String taskName,
            int taskOrder,
            String input,
            String output,
            String toolOutput,
            long durationMillis) {}

    public record PromptTrace(
            String taskName,
            String prompt,
            String promptResponse,
            String created) {}
}
