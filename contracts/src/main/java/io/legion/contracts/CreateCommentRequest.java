package io.legion.contracts;

import java.util.UUID;

/** 发评论请求。author 缺省由 server 补默认值（同 CreateIssueRequest）。 */
public record CreateCommentRequest(
        String authorType,
        UUID authorId,
        String body) {
}