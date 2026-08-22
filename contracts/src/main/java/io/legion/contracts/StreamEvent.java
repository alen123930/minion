package io.legion.contracts;

/**
 * SSE 帧载荷（帧协议是自定义 JSON，AGENTS.md）：一条事件 = type + payload。
 * M0 事件类型：connected / issue:updated / comment:created / task:queued。
 */
public record StreamEvent(String type, Object payload) {
}