package io.legion.contracts;

/**
 * daemon 认领协议响应：task 为 null 表示队列为空（M0 单任务认领）。
 * M1 改批量后此 DTO 进化为任务列表，端点路径不变（设计 §4.2）。
 */
public record ClaimTaskResponse(AgentTaskRow task) {
}