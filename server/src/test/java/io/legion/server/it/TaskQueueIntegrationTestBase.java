package io.legion.server.it;

import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.fail;

/**
 * daemon 协议集成测试基座：对齐 M0-4 的 {@link AbstractIntegrationTest} 约定
 * （`it` profile + 每类自持容器 + @DirtiesContext 防跨类死库），在此之上补
 * 队列 fixture 与「方法级 TRUNCATE」——worker/协议测试对队列状态敏感，
 * 同一类内的测试方法共享容器，不清会串味。
 *
 * <p>每个子类必须像 M0-4 测试一样声明：
 * {@code @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = newPostgres();}
 */
public abstract class TaskQueueIntegrationTestBase extends AbstractIntegrationTest {

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE agent_task_queue, comment, issue, agent, member, workspace, agent_runtime, \"user\" CASCADE");
    }

    protected String baseUrl() {
        return rest.getRootUri();
    }

    protected record Seed(UUID wsId, UUID agentId, UUID issueId) {
    }

    /** 种一个 workspace + agent + issue 的最小 fixture（字段默认值即合法）。 */
    protected Seed seed() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UUID wsId = insertRow("INSERT INTO workspace (name, slug) VALUES (?, ?) RETURNING id",
                "ws-" + tag, "slug-" + tag);
        UUID agentId = insertRow(
                "INSERT INTO agent (workspace_id, name, runtime_mode) VALUES (?, ?, 'local') RETURNING id",
                wsId, "agent-" + tag);
        UUID issueId = insertRow(
                "INSERT INTO issue (workspace_id, title, creator_type, creator_id) VALUES (?, ?, 'agent', ?) RETURNING id",
                wsId, "issue-" + tag, agentId);
        return new Seed(wsId, agentId, issueId);
    }

    /** 入队一个任务（M0 的入队路径由 M0-4 负责，测试直接 SQL 种）。 */
    protected UUID enqueue(Seed s, String status) {
        return enqueue(s, status, 0);
    }

    protected UUID enqueue(Seed s, String status, int priority) {
        return insertRow(
                "INSERT INTO agent_task_queue (workspace_id, issue_id, agent_id, status, priority) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id",
                s.wsId, s.issueId, s.agentId, status, priority);
    }

    protected UUID insertRow(String sql, Object... args) {
        return jdbc.queryForObject(sql, UUID.class, args);
    }

    protected int countByStatus(String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM agent_task_queue WHERE status = ?", Integer.class, status);
    }

    protected String statusOf(UUID taskId) {
        return jdbc.queryForObject(
                "SELECT status FROM agent_task_queue WHERE id = ?", String.class, taskId);
    }

    /** 轮询等待 DB 状态收敛（无 Awaitility 依赖）。 */
    protected void awaitCondition(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("await 被中断");
            }
        }
        fail("等待超时: " + timeout);
    }
}