package io.legion.server.it;

import io.legion.contracts.agent.BackendFailureReason;
import io.legion.daemon.agent.FakeBackend;
import io.legion.daemon.client.DaemonClient;
import io.legion.daemon.loop.TaskWorkerLoop;
import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * worker（daemon 模块）↔ server（HTTP）全链路（M0-7 起真实调用 backend）：
 * 认领 → FakeBackend 执行 → messages 转发 → usage 先报 → 终态落库。
 * 手动驱动一次 poll，确定性验证 daemon 对 server 的调用走 localhost HTTP。
 */
class EmbeddedWorkerLoopIntegrationTest extends TaskQueueIntegrationTestBase {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    private TaskWorkerLoop loop(AgentBackendSupplier backend) {
        return new TaskWorkerLoop(
                new DaemonClient(URI.create(baseUrl())), backend.get(), Duration.ofMillis(10));
    }

    @FunctionalInterface
    private interface AgentBackendSupplier {
        io.legion.contracts.agent.AgentBackend get();
    }

    @Test
    void workerRunsFakeBackendToCompletionWithUsage() {
        // API 入队（默认 workspace）：prompt 快照随任务携带，FakeBackend echo 回来
        UUID agentId = insertAgent("worker-e2e-agent");
        UUID issueId = insertIssue("worker e2e issue");
        jdbc.update("UPDATE issue SET description = ? WHERE id = ?",
                "describe the plan", issueId);
        rest.postForEntity("/api/agents/{id}/tasks",
                java.util.Map.of("issue_id", issueId.toString()), String.class, agentId);
        UUID taskId = jdbc.queryForObject(
                "SELECT id FROM agent_task_queue WHERE issue_id = ? AND agent_id = ?",
                java.util.UUID.class, issueId, agentId);

        loop(FakeBackend::new).poll();

        assertThat(countByStatus("queued")).isZero();
        assertThat(countByStatus("completed")).isEqualTo(1);
        String result = jdbc.queryForObject(
                "SELECT result::text FROM agent_task_queue WHERE id = ?", String.class, taskId);
        assertThat(result).contains("echo:");
        assertThat(result).contains("describe the plan");
        // PG jsonb 文本化带空格（"provider": "fake"），走解析断言
        assertThat(jdbc.queryForObject(
                "SELECT result->>'provider' FROM agent_task_queue WHERE id = ?",
                String.class, taskId)).isEqualTo("fake");
        // usage 落库：FakeBackend 固定用量 17/9，cost ticks 原样透传不折算
        assertThat(jdbc.queryForObject(
                "SELECT input_tokens FROM agent_task_queue WHERE id = ?",
                Integer.class, taskId)).isEqualTo(17);
        assertThat(jdbc.queryForObject(
                "SELECT output_tokens FROM agent_task_queue WHERE id = ?",
                Integer.class, taskId)).isEqualTo(9);
        assertThat(jdbc.queryForObject(
                "SELECT cost_usd_ticks FROM agent_task_queue WHERE id = ?",
                Long.class, taskId)).isEqualTo(1_234_500_000L);
        assertThat(jdbc.queryForObject(
                "SELECT session_id FROM agent_task_queue WHERE id = ?",
                String.class, taskId)).isEqualTo("fake-session");
        assertThat(jdbc.queryForObject(
                "SELECT dispatched_at IS NOT NULL FROM agent_task_queue WHERE id = ?",
                Boolean.class, taskId)).isTrue();
    }

    @Test
    void workerFailingBackendMarksTaskFailedWithStableClass() {
        Seed s = seed();
        UUID taskId = enqueue(s, "queued");

        loop(() -> FakeBackend.failing(
                BackendFailureReason.NO_RESULT, "stream ended without terminal result")).poll();

        assertThat(statusOf(taskId)).isEqualTo("failed");
        assertThat(jdbc.queryForObject(
                "SELECT failure_class FROM agent_task_queue WHERE id = ?",
                String.class, taskId)).isEqualTo("no_result");
        assertThat(jdbc.queryForObject(
                "SELECT error FROM agent_task_queue WHERE id = ?",
                String.class, taskId)).contains("without terminal result");
    }

    /**
     * usage 先于 early-return（NIMI-14，参照 daemon.go:5086）：结算异常
     * （outcome 异常完成）时已产生的 usage 必须落库，任务滞留 dispatched，
     * 不许 complete/fail 冒充终态。
     */
    @Test
    void settlementExceptionStillPersistsProducedUsage() {
        Seed s = seed();
        UUID taskId = enqueue(s, "queued");

        io.legion.contracts.agent.TokenUsage produced =
                new io.legion.contracts.agent.TokenUsage(31, 13, 0, 0, 777_000_000L);
        loop(() -> new io.legion.contracts.agent.AgentBackend() {
            @Override
            public String provider() {
                return "exploding";
            }

            @Override
            public io.legion.contracts.agent.Session execute(
                    io.legion.contracts.agent.ExecRequest request) {
                var events = new java.util.concurrent.LinkedBlockingQueue<io.legion.contracts.agent.AgentStreamEvent>();
                var outcome = new java.util.concurrent.CompletableFuture<io.legion.contracts.agent.Outcome>();
                Thread runner = new Thread(() -> {
                    events.offer(new io.legion.contracts.agent.AgentStreamEvent.SystemInfo("err-session"));
                    events.offer(new io.legion.contracts.agent.AgentStreamEvent.Usage(
                            java.util.Map.of("boom-model", produced)));
                    events.offer(new io.legion.contracts.agent.AgentStreamEvent.End());
                    outcome.completeExceptionally(new IllegalStateException("pump thread died"));
                }, "legion-exploding-agent");
                runner.setDaemon(true);
                runner.start();
                return new io.legion.contracts.agent.Session(events, outcome);
            }
        }).poll();

        assertThat(statusOf(taskId)).isEqualTo("dispatched");
        assertThat(jdbc.queryForObject(
                "SELECT input_tokens FROM agent_task_queue WHERE id = ?",
                Integer.class, taskId)).isEqualTo(31);
        assertThat(jdbc.queryForObject(
                "SELECT output_tokens FROM agent_task_queue WHERE id = ?",
                Integer.class, taskId)).isEqualTo(13);
        assertThat(jdbc.queryForObject(
                "SELECT cost_usd_ticks FROM agent_task_queue WHERE id = ?",
                Long.class, taskId)).isEqualTo(777_000_000L);
        assertThat(jdbc.queryForObject(
                "SELECT session_id FROM agent_task_queue WHERE id = ?",
                String.class, taskId)).isEqualTo("err-session");
    }
}
