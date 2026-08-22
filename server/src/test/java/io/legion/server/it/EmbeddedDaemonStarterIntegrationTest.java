package io.legion.server.it;

import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EmbeddedDaemonStarter 的接线测试：开启内嵌 worker + 短轮询间隔，
 * 种一个 queued 任务后 worker 应自动认领并终态化（真实调度链路，非手动 poll）。
 *
 * <p>基座 {@link AbstractIntegrationTest} 已带 @DirtiesContext(AFTER_CLASS)：类结束即销毁
 * 上下文，常驻轮询线程随之 @PreDestroy 停止，不会在 suite 期间抢共享容器里的任务。
 */
@TestPropertySource(properties = {
        "legion.daemon.embedded.enabled=true",
        "legion.daemon.embedded.poll-interval=100ms"
})
class EmbeddedDaemonStarterIntegrationTest extends TaskQueueIntegrationTestBase {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    @Test
    void embeddedWorkerAutoClaimsAndCompletesSeededTask() {
        Seed s = seed();
        UUID taskId = enqueue(s, "queued");

        awaitCondition(Duration.ofSeconds(15), () -> {
            Integer done = jdbc.queryForObject(
                    "SELECT count(*) FROM agent_task_queue WHERE id = ? AND status = 'completed'",
                    Integer.class, taskId);
            return done != null && done == 1;
        });

        assertThat(statusOf(taskId)).isEqualTo("completed");
        String result = jdbc.queryForObject(
                "SELECT result::text FROM agent_task_queue WHERE id = ?", String.class, taskId);
        assertThat(result).contains("m0-worker");
    }
}