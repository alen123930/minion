package io.legion.server.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M0-3 集成测试基线：真实 PostgreSQL 17 容器上验证 V1__init.sql 的迁移与关键约束。
 * 直连 Flyway API + JdbcTemplate，不经 Spring 上下文（上下文接库留给 M0-4 CRUD 任务）。
 *
 * <p>词汇铁律：任务终态是 {@code completed}（非 done），{@code dispatched} 是中间态
 * （AGENTS.md 协议与任务生命周期一节）。唯一槽语义：per-(issue, agent) 而非 per-issue 全局
 * ——参照仓库 migration 037 的教训：不同 agent 的 pending 任务不该互挡。
 */
@Testcontainers
class V1InitMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"));

    static Flyway flyway;
    static JdbcTemplate jdbc;
    static MigrateResult firstRun;

    @BeforeAll
    static void migrateFreshDatabase() {
        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();
        firstRun = flyway.migrate();
        jdbc = new JdbcTemplate(ds);
    }

    @Test
    void firstMigrateAppliesExactlyOneVersion() {
        assertThat(firstRun.migrationsExecuted).isEqualTo(1);
        assertThat(firstRowDescription()).isEqualTo("1");
    }

    @Test
    void secondMigrateIsNoOpAndSchemaValidates() {
        MigrateResult secondRun = flyway.migrate();
        assertThat(secondRun.migrationsExecuted).isZero();
        // 迁移可重复执行：已应用库上 validate 通过（checksum 漂移会让它抛异常）
        flyway.validate();
    }

    @Test
    void allEightTablesCreated() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);
        assertThat(tables).contains(
                "user", "workspace", "member", "agent", "issue", "comment",
                "agent_task_queue", "agent_runtime");
    }

    @Test
    void duplicateQueuedTaskPerIssueAgentRejected() {
        Seed s = seed();
        insertTask(s, "queued");
        assertThatThrownBy(() -> insertTask(s, "queued"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("idx_one_pending_task_per_issue_agent");
    }

    @Test
    void dispatchedHoldsSlotButOtherAgentOnSameIssueNotBlocked() {
        Seed s = seed();
        insertTask(s, "dispatched");
        // dispatched 也是 pending 词汇的一部分，同样占用唯一槽
        assertThatThrownBy(() -> insertTask(s, "queued"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("idx_one_pending_task_per_issue_agent");
        // 037 语义：同一 issue 换一个 agent 入队不受前者阻挡
        UUID otherAgent = insertRow(
                "INSERT INTO agent (workspace_id, name, runtime_mode) VALUES (?, 'b', 'local') RETURNING id", s.wsId);
        insertTask(s.wsId, s.issueId, otherAgent, "queued");
        assertThat(countTasks(s.issueId)).isEqualTo(2);
    }

    @Test
    void terminalStatusFreesSlotForReenqueue() {
        Seed s = seed();
        insertTask(s, "queued");
        jdbc.update("UPDATE agent_task_queue SET status = 'completed' WHERE issue_id = ? AND agent_id = ?",
                s.issueId, s.agentId);
        // partial unique index 只覆盖 queued/dispatched；终态后同 (issue,agent) 可重新入队
        insertTask(s, "queued");
        assertThat(countTasks(s.issueId)).isEqualTo(2);
    }

    @Test
    void doneStatusRejectedByCheckConstraint() {
        Seed s = seed();
        assertThatThrownBy(() -> insertTask(s, "done"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("agent_task_queue_status_check");
    }

    @Test
    void workspaceDeleteCascadesToAllDependents() {
        Seed s = seed();
        UUID userId = insertRow(
                "INSERT INTO \"user\" (name, email) VALUES (?, ?) RETURNING id",
                "u-" + UUID.randomUUID(), UUID.randomUUID() + "@example.com");
        insertRow("INSERT INTO member (workspace_id, user_id, role) VALUES (?, ?, 'member') RETURNING id",
                s.wsId, userId);
        UUID commentId = insertRow(
                "INSERT INTO comment (issue_id, author_type, author_id, body) VALUES (?, 'agent', ?, 'c') RETURNING id",
                s.issueId, s.agentId);
        insertTask(s, "queued");

        jdbc.update("DELETE FROM workspace WHERE id = ?", s.wsId);

        // M0 六表 FK CASCADE 是刻意简化（AGENTS.md），此处锚定该简化确实在位
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member WHERE workspace_id = ?", Integer.class, s.wsId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent WHERE workspace_id = ?", Integer.class, s.wsId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issue WHERE workspace_id = ?", Integer.class, s.wsId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comment WHERE id = ?", Integer.class, commentId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_task_queue WHERE workspace_id = ?", Integer.class, s.wsId)).isZero();
    }

    private record Seed(UUID wsId, UUID issueId, UUID agentId) {
    }

    private Seed seed() {
        UUID wsId = insertRow("INSERT INTO workspace (name, slug) VALUES (?, ?) RETURNING id",
                "ws-" + UUID.randomUUID(), "slug-" + UUID.randomUUID());
        UUID agentId = insertRow(
                "INSERT INTO agent (workspace_id, name, runtime_mode) VALUES (?, 'a', 'local') RETURNING id", wsId);
        UUID issueId = insertRow(
                "INSERT INTO issue (workspace_id, title, creator_type, creator_id) VALUES (?, 't', 'agent', ?) RETURNING id",
                wsId, agentId);
        return new Seed(wsId, issueId, agentId);
    }

    private void insertTask(Seed s, String status) {
        insertTask(s.wsId, s.issueId, s.agentId, status);
    }

    private static void insertTask(UUID wsId, UUID issueId, UUID agentId, String status) {
        jdbc.update("INSERT INTO agent_task_queue (workspace_id, issue_id, agent_id, status) VALUES (?, ?, ?, ?)",
                wsId, issueId, agentId, status);
    }

    private static UUID insertRow(String sql, Object... args) {
        return jdbc.queryForObject(sql, UUID.class, args);
    }

    private static int countTasks(UUID issueId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM agent_task_queue WHERE issue_id = ?", Integer.class, issueId);
    }

    private static String firstRowDescription() {
        return jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",
                String.class);
    }
}
