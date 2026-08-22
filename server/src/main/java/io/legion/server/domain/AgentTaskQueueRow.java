package io.legion.server.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** agent_task_queue 行对象（M0 只投影入队需要的列，不读 JSONB context）。 */
public record AgentTaskQueueRow(
        UUID id,
        UUID workspaceId,
        UUID issueId,
        UUID agentId,
        String status,
        int priority,
        OffsetDateTime createdAt) {
}