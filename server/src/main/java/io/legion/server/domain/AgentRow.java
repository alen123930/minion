package io.legion.server.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** agent 表行对象（M0 仅用于 assignee 存在性校验与手动触发目标）。 */
public record AgentRow(
        UUID id,
        UUID workspaceId,
        String name,
        String runtimeMode,
        String status,
        int maxConcurrentTasks,
        OffsetDateTime createdAt) {
}