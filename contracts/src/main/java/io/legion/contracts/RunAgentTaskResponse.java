package io.legion.contracts;

/**
 * 手动触发结果。coalesced=true 表示该 (issue, agent) 已有 pending 任务，
 * 被 partial unique index 合并为同一槽（idx_one_pending_task_per_issue_agent），
 * 未新增行、未广播 task:queued。
 */
public record RunAgentTaskResponse(AgentTaskDto task, boolean coalesced) {
}