package io.legion.server.it;

import io.legion.contracts.AgentTaskRow;
import io.legion.daemon.client.DaemonClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M0-5 的必测项：两个 worker 同时认领同一行，只有一个成功（设计 §9.2 手法一）。
 * 用 pg_sleep 触发器把认领竞态窗口强行拉开——两个 HTTP 认领必然在窗口内重叠，
 * 断言恰好一个赢家 + DB 终态恰好一行 dispatched。
 */
class TaskClaimConcurrencyIntegrationTest extends TaskQueueIntegrationTestBase {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    @Test
    void twoWorkersClaimingSameQueuedRowOnlyOneSucceeds() throws Exception {
        Seed s = seed();
        UUID taskId = enqueue(s, "queued");
        installClaimRaceTrigger();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            DaemonClient c1 = new DaemonClient(URI.create(baseUrl()));
            DaemonClient c2 = new DaemonClient(URI.create(baseUrl()));
            CountDownLatch start = new CountDownLatch(1);

            Future<AgentTaskRow> f1 = pool.submit(() -> {
                start.await();
                return c1.claim();
            });
            Future<AgentTaskRow> f2 = pool.submit(() -> {
                start.await();
                return c2.claim();
            });
            start.countDown();

            List<AgentTaskRow> winners = Stream.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS))
                    .filter(Objects::nonNull).toList();

            assertThat(winners)
                    .as("queued=%s dispatched=%s completed=%s",
                            countByStatus("queued"), countByStatus("dispatched"), countByStatus("completed"))
                    .hasSize(1);
            assertThat(winners.get(0).getId()).isEqualTo(taskId);
            assertThat(countByStatus("queued")).isZero();
            assertThat(countByStatus("dispatched")).isEqualTo(1);
        } finally {
            dropClaimRaceTrigger();
            pool.shutdownNow();
        }
    }

    private void installClaimRaceTrigger() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION legion_claim_race_sleep() RETURNS trigger AS $$
                BEGIN
                    IF OLD.status = 'queued' AND NEW.status = 'dispatched' THEN
                        PERFORM pg_sleep(0.4);
                    END IF;
                    RETURN NEW;
                END; $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER legion_claim_race_trg BEFORE UPDATE ON agent_task_queue
                FOR EACH ROW EXECUTE FUNCTION legion_claim_race_sleep()
                """);
    }

    private void dropClaimRaceTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS legion_claim_race_trg ON agent_task_queue");
        jdbc.execute("DROP FUNCTION IF EXISTS legion_claim_race_sleep()");
    }

    /**
     * 终态双写竞态（§9.3 清单项，NIMI-14）：complete 与 fail 并发打同一
     * dispatched 行，恰好一路生效；败者 0 行，胜者列完整、败者列不残留
     * （0 行 = 幂等护栏，设计 §3.5）。
     */
    @Test
    void concurrentCompleteAndFailExactlyOneTerminalizes() throws Exception {
        Seed s = seed();
        UUID taskId = enqueue(s, "queued");
        DaemonClient setup = new DaemonClient(URI.create(baseUrl()));
        assertThat(setup.claim()).isNotNull();

        jdbc.execute("""
                CREATE OR REPLACE FUNCTION legion_terminal_race_sleep() RETURNS trigger AS $$
                BEGIN
                    IF OLD.status IN ('dispatched','running')
                       AND NEW.status IN ('completed','failed') THEN
                        PERFORM pg_sleep(0.4);
                    END IF;
                    RETURN NEW;
                END; $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER legion_terminal_race_trg BEFORE UPDATE ON agent_task_queue
                FOR EACH ROW EXECUTE FUNCTION legion_terminal_race_sleep()
                """);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            DaemonClient c1 = new DaemonClient(URI.create(baseUrl()));
            DaemonClient c2 = new DaemonClient(URI.create(baseUrl()));
            CountDownLatch start = new CountDownLatch(1);

            com.fasterxml.jackson.databind.ObjectMapper json =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            Future<Boolean> complete = pool.submit(() -> {
                start.await();
                return c1.complete(taskId, json.createObjectNode().put("output", "done"));
            });
            Future<Boolean> fail = pool.submit(() -> {
                start.await();
                return c2.fail(taskId, "boom", "no_result");
            });
            start.countDown();

            boolean completeApplied = complete.get(15, TimeUnit.SECONDS);
            boolean failApplied = fail.get(15, TimeUnit.SECONDS);

            assertThat(completeApplied ^ failApplied)
                    .as("complete=%s fail=%s status=%s",
                            completeApplied, failApplied, statusOf(taskId))
                    .isTrue();
            if (completeApplied) {
                assertThat(statusOf(taskId)).isEqualTo("completed");
                assertThat(jdbc.queryForObject(
                        "SELECT error FROM agent_task_queue WHERE id = ?", String.class, taskId))
                        .isNull();
                assertThat(jdbc.queryForObject(
                        "SELECT failure_class FROM agent_task_queue WHERE id = ?", String.class, taskId))
                        .isNull();
            } else {
                assertThat(statusOf(taskId)).isEqualTo("failed");
                assertThat(jdbc.queryForObject(
                        "SELECT result FROM agent_task_queue WHERE id = ?", String.class, taskId))
                        .isNull();
            }
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS legion_terminal_race_trg ON agent_task_queue");
            jdbc.execute("DROP FUNCTION IF EXISTS legion_terminal_race_sleep()");
            pool.shutdownNow();
        }
    }
}