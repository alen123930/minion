package io.legion.server.service;

import static io.legion.server.WorkspaceDefaults.DEFAULT_WORKSPACE_ID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.legion.server.domain.AgentTaskQueueRow;
import io.legion.server.domain.IssueRow;
import io.legion.server.mapper.AgentTaskQueueMapper;
import io.legion.server.mapper.IssueMapper;
import io.legion.server.realtime.IssueStreamEvent;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.legion.contracts.AgentTaskDto;

/**
 * 入队路径唯一实现（设计 §4.3）：自动分派（WillEnqueueRun 命中）与手动触发
 * 共用本服务。coalesce 语义由 partial unique index 保证——enqueue 返回 0 行
 * 即已被唯一槽合并，广播 task:queued 只发生在真正入队后（先广播、M0 无 WS
 * 跳过唤醒）。
 *
 * <p>M0-7：入队时把 issue 内容快照成 prompt 存进 context——worker 认领后
 * 只有任务行可用，issue 内容必须随任务携带（与原项目 context.prompt 同构）。
 */
@Service
public class TaskEnqueueService {

    public record Outcome(AgentTaskDto task, boolean coalesced) {
    }

    private final AgentTaskQueueMapper taskMapper;
    private final IssueMapper issueMapper;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;

    public TaskEnqueueService(AgentTaskQueueMapper taskMapper, IssueMapper issueMapper,
                              ApplicationEventPublisher events, ObjectMapper json) {
        this.taskMapper = taskMapper;
        this.issueMapper = issueMapper;
        this.events = events;
        this.json = json;
    }

    @Transactional
    public Outcome enqueue(UUID issueId, UUID agentId) {
        IssueRow issue = issueMapper.findById(issueId);
        AgentTaskQueueRow row = new AgentTaskQueueRow(UUID.randomUUID(), DEFAULT_WORKSPACE_ID,
                issueId, agentId, "queued", 0, promptSnapshot(issue), OffsetDateTime.now());
        int inserted = taskMapper.enqueue(row);
        if (inserted == 1) {
            AgentTaskDto dto = DtoFactory.task(row);
            events.publishEvent(new IssueStreamEvent(issueId, "task:queued", dto));
            return new Outcome(dto, false);
        }
        return new Outcome(DtoFactory.task(taskMapper.findPending(issueId, agentId)), true);
    }

    /** 入队时刻的 prompt 快照：标题 + 描述 + 处理指令。 */
    private ObjectNode promptSnapshot(IssueRow issue) {
        ObjectNode context = json.createObjectNode();
        StringBuilder prompt = new StringBuilder("请处理以下 issue 并给出结论。");
        if (issue != null) {
            prompt.append("\n\n标题：").append(issue.title());
            prompt.append("\n\n描述：")
                    .append(issue.description() == null || issue.description().isBlank()
                            ? "（无）" : issue.description());
        }
        context.put("prompt", prompt.toString());
        return context;
    }
}
