package io.legion.server.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** comment 表行对象。 */
public record CommentRow(
        UUID id,
        UUID issueId,
        String authorType,
        UUID authorId,
        String body,
        OffsetDateTime createdAt) {
}