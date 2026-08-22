package io.legion.server.realtime;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * issue 详情事件流的 SSE 注册表（设计 §4.6）：Map&lt;issueId, List&lt;SseEmitter&gt;&gt;。
 * 连接断开/超时从注册表移除；每 30s 发一条注释帧保活（代理会掐静默连接）。
 *
 * <p>订阅/退订必须经 ConcurrentHashMap 的原子 compute：分步 add/remove 会在
 * 并发时让新订阅者加入已脱离 map 的孤儿 list——连接建好却永远收不到事件
 * （forward 查 map 得 null），也永不被清理。保活调度按需启停：最后一个
 * emitter 断开后 shutdown scheduler，不空转。
 */
@Component
public class IssueEventHub {

    private final ConcurrentMap<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final long keepaliveMillis;

    private ScheduledExecutorService keepalive;
    private boolean keepaliveRunning;

    public IssueEventHub(@Value("${legion.stream.keepalive-millis:30000}") long keepaliveMillis) {
        this.keepaliveMillis = keepaliveMillis;
    }

    public SseEmitter subscribe(UUID issueId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.compute(issueId, (k, subs) -> {
            if (subs == null) {
                subs = new CopyOnWriteArrayList<>();
            }
            subs.add(emitter);
            return subs;
        });
        emitter.onCompletion(() -> remove(issueId, emitter));
        emitter.onTimeout(() -> remove(issueId, emitter));
        emitter.onError(e -> remove(issueId, emitter));
        startKeepaliveIfNeeded();
        return emitter;
    }

    /**
     * AFTER_COMMIT 转发：publish 全部在 @Transactional 写路径内，事务提交前同步
     * 广播会让订阅者收到随回滚一起消失的幻影事件。M1 写路径更多后这是硬性要求；
     * M0 全部 publish 均带事务，fallbackExecution 保持默认（无事务则丢弃）。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
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

    private synchronized void startKeepaliveIfNeeded() {
        if (keepaliveMillis <= 0 || keepaliveRunning) {
            return;
        }
        keepalive = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "issue-stream-keepalive");
            t.setDaemon(true);
            return t;
        });
        keepalive.scheduleWithFixedDelay(this::heartbeat, keepaliveMillis, keepaliveMillis, TimeUnit.MILLISECONDS);
        keepaliveRunning = true;
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
        emitters.computeIfPresent(issueId, (k, subs) -> {
            subs.remove(emitter);
            return subs.isEmpty() ? null : subs;
        });
        stopKeepaliveIfIdle();
    }

    private synchronized void stopKeepaliveIfIdle() {
        if (keepaliveRunning && emitters.isEmpty()) {
            keepalive.shutdown();
            keepalive = null;
            keepaliveRunning = false;
        }
    }
}