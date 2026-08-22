package io.legion.server.it;

import static io.legion.server.WorkspaceDefaults.DEFAULT_MEMBER_ID;
import static io.legion.server.WorkspaceDefaults.DEFAULT_WORKSPACE_ID;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * M0-4 集成测试基类：免认证、单 workspace 硬编码（设计 §4.2），fixture 直接经
 * jdbc 落库。PG 容器由各测试类自持 @Container + @ServiceConnection（设计 §9.1：
 * 每个 test class 一个 PG 实例），上下文经 @DirtiesContext 不跨类复用——
 * 否则静态容器被上个类停掉后，缓存上下文仍指向死库。
 */
@Testcontainers
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {

    protected static PostgreSQLContainer<?> newPostgres() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"));
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper json;

    protected static final UUID WS_ID = DEFAULT_WORKSPACE_ID;
    protected static final UUID MEMBER_ID = DEFAULT_MEMBER_ID;

    protected UUID insertAgent(String name) {
        return jdbc.queryForObject(
                "INSERT INTO agent (workspace_id, name, runtime_mode) VALUES (?, ?, 'local') RETURNING id",
                UUID.class, WS_ID, name);
    }

    protected UUID insertIssue(String title) {
        return jdbc.queryForObject(
                "INSERT INTO issue (workspace_id, title, creator_type, creator_id) VALUES (?, ?, 'member', ?) RETURNING id",
                UUID.class, WS_ID, title, MEMBER_ID);
    }

    protected int countQueuedTasks(UUID issueId, UUID agentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM agent_task_queue WHERE issue_id = ? AND agent_id = ? AND status = 'queued'",
                Integer.class, issueId, agentId);
    }

    /** 打开一条 issue SSE 连接（不读流）；用于断言事件流行为。 */
    protected HttpURLConnection openStream(UUID issueId) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                rest.getRootUri() + "/api/issues/" + issueId + "/stream").openConnection();
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(2_000);
        return conn;
    }

    /** 读一条 SSE data 帧（payload 去 data: 前缀）；超时窗口内无帧返回 null。 */
    protected String readDataLine(HttpURLConnection conn, Duration timeout) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            String line;
            try {
                line = reader.readLine();
            } catch (SocketTimeoutException e) {
                continue;
            }
            if (line == null) {
                return null;
            }
            if (line.startsWith("data:")) {
                String payload = line.substring("data:".length()).trim();
                if (!payload.isEmpty()) {
                    return payload;
                }
            }
        }
        return null;
    }
}