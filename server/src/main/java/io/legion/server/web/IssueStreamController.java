package io.legion.server.web;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.legion.contracts.StreamEvent;
import io.legion.server.exception.NotFoundException;
import io.legion.server.mapper.IssueMapper;
import io.legion.server.realtime.IssueEventHub;

/**
 * GET /api/issues/{id}/stream：issue 详情事件流（SSE，设计 §4.2/§4.6）。
 * 连接即发 connected 帧，此后 task:queued / comment:created / issue:updated
 * 经 IssueEventHub 转发；每 30s 注释帧保活由 hub 统一调度。
 */
@RestController
@RequestMapping("/api/issues/{id}/stream")
public class IssueStreamController {

    private final IssueEventHub hub;
    private final IssueMapper issueMapper;

    public IssueStreamController(IssueEventHub hub, IssueMapper issueMapper) {
        this.hub = hub;
        this.issueMapper = issueMapper;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID id) {
        if (issueMapper.findById(id) == null) {
            throw new NotFoundException("issue not found: " + id);
        }
        SseEmitter emitter = hub.subscribe(id);
        try {
            emitter.send(SseEmitter.event().data(
                    new StreamEvent("connected", Map.of("issue_id", id.toString()))));
        } catch (IOException e) {
            throw new IllegalStateException("failed to open issue stream: " + id, e);
        }
        return emitter;
    }
}