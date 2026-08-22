package io.legion.server.mapper;

import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.legion.server.domain.AgentTaskQueueRow;

@Mapper
public interface AgentTaskQueueMapper {

    /**
     * 入队一行 queued 任务。per-(issue, agent) 的唯一槽（partial unique index
     * idx_one_pending_task_per_issue_agent）把重复入队合并为 0 行受影响——
     * M0 的 coalesce 语义就靠它，无需应用层再查一遍（设计 §3.3 / §4.3）。
     */
    int enqueue(AgentTaskQueueRow row);

    /** 查询该 (issue, agent) 现有 pending 任务（coalesce 后回读用）。 */
    AgentTaskQueueRow findPending(@Param("issueId") UUID issueId, @Param("agentId") UUID agentId);
}