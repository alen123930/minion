package io.legion.server.it;

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
 * worker（daemon 模块）↔ server（HTTP）全链路：认领 → 状态流转桩 → 终态落库。
 * 手动驱动一次 poll，确定性验证 daemon 对 server 的调用走 localhost HTTP 且状态机完整。
 */
class EmbeddedWorkerLoopIntegrationTest extends TaskQueueIntegrationTestBase {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    @Test
    void workerPollClaimsThenCompletesStub() {
        Seed s = seed();
        UUID taskId = enqueue(s, "queued");

        TaskWorkerLoop loop = new TaskWorkerLoop(
                new DaemonClient(URI.create(baseUrl())), Duration.ofMillis(10));
        loop.poll();

        assertThat(countByStatus("queued")).isZero();
        assertThat(countByStatus("completed")).isEqualTo(1);
        String result = jdbc.queryForObject(
                "SELECT result::text FROM agent_task_queue WHERE id = ?", String.class, taskId);
        assertThat(result).contains("m0-worker");
        assertThat(jdbc.queryForObject(
                "SELECT dispatched_at IS NOT NULL FROM agent_task_queue WHERE id = ?",
                Boolean.class, taskId)).isTrue();
    }
}