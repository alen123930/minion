package io.legion.server.realtime;

import java.util.UUID;

import io.legion.contracts.StreamEvent;

/**
 * issue 事件流的进程内广播载体（设计 §4.1/§4.6）：service 经
 * ApplicationEventPublisher 发布，IssueEventHub @EventListener 订阅并转发到
 * 该 issue 的 SseEmitter 们。M1 realtime WS hub 订阅同一事件流，传输层更换
 * 不重写事件路径。
 */
public record IssueStreamEvent(UUID issueId, String type, Object payload) {

    public StreamEvent asStreamEvent() {
        return new StreamEvent(type, payload);
    }
}