package io.legion.contracts;

import java.time.OffsetDateTime;
import java.util.UUID;

/** agent_task_queue 中一行任务的对外契约行（M0 只暴露入队产物）。 */
public record AgentTaskDto(
        UUID id,
        UUID workspaceId,
        UUID issueId,
        UUID agentId,
        String status,
        int priority,
        OffsetDateTime createdAt) {
}