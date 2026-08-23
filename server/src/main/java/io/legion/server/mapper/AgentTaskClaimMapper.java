package io.legion.server.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import io.legion.contracts.AgentTaskRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

/**
 * agent_task_queue 的认领/终态操作（M0-5）。入队路径由 M0-4 的 TaskEnqueue 负责，
 * 本 mapper 只认领与终态化，避免两个任务互相踩。
 */
@Mapper
public interface AgentTaskClaimMapper {

    /** 原子认领一个 queued 任务并置为 dispatched（FOR UPDATE SKIP LOCKED，见 XML）。队列空返回 null。 */
    AgentTaskRow claimNextTask();

    /** 终态 completed；0 行 = 任务已被终态化（幂等语义，设计 §3.5）。 */
    int completeTask(@Param("id") UUID id, @Param("result") JsonNode result);

    /** 终态 failed；0 行 = 已被终态化。 */
    int failTask(@Param("id") UUID id, @Param("error") String error, @Param("failureClass") String failureClass);

    /** 任务归属的 issue（messages/终态 SSE 转发路由用）；未知任务返回 null。 */
    UUID findIssueId(@Param("id") UUID id);

    /** usage 落库（仅 dispatched/running；终态后 0 行 = 丢弃）。 */
    int applyUsage(@Param("id") UUID id,
                   @Param("sessionId") String sessionId,
                   @Param("inputTokens") long inputTokens,
                   @Param("outputTokens") long outputTokens,
                   @Param("costUsdTicks") long costUsdTicks);
}