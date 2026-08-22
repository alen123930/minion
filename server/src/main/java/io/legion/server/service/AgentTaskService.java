package io.legion.server.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.legion.contracts.RunAgentTaskRequest;
import io.legion.contracts.RunAgentTaskResponse;
import io.legion.server.exception.BadRequestException;
import io.legion.server.exception.NotFoundException;
import io.legion.server.mapper.AgentMapper;
import io.legion.server.mapper.IssueMapper;

/**
 * POST /api/agents/{id}/tasks：手动触发一次 agent 运行（M0 的入队入口，设计 §4.2）。
 * 与自动分派共用 TaskEnqueueService，coalesce 语义一致。
 */
@Service
public class AgentTaskService {

    private final AgentMapper agentMapper;
    private final IssueMapper issueMapper;
    private final TaskEnqueueService enqueue;

    public AgentTaskService(AgentMapper agentMapper, IssueMapper issueMapper, TaskEnqueueService enqueue) {
        this.agentMapper = agentMapper;
        this.issueMapper = issueMapper;
        this.enqueue = enqueue;
    }

    @Transactional
    public RunAgentTaskResponse run(UUID agentId, RunAgentTaskRequest req) {
        if (req.issueId() == null) {
            throw new BadRequestException("issue_id is required");
        }
        if (agentMapper.findById(agentId) == null) {
            throw new NotFoundException("agent not found: " + agentId);
        }
        if (issueMapper.findById(req.issueId()) == null) {
            throw new NotFoundException("issue not found: " + req.issueId());
        }
        TaskEnqueueService.Outcome outcome = enqueue.enqueue(req.issueId(), agentId);
        return new RunAgentTaskResponse(outcome.task(), outcome.coalesced());
    }
}