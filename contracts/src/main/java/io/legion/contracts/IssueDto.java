package io.legion.contracts;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * issue 的对外契约行（M0 web 面返回形态，蛇形 JSON 由 server 的
 * Jackson SNAKE_CASE 策略统一输出）。
 */
public record IssueDto(
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