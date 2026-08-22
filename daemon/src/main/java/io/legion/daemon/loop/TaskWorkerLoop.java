package io.legion.daemon.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.legion.contracts.AgentTaskRow;
import io.legion.daemon.client.DaemonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * M0 队列 worker 最简版（设计 §5.1 主调度线程的 poll 语义）。
 * 一次 poll：认领一个 pending 任务 → 状态流转桩（不调用 agent，ClaudeBackend 是后续任务）。
 * 认领/上报的瞬态失败必须吞掉留给下一轮——EmbeddedDaemon 用 scheduleWithFixedDelay
 * 驱动 poll，抛异常会取消后续执行，worker 就静默死掉了。
 */
public class TaskWorkerLoop {

    private static final Logger log = LoggerFactory.getLogger(TaskWorkerLoop.class);

    private final DaemonClient client;
    private final Duration pollInterval;
    private final ObjectMapper json = new ObjectMapper();
    private volatile boolean running = true;

    public TaskWorkerLoop(DaemonClient client, Duration pollInterval) {
        this.client = client;
        this.pollInterval = pollInterval;
    }

    /** 单轮：认领 + 状态流转桩。调度线程（EmbeddedDaemon）按周期调用。 */
    public void poll() {
        AgentTaskRow task;
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
        completeStub(task);
    }

    /** M0 状态流转桩：不调用 agent，直接以 stub result 终态化。 */
    private void completeStub(AgentTaskRow task) {
        ObjectNode result = json.createObjectNode();
        result.put("stub", "m0-worker");
        result.put("note", "state-transition stub, agent 调用未实现");
        try {
            client.complete(task.getId(), result);
        } catch (RuntimeException e) {
            // 完成上报失败：任务已 dispatched，下一轮 claim 只认 queued、且 NOT EXISTS 会挡掉
            // 同 (issue, agent) 的重认领——实际不会重试，任务滞留 dispatched，待 M1 租约恢复兜底。
            log.error("任务 {} 完成上报失败，滞留 dispatched，待 M1 租约恢复", task.getId(), e);
        }
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