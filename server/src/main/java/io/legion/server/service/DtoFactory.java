package io.legion.server.service;

import io.legion.contracts.AgentTaskDto;
import io.legion.contracts.CommentDto;
import io.legion.contracts.IssueDto;
import io.legion.server.domain.AgentTaskQueueRow;
import io.legion.server.domain.CommentRow;
import io.legion.server.domain.IssueRow;

/** 行对象 → 契约 DTO 的唯一转换点（controller 不见行对象，设计 §4.1）。 */
public final class DtoFactory {

    private DtoFactory() {
    }

    public static IssueDto issue(IssueRow r) {
        return new IssueDto(r.id(), r.workspaceId(), r.title(), r.description(), r.status(),
                r.priority(), r.assigneeType(), r.assigneeId(), r.creatorType(), r.creatorId(),
                r.createdAt(), r.updatedAt());
    }

    public static CommentDto comment(CommentRow r) {
        return new CommentDto(r.id(), r.issueId(), r.authorType(), r.authorId(), r.body(), r.createdAt());
    }

    public static AgentTaskDto task(AgentTaskQueueRow r) {
        return new AgentTaskDto(r.id(), r.workspaceId(), r.issueId(), r.agentId(),
                r.status(), r.priority(), r.createdAt());
    }
}