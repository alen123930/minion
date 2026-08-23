package io.legion.contracts;

import java.util.List;

/**
 * POST /api/daemon/tasks/{id}/messages 载荷（设计 §4.2）：
 * daemon 把 backend 输出事件流按批次上报，server 映射成 SSE task:message 帧转发。
 * 事件即 SSE 帧三元组（type + payload），与 web 面共用一个形状。
 */
public record TaskMessagesRequest(List<StreamEvent> events) {
}
