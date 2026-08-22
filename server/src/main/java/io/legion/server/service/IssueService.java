package io.legion.server.service;

import static io.legion.server.WorkspaceDefaults.DEFAULT_MEMBER_ID;
import static io.legion.server.WorkspaceDefaults.DEFAULT_WORKSPACE_ID;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.legion.contracts.CreateIssueRequest;
import io.legion.contracts.IssueDetailDto;
import io.legion.contracts.IssueDto;
import io.legion.server.domain.IssueRow;
import io.legion.server.exception.BadRequestException;
import io.legion.server.exception.NotFoundException;
import io.legion.server.mapper.AgentMapper;
import io.legion.server.mapper.CommentMapper;
import io.legion.server.mapper.IssueMapper;
import io.legion.server.realtime.IssueStreamEvent;

/**
 * issue 写路径：create/分派 统一走 WillEnqueueRun 单一谓词决定是否入队
 * （设计 §4.3 的 MUL-3375 教训：谓词散落各处会 drift）。M0 只实现
 * "assign 给 agent 且状态非 backlog"一条规则。
 */
@Service
public class IssueService {

    /** 一次 issue 写操作变化摘要（M0 只有 create 路径；字段留给 M1 分派/状态迁移）。 */
    public record IssueChange(boolean isCreate, boolean assigneeChanged, boolean statusChanged, String prevStatus) {
    }

    /** WillEnqueueRun 的决策产物：为谁入队。 */
    public record RunTrigger(UUID issueId, UUID agentId) {
    }

    /**
     * 状态/优先级白名单（参照原项目 issuestatus 7 内建 key 与 validIssuePriorities；
     * M0 无自定义状态目录表，白名单即全集，避免把语义垃圾当可执行）。
     */
    private static final Set<String> VALID_STATUSES =
            Set.of("backlog", "todo", "in_progress", "in_review", "done", "blocked", "cancelled");
    private static final Set<String> VALID_PRIORITIES =
            Set.of("urgent", "high", "medium", "low", "none");

    private final IssueMapper issueMapper;
    private final CommentMapper commentMapper;
    private final AgentMapper agentMapper;
    private final TaskEnqueueService enqueue;
    private final ApplicationEventPublisher events;

    public IssueService(IssueMapper issueMapper, CommentMapper commentMapper, AgentMapper agentMapper,
                        TaskEnqueueService enqueue, ApplicationEventPublisher events) {
        this.issueMapper = issueMapper;
        this.commentMapper = commentMapper;
        this.agentMapper = agentMapper;
        this.enqueue = enqueue;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<IssueDto> list() {
        return issueMapper.listAll().stream().map(DtoFactory::issue).toList();
    }

    @Transactional(readOnly = true)
    public IssueDetailDto get(UUID id) {
        IssueRow issue = issueMapper.findById(id);
        if (issue == null) {
            throw new NotFoundException("issue not found: " + id);
        }
        return new IssueDetailDto(
                DtoFactory.issue(issue),
                commentMapper.listByIssue(id).stream().map(DtoFactory::comment).toList());
    }

    @Transactional
    public IssueDto create(CreateIssueRequest req) {
        if (req.title() == null || req.title().isBlank()) {
            throw new BadRequestException("title is required");
        }
        // 原项目默认值：status todo、priority none（参照 issue.go CreateIssue）
        String status = (req.status() == null || req.status().isBlank()) ? "todo" : req.status();
        String priority = (req.priority() == null || req.priority().isBlank()) ? "none" : req.priority();
        if (!VALID_STATUSES.contains(status)) {
            throw new BadRequestException("status must be one of " + VALID_STATUSES);
        }
        if (!VALID_PRIORITIES.contains(priority)) {
            throw new BadRequestException("priority must be one of " + VALID_PRIORITIES);
        }
        String assigneeType = req.assigneeType();
        UUID assigneeId = req.assigneeId();
        validateAssignee(assigneeType, assigneeId);

        OffsetDateTime now = OffsetDateTime.now();
        IssueRow row = new IssueRow(UUID.randomUUID(), DEFAULT_WORKSPACE_ID, req.title(), req.description(),
                status, priority, assigneeType, assigneeId, "member", DEFAULT_MEMBER_ID, now, now);
        issueMapper.insert(row);

        willEnqueueRun(row, new IssueChange(true, false, false, null))
                .ifPresent(t -> enqueue.enqueue(t.issueId(), t.agentId()));

        IssueDto dto = DtoFactory.issue(row);
        events.publishEvent(new IssueStreamEvent(row.id(), "issue:updated", dto));
        return dto;
    }

    /** WillEnqueueRun：单一路径决定"这次写是否会起一个 agent 运行"，与入队实现同源。 */
    Optional<RunTrigger> willEnqueueRun(IssueRow issue, IssueChange change) {
        // create 路径 validateAssignee 已确认 agent 存在（否则 400），此查询主要是
        // 谓词自足（纯函数可单测）+ 为 M1 状态迁移/重分派路径兜底；M0 create 恒命中。
        boolean agentExists = issue.assigneeId() != null && agentMapper.findById(issue.assigneeId()) != null;
        return decideEnqueue(issue, change, agentExists);
    }

    /**
     * M0 单规则的纯决策（无 IO，可单测）：assign 给 agent 且状态非 backlog。
     * backlog 是停车场——预分派不触发，移出 backlog 由 M1 的状态迁移路径触发。
     */
    static Optional<RunTrigger> decideEnqueue(IssueRow issue, IssueChange change, boolean agentExists) {
        if (issue.assigneeType() == null || !"agent".equals(issue.assigneeType()) || issue.assigneeId() == null) {
            return Optional.empty();
        }
        if ("backlog".equals(issue.status())) {
            return Optional.empty();
        }
        boolean triggered = change.isCreate()
                || change.assigneeChanged()
                || (change.statusChanged() && "backlog".equals(change.prevStatus()));
        if (!triggered || !agentExists) {
            return Optional.empty();
        }
        return Optional.of(new RunTrigger(issue.id(), issue.assigneeId()));
    }

    private void validateAssignee(String assigneeType, UUID assigneeId) {
        if (assigneeType == null && assigneeId == null) {
            return;
        }
        if (assigneeType == null || assigneeId == null) {
            throw new BadRequestException("assignee_type and assignee_id must be provided together");
        }
        if (!"member".equals(assigneeType) && !"agent".equals(assigneeType)) {
            throw new BadRequestException("assignee_type must be 'member' or 'agent'");
        }
        if ("agent".equals(assigneeType) && agentMapper.findById(assigneeId) == null) {
            throw new BadRequestException("assignee_id does not refer to an agent");
        }
    }
}