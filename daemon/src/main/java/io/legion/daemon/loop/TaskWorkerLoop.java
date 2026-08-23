package io.legion.daemon.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.legion.contracts.ReportUsageRequest;
import io.legion.contracts.StreamEvent;
import io.legion.contracts.TaskMessagesRequest;
import io.legion.contracts.agent.AgentBackend;
import io.legion.contracts.agent.AgentStreamEvent;
import io.legion.contracts.agent.BackendException;
import io.legion.contracts.agent.ExecOptions;
import io.legion.contracts.agent.ExecRequest;
import io.legion.contracts.agent.Outcome;
import io.legion.contracts.agent.Session;
import io.legion.contracts.agent.TokenUsage;
import io.legion.daemon.client.DaemonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * M0 队列 worker（设计 §5.1/§5.2 的 M0 形态）：认领 → 真实调用 backend →
 * 输出事件流转发 server（SSE 通道）→ usage 先报 → 终态。
 *
 * <p>结算纪律照抄（§5.3）：usage 先于任何 early-return 上报（计费不可漏）；
 * fail-closed——backend 只在显式 Success 时 complete，Failure 一律 fail 并带
 * 稳定 failure_class（reason code 原样透传，禁止字符串解析）。
 *
 * <p>M0 同步执行：poll 在任务终态上报后才返回（内嵌调度线程 scheduleWithFixedDelay
 * 本就不重叠）。M1 换 slot-before-claim + 每任务虚拟线程，此处只动 poll 的编排层。
 */
public class TaskWorkerLoop {

    private static final Logger log = LoggerFactory.getLogger(TaskWorkerLoop.class);

    private final DaemonClient client;
    private final AgentBackend backend;
    private final Duration pollInterval;
    private final ExecOptions execOptions;
    private final ObjectMapper json = new ObjectMapper();
    private volatile boolean running = true;

    public TaskWorkerLoop(DaemonClient client, AgentBackend backend, Duration pollInterval) {
        this(client, backend, pollInterval, ExecOptions.defaults());
    }

    public TaskWorkerLoop(DaemonClient client, AgentBackend backend, Duration pollInterval,
                          ExecOptions execOptions) {
        this.client = client;
        this.backend = backend;
        this.pollInterval = pollInterval;
        this.execOptions = execOptions;
    }

    /** 单轮：认领 + 执行 + 结算。调度线程（EmbeddedDaemon）按周期调用。 */
    public void poll() {
        io.legion.contracts.AgentTaskRow task;
        try {
            task = client.claim();
        } catch (RuntimeException e) {
            log.warn("认领失败，留给下一轮", e);
            return;
        }
        if (task == null) {
            return;
        }
        log.info("认领任务 {} (issue={}, agent={})", task.getId(), task.getIssueId(), task.getAgentId());
        execute(task);
    }

    /**
     * outcome 等待上限：配了 totalTimeout 就取 total+30s 宽限（看门狗杀树后
     * outcome 才完成，等太短会在长任务上提前抛 Timeout 走"滞留 dispatched"，
     * M0 无租约恢复）；未配置回落 15 分钟。硬编码 15m 曾与生产默认 30m 冲突（评审 minor）。
     */
    static Duration outcomeWait(io.legion.contracts.agent.ExecOptions opts) {
        Duration total = opts.totalTimeout();
        return total != null ? total.plus(Duration.ofSeconds(30)) : Duration.ofMinutes(15);
    }

