package io.legion.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.legion.contracts.AgentTaskRow;
import io.legion.contracts.ReportUsageRequest;
import io.legion.contracts.StreamEvent;
import io.legion.server.mapper.AgentTaskClaimMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 认领与终态化的事务边界（设计 §3.4）。M0 是单语句原子操作，@Transactional 是
 * M1 的缝：容量检查 + 认领要在同一事务（先锁 agent 行再认领）。
 *
 * <p>M0-7 增补：messages 转发（SSE task:message）与 usage 落库。终态化同时广播
 * task:completed / task:failed 帧——web 面的"结论"到达路径。
 */
@Service
public class AgentTaskClaimService {

    private final AgentTaskClaimMapper mapper;
    private final ApplicationEventPublisher events;

    public AgentTaskClaimService(AgentTaskClaimMapper mapper, ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.events = events;
    }

    @Transactional
    public AgentTaskRow claim() {
        return mapper.claimNextTask();
    }

    @Transactional
    public boolean complete(UUID id, JsonNode result) {
        UUID issueId = mapper.findIssueId(id);
        int applied = mapper.completeTask(id, result);
        if (applied > 0 && issueId != null) {
            events.publishEvent(new io.legion.server.realtime.IssueStreamEvent(
                    issueId, "task:completed",
                    Map.of("task_id", id.toString(), "result", result)));
        }
        return applied > 0;
    }

    @Transactional
    public boolean fail(UUID id, String error, String failureClass) {
        UUID issueId = mapper.findIssueId(id);
        int applied = mapper.failTask(id, error, failureClass);
        if (applied > 0 && issueId != null) {
            events.publishEvent(new io.legion.server.realtime.IssueStreamEvent(
                    issueId, "task:failed",
                    Map.of("task_id", id.toString(), "failure_class", failureClass)));
        }
        return applied > 0;
    }

    /**
     * daemon 转发的执行事件批次 → SSE task:message 帧（设计 §4.6）。
     * 空事务承载 AFTER_COMMIT 语义：无事务发布会被 hub 静默丢弃。
     */
    @Transactional
    public void forwardMessages(UUID id, List<StreamEvent> frames) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        UUID issueId = mapper.findIssueId(id);
        if (issueId == null) {
            return;
        }
        for (StreamEvent frame : frames) {
            events.publishEvent(new io.legion.server.realtime.IssueStreamEvent(
                    issueId, "task:message",
                    Map.of("task_id", id.toString(), "event", frame)));
        }
    }

    /** usage 落库：仅 dispatched/running 生效（终态后到达的上报丢弃，幂等）。 */
    @Transactional
    public boolean recordUsage(UUID id, ReportUsageRequest usage) {
        return mapper.applyUsage(id, usage.sessionId(),
                usage.inputTokens(), usage.outputTokens(), usage.costUsdTicks()) > 0;
    }
}
