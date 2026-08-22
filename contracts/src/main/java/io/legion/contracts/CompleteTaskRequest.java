package io.legion.contracts;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 终态上报：completed 请求体。result 落 {@code agent_task_queue.result} (jsonb)。
 * fail-closed 纪律（设计 §3.5）：只有显式 completed 算成功，缺 result 不补成功——
 * M0 状态流转桩由 worker 显式携带 stub result。
 */
public record CompleteTaskRequest(JsonNode result) {
}