    private void execute(io.legion.contracts.AgentTaskRow task) {        Session session;
        try {
            session = backend.execute(new ExecRequest(promptOf(task), execOptions));
        } catch (BackendException e) {
            // 启动阶段失败：无会话、无 usage 可报，直接终态化
            fail(task, e.getMessage(), e.reason().code());
            return;
        }

        try {
            forwardEvents(task.getId(), session);
            Outcome outcome = session.outcome().get(
                    outcomeWait(execOptions).toMillis(), TimeUnit.MILLISECONDS);
            if (outcome instanceof Outcome.Success success) {
                // usage 先于终态，任何路径都不许跳过（计费不可漏）
                reportUsage(task.getId(), success.usage(), success.sessionId());
                complete(task, success);
            } else {
                Outcome.Failure failure = (Outcome.Failure) outcome;
                reportUsage(task.getId(), failure.usage(), failure.sessionId());
                fail(task, failure.message(), failure.reason().code());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 会话已建立但结算异常/被中断：任务滞留 dispatched，M1 租约恢复兜底
            log.error("任务 {} 结算异常，滞留 dispatched，待 M1 租约恢复", task.getId(), e);
        }
    }

    /** 事件流转发到 server 的 messages 端点（server 再映射 SSE task:message）。 */
    private void forwardEvents(java.util.UUID taskId, Session session) {
        try {
            while (true) {
                AgentStreamEvent event = session.events().poll(15, TimeUnit.MINUTES);
                if (event == null) {
                    throw new IllegalStateException("agent event stream stalled without End");
                }
                if (event instanceof AgentStreamEvent.End) {
                    return;
                }
                StreamEvent frame = frameOf(event);
                if (frame != null) {
                    // 转发是 best-effort transcript：失败只丢实时性不丢正确性
                    try {
                        client.messages(taskId, List.of(frame));
                    } catch (RuntimeException e) {
                        log.warn("任务 {} 事件转发失败（继续）", taskId, e);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while draining agent events", e);
        }
    }

    /** usage 汇总上报：跨模型求和（stream-json 家族是增量桶）。 */
    private void reportUsage(java.util.UUID taskId, Map<String, TokenUsage> usage, String sessionId) {
        long input = 0;
        long output = 0;
        long costTicks = 0;
        for (TokenUsage u : usage.values()) {
            input += u.inputTokens();
            output += u.outputTokens();
            costTicks += u.costUsdTicks();
        }
        // provider 未申报成本时 cost=0 落库——绝不 token×费率折算（AGENTS.md）
        try {
            client.reportUsage(taskId, new ReportUsageRequest(sessionId, input, output, costTicks));
        } catch (RuntimeException e) {
            log.warn("任务 {} usage 上报失败（计费路径，M1 补重试）", taskId, e);
        }
    }

    private void complete(io.legion.contracts.AgentTaskRow task, Outcome.Success success) {
        ObjectNode result = json.createObjectNode();
        result.put("output", success.output());
        if (success.sessionId() != null) {
            result.put("session_id", success.sessionId());
        }
        result.put("provider", backend.provider());
        try {
            client.complete(task.getId(), result);
        } catch (RuntimeException e) {
            log.error("任务 {} 完成上报失败，滞留 dispatched，待 M1 租约恢复", task.getId(), e);
        }
    }

    private void fail(io.legion.contracts.AgentTaskRow task, String error, String failureClass) {
        try {
            client.fail(task.getId(), error, failureClass);
        } catch (RuntimeException e) {
            log.error("任务 {} 失败上报失败，滞留 dispatched，待 M1 租约恢复", task.getId(), e);
        }
    }

    /** 入队时快照进 context.prompt（TaskEnqueueService）；兜底占位防 null。 */
    private String promptOf(io.legion.contracts.AgentTaskRow task) {
        JsonNode context = task.getContext();
        if (context != null) {
            String prompt = context.path("prompt").asText("");
            if (!prompt.isBlank()) {
                return prompt;
            }
        }
        return "Handle the assigned issue.";
    }

    // ------------------------------------------------------------------

    /** backend 事件 → wire 帧（type + payload）。End 哨兵不转发。
     *  JDK 17：switch 模式匹配是 preview，用 instanceof 链（21 后可换回）。 */
    static StreamEvent frameOf(AgentStreamEvent event) {
        if (event instanceof AgentStreamEvent.AssistantText e) {
            return new StreamEvent("assistant_text", Map.of("text", e.text()));
        }
        if (event instanceof AgentStreamEvent.Thinking e) {
            return new StreamEvent("thinking", Map.of("text", e.text()));
        }
        if (event instanceof AgentStreamEvent.ToolUse e) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool", e.tool());
            payload.put("call_id", e.callId());
            if (e.input() != null) {
                payload.put("input", e.input());
            }
            return new StreamEvent("tool_use", payload);
        }
        if (event instanceof AgentStreamEvent.ToolResult e) {
            return new StreamEvent("tool_result", Map.of(
                    "call_id", e.callId(), "output", e.output()));
        }
        if (event instanceof AgentStreamEvent.SystemInfo e) {
            return new StreamEvent("system", Map.of("session_id", e.sessionId()));
        }
        if (event instanceof AgentStreamEvent.Usage e) {
            return new StreamEvent("usage", Map.of("usage", usagePayload(e.usage())));
        }
        if (event instanceof AgentStreamEvent.Log e) {
            return new StreamEvent("log", Map.of("level", e.level(), "message", e.message()));
        }
        return null;    // End 哨兵
    }

    private static Map<String, Object> usagePayload(Map<String, TokenUsage> usage) {
        Map<String, Object> out = new LinkedHashMap<>();
        usage.forEach((model, u) -> {
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("input_tokens", u.inputTokens());
            bucket.put("output_tokens", u.outputTokens());
            bucket.put("cache_read_tokens", u.cacheReadTokens());
            bucket.put("cache_write_tokens", u.cacheWriteTokens());
            bucket.put("cost_usd_ticks", u.costUsdTicks());
            out.put(model, bucket);
        });
        return out;
    }

    /** 独立循环（M1 daemon 进程用）；M0 内嵌由 EmbeddedDaemon 的调度器驱动 poll。 */
    public void run() {
        while (running) {
            poll();
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public void stop() {
        running = false;
    }
}
