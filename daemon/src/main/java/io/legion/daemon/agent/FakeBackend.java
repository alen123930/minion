package io.legion.daemon.agent;

import io.legion.contracts.agent.AgentBackend;
import io.legion.contracts.agent.AgentStreamEvent;
import io.legion.contracts.agent.BackendFailureReason;
import io.legion.contracts.agent.ExecRequest;
import io.legion.contracts.agent.Outcome;
import io.legion.contracts.agent.Session;
import io.legion.contracts.agent.TokenUsage;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * M0 联调假 agent（设计 §6.4）：echo 实现——分几条事件、可注入失败。
 * 所有默认测试禁止执行真实 agent CLI；真 CLI 冒烟由
 * {@code LEGION_RUN_REAL_AGENT_SMOKE=1} 门控（对应原项目 agentintegration
 * build tag 的隔离纪律）。
 */
public final class FakeBackend implements AgentBackend {

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    /** 假用量：固定非零，验证 usage 上报路径贯通（含 cost ticks 原样透传）。 */
    static final TokenUsage FAKE_USAGE = new TokenUsage(17, 9, 0, 0, 1_234_500_000L);

    private final Outcome forcedFailure;

    public FakeBackend() {
        this(null);
    }

    private FakeBackend(Outcome forcedFailure) {
        this.forcedFailure = forcedFailure;
    }

    /** 注入失败终态的实例。 */
    public static FakeBackend failing(BackendFailureReason reason, String message) {
        return new FakeBackend(new Outcome.Failure(reason, message,
                Map.of("fake-model", FAKE_USAGE), "fake-session"));
    }

    @Override
    public String provider() {
        return "fake";
    }

    @Override
    public Session execute(ExecRequest request) {
        LinkedBlockingQueue<AgentStreamEvent> events = new LinkedBlockingQueue<>();
        CompletableFuture<Outcome> outcome = new CompletableFuture<>();
        Thread runner = new Thread(() -> {
            try {
                // 分几条事件 + 小延时，模拟流式节奏
                String prompt = request.prompt() == null ? "" : request.prompt();
                for (String chunk : List.of("echo:", " " + prompt)) {
                    events.offer(new AgentStreamEvent.AssistantText(chunk));
                    Thread.sleep(20);
                }
                events.offer(new AgentStreamEvent.Usage(usageSnapshot()));
                Outcome result = forcedFailure != null ? forcedFailure
                        : new Outcome.Success("echo: " + prompt, usageSnapshot(), "fake-session");
                events.offer(new AgentStreamEvent.End());
                outcome.complete(result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                outcome.complete(new Outcome.Failure(BackendFailureReason.CANCELLED,
                        "interrupted", Map.of(), null));
                events.offer(new AgentStreamEvent.End());
            }
        }, "legion-fake-agent-" + THREAD_SEQ.incrementAndGet());
        runner.setDaemon(true);
        runner.start();
        return new Session(events, outcome);
    }

    private Map<String, TokenUsage> usageSnapshot() {
        return Map.of("fake-model", FAKE_USAGE);
    }
}
