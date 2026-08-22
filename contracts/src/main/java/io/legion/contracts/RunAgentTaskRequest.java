package io.legion.contracts;

import java.util.UUID;

/** 手动触发一次 agent 运行（M0 的入队入口，设计 §4.2）：为指定 issue 入队一行任务。 */
public record RunAgentTaskRequest(UUID issueId) {
}