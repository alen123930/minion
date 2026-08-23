package io.legion.contracts.agent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;

/**
 * 一次执行的会话句柄（设计 §6.1）：流式事件队列 + 终态 future。
 * 不引入 reactive 框架——BlockingQueue + CompletableFuture 与虚拟线程配合最直白。
 */
public record Session(BlockingQueue<AgentStreamEvent> events, CompletableFuture<Outcome> outcome) {
}
