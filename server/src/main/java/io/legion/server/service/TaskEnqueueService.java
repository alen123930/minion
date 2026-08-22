package io.legion.server.service;

import static io.legion.server.WorkspaceDefaults.DEFAULT_WORKSPACE_ID;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.legion.contracts.AgentTaskDto;
import io.legion.server.domain.AgentTaskQueueRow;
import io.legion.server.mapper.AgentTaskQueueMapper;
import io.legion.server.realtime.IssueStreamEvent;

/**
 * 入队路径唯一实现（设计 §4.3）：自动分派（WillEnqueueRun 命中）与手动触发
 * 共用本服务。coalesce 语义由 partial unique index 保证——enqueue 返回 0 行
 * 即已被唯一槽合并，广播 task:queued 只发生在真正入队后（先广播、M0 无 WS
 * 跳过唤醒）。
 */
@Service
public class TaskEnqueueService {

    public record Outcome(AgentTaskDto task, boolean coalesced) {
    }

    private final AgentTaskQueueMapper taskMapper;
    private final ApplicationEventPublisher events;

    public TaskEnqueueService(AgentTaskQueueMapper taskMapper, ApplicationEventPublisher events) {
        this.taskMapper = taskMapper;
        this.events = events;
    }

    @Transactional
    public Outcome enqueue(UUID issueId, UUID agentId) {
        AgentTaskQueueRow row = new AgentTaskQueueRow(UUID.randomUUID(), DEFAULT_WORKSPACE_ID,
                issueId, agentId, "queued", 0, OffsetDateTime.now());
        int inserted = taskMapper.enqueue(row);
        if (inserted == 1) {
            AgentTaskDto dto = DtoFactory.task(row);
            events.publishEvent(new IssueStreamEvent(issueId, "task:queued", dto));
            return new Outcome(dto, false);
        }
        return new Outcome(DtoFactory.task(taskMapper.findPending(issueId, agentId)), true);
    }
}