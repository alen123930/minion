package io.legion.server.realtime;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * issue 详情事件流的 SSE 注册表（设计 §4.6）：Map&lt;issueId, List&lt;SseEmitter&gt;&gt;。
 * 连接断开/超时从注册表移除；每 30s 发一条注释帧保活（代理会掐静默连接）。
 */
@Component
public class IssueEventHub {

    private final ConcurrentMap<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService keepalive =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "issue-stream-keepalive");
                t.setDaemon(true);
                return t;
            });
    private final long keepaliveMillis;
    private final AtomicBoolean keepaliveStarted = new AtomicBoolean();

    public IssueEventHub(@Value("${legion.stream.keepalive-millis:30000}") long keepaliveMillis) {
        this.keepaliveMillis = keepaliveMillis;
    }

    public SseEmitter subscribe(UUID issueId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(issueId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(issueId, emitter));
        emitter.onTimeout(() -> remove(issueId, emitter));
        emitter.onError(e -> remove(issueId, emitter));
        startKeepalive();
        return emitter;
    }

    @EventListener
    public void onStreamEvent(IssueStreamEvent event) {
        forward(event.issueId(), event);
    }

    private void forward(UUID issueId, IssueStreamEvent event) {
        List<SseEmitter> subs = emitters.get(issueId);
        if (subs == null) {
            return;
        }
        for (SseEmitter emitter : subs) {
            try {
                emitter.send(SseEmitter.event().data(event.asStreamEvent()));
            } catch (Exception e) {
                remove(issueId, emitter);
            }
        }
    }

    private void startKeepalive() {
        if (keepaliveMillis > 0 && keepaliveStarted.compareAndSet(false, true)) {
            keepalive.scheduleWithFixedDelay(this::heartbeat, keepaliveMillis, keepaliveMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void heartbeat() {
        emitters.forEach((issueId, subs) -> subs.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception e) {
                remove(issueId, emitter);
            }
        }));
    }

    private void remove(UUID issueId, SseEmitter emitter) {
        List<SseEmitter> subs = emitters.get(issueId);
        if (subs == null) {
            return;
        }
        subs.remove(emitter);
        if (subs.isEmpty()) {
            emitters.remove(issueId);
        }
    }
}