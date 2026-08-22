package io.legion.server.service;

import static io.legion.server.WorkspaceDefaults.DEFAULT_MEMBER_ID;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.legion.contracts.CommentDto;
import io.legion.contracts.CreateCommentRequest;
import io.legion.server.domain.CommentRow;
import io.legion.server.exception.BadRequestException;
import io.legion.server.exception.NotFoundException;
import io.legion.server.mapper.CommentMapper;
import io.legion.server.mapper.IssueMapper;
import io.legion.server.realtime.IssueStreamEvent;

/** 评论写路径：M0 只落库 + 广播 comment:created，不入队（M1 评论触发走 WillEnqueueRun 全家桶）。 */
@Service
public class CommentService {

    private final CommentMapper commentMapper;
    private final IssueMapper issueMapper;
    private final ApplicationEventPublisher events;

    public CommentService(CommentMapper commentMapper, IssueMapper issueMapper, ApplicationEventPublisher events) {
        this.commentMapper = commentMapper;
        this.issueMapper = issueMapper;
        this.events = events;
    }

    @Transactional
    public CommentDto create(UUID issueId, CreateCommentRequest req) {
        if (issueMapper.findById(issueId) == null) {
            throw new NotFoundException("issue not found: " + issueId);
        }
        if (req.body() == null || req.body().isBlank()) {
            throw new BadRequestException("body is required");
        }
        String authorType = (req.authorType() == null || req.authorType().isBlank()) ? "member" : req.authorType();
        if (!"member".equals(authorType) && !"agent".equals(authorType)) {
            throw new BadRequestException("author_type must be 'member' or 'agent'");
        }
        if ("agent".equals(authorType) && req.authorId() == null) {
            throw new BadRequestException("author_id is required for author_type 'agent'");
        }
        UUID authorId = req.authorId() == null ? DEFAULT_MEMBER_ID : req.authorId();

        // 评论算 issue 活动：同事务 bump issue.updated_at（原项目 comment.sql 的
        // touch 语义，"Updated date" 排序与 daemon GC TTL 都读它，属 load-bearing）
        commentMapper.touchIssueUpdatedAt(issueId);
        CommentRow row = new CommentRow(UUID.randomUUID(), issueId, authorType, authorId, req.body(), OffsetDateTime.now());
        commentMapper.insert(row);
        CommentDto dto = DtoFactory.comment(row);
        events.publishEvent(new IssueStreamEvent(issueId, "comment:created", dto));
        return dto;
    }
}