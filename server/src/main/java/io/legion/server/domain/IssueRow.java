package io.legion.server.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** issue 表行对象；仅 mapper/service 内部使用，controller 一律经 DTO（设计 §4.1）。 */
public record IssueRow(
        UUID id,
        UUID workspaceId,
        String title,
        String description,
        String status,
        String priority,
        String assigneeType,
        UUID assigneeId,
        String creatorType,
        UUID creatorId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}