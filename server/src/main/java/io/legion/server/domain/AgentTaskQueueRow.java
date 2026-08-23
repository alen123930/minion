package io.legion.server.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

/** agent_task_queue 行对象（M0 只投影入队需要的列，终态列在 claim mapper）。 */
public record AgentTaskQueueRow(
        UUID id,
        UUID workspaceId,
        UUID issueId,
        UUID agentId,
        String status,
        int priority,
        JsonNode context,
        OffsetDateTime createdAt) {

    /** 兼容无 context 的旧构造（findPending 的列清单不含 context）。 */
    public AgentTaskQueueRow(UUID id, UUID workspaceId, UUID issueId, UUID agentId,
                             String status, int priority, OffsetDateTime createdAt) {
        this(id, workspaceId, issueId, agentId, status, priority, null, createdAt);
    }
}
