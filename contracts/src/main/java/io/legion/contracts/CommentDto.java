package io.legion.contracts;

import java.time.OffsetDateTime;
import java.util.UUID;

/** comment 的对外契约行。 */
public record CommentDto(
        UUID id,
        UUID issueId,
        String authorType,
        UUID authorId,
        String body,
        OffsetDateTime createdAt) {
}