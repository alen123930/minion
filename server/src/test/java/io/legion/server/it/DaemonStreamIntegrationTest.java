package io.legion.server.it;

import com.fasterxml.jackson.databind.JsonNode;
import io.legion.contracts.ReportUsageRequest;
import io.legion.contracts.StreamEvent;
import io.legion.contracts.TaskMessagesRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * daemon 协议的事件/上报端点（M0-7）：messages → SSE task:message 转发、
 * usage 先于终态的落库幂等、入队 prompt 快照。
 */
class DaemonStreamIntegrationTest extends TaskQueueIntegrationTestBase {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    @Autowired
    RestClient.Builder restBuilder;

    private RestClient rest() {
        return restBuilder.clone().baseUrl(baseUrl()).build();
    }

    @Test
    void messagesForwardToIssueStreamAsTaskMessageFrames() throws Exception {
        Seed s = seed();
        UUID taskId = enqueue(s, "queued");
        rest().post().uri("/api/daemon/tasks/claim").retrieve().body(String.class);

        HttpURLConnection conn = openStream(s.issueId());
        try {
            assertThat(readDataLine(conn, Duration.ofSeconds(5))).contains("connected");

            rest().post().uri("/api/daemon/tasks/{id}/messages", taskId)
                    .body(new TaskMessagesRequest(List.of(
                            new StreamEvent("assistant_text", Map.of("text", "streaming answer")))))
                    .retrieve().body(String.class);

            String frame = readDataLine(conn, Duration.ofSeconds(5));
            assertThat(frame).isNotNull();
            JsonNode evt = json.readTree(frame);
            assertThat(evt.path("type").asText()).isEqualTo("task:message");
            assertThat(evt.path("payload").path("task_id").asText()).isEqualTo(taskId.toString());
            assertThat(evt.path("payload").path("event").path("type").asText())
                    .isEqualTo("assistant_text");
            assertThat(evt.path("payload").path("event").path("payload").path("text").asText())
                    .isEqualTo("streaming answer");
        } finally {
            conn.disconnect();
        }
    }

    @Test
    void usagePersistsColumnsWhileTaskDispatched() throws Exception {
        Seed s = seed();
        UUID taskId = enqueue(s, "queued");
        rest().post().uri("/api/daemon/tasks/claim").retrieve().body(String.class);

        JsonNode applied = json.readTree(rest().post()
                .uri("/api/daemon/tasks/{id}/usage", taskId)
                .body(new ReportUsageRequest("sess-1", 11, 7, 999))
                .retrieve().body(String.class));

        assertThat(applied.path("applied").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT session_id FROM agent_task_queue WHERE id = ?", String.class, taskId))
                .isEqualTo("sess-1");
        assertThat(jdbc.queryForObject(
                "SELECT input_tokens FROM agent_task_queue WHERE id = ?", Integer.class, taskId))
                .isEqualTo(11);
        assertThat(jdbc.queryForObject(
                "SELECT output_tokens FROM agent_task_queue WHERE id = ?", Integer.class, taskId))
                .isEqualTo(7);
        assertThat(jdbc.queryForObject(
                "SELECT cost_usd_ticks FROM agent_task_queue WHERE id = ?", Long.class, taskId))
                .isEqualTo(999L);
    }

    @Test
    void usageAfterTerminalIsNotApplied() throws Exception {
        Seed s = seed();
        UUID taskId = enqueue(s, "queued");
        rest().post().uri("/api/daemon/tasks/claim").retrieve().body(String.class);
        rest().post().uri("/api/daemon/tasks/{id}/complete", taskId)
                .body(new io.legion.contracts.CompleteTaskRequest(
                        json.createObjectNode().put("output", "done")))
                .retrieve().body(String.class);

        JsonNode applied = json.readTree(rest().post()
                .uri("/api/daemon/tasks/{id}/usage", taskId)
                .body(new ReportUsageRequest(null, 1, 1, 1))
                .retrieve().body(String.class));

        assertThat(applied.path("applied").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT input_tokens FROM agent_task_queue WHERE id = ?", Integer.class, taskId))
                .isNull();
    }

    @Test
    void enqueueViaApiSnapshotsPromptIntoContext() {
        // API 入队路径挂在默认 workspace（M0 单 workspace 硬编码），
        // seed() 的随机 workspace 会撞 FK
        UUID agentId = insertAgent("prompt-snap-agent");
        UUID issueId = insertIssue("prompt snap issue");
        jdbc.update("UPDATE issue SET description = ? WHERE id = ?",
                "make the build green", issueId);

        rest().post().uri("/api/agents/{id}/tasks", agentId)
                .body(Map.of("issue_id", issueId.toString()))
                .retrieve().body(String.class);

        String context = jdbc.queryForObject(
                "SELECT context::text FROM agent_task_queue WHERE issue_id = ? AND agent_id = ?",
                String.class, issueId, agentId);
        assertThat(context).contains("prompt");
        assertThat(context).contains("prompt snap issue");
        assertThat(context).contains("make the build green");
    }

    @Test
    void completePublishesTaskCompletedFrameToStream() throws Exception {
        Seed s = seed();
        UUID taskId = enqueue(s, "queued");
        rest().post().uri("/api/daemon/tasks/claim").retrieve().body(String.class);

        HttpURLConnection conn = openStream(s.issueId());
        try {
            assertThat(readDataLine(conn, Duration.ofSeconds(5))).contains("connected");

            rest().post().uri("/api/daemon/tasks/{id}/complete", taskId)
                    .body(new io.legion.contracts.CompleteTaskRequest(
                            json.createObjectNode().put("output", "done")))
                    .retrieve().body(String.class);

            String frame = readDataLine(conn, Duration.ofSeconds(5));
            assertThat(frame).isNotNull();
            JsonNode evt = json.readTree(frame);
            assertThat(evt.path("type").asText()).isEqualTo("task:completed");
            assertThat(evt.path("payload").path("task_id").asText()).isEqualTo(taskId.toString());
        } finally {
            conn.disconnect();
        }
    }

    @Test
    void failPublishesTaskFailedFrameToStream() throws Exception {
        Seed s = seed();
        UUID taskId = enqueue(s, "queued");
        rest().post().uri("/api/daemon/tasks/claim").retrieve().body(String.class);

        HttpURLConnection conn = openStream(s.issueId());
        try {
            assertThat(readDataLine(conn, Duration.ofSeconds(5))).contains("connected");

            rest().post().uri("/api/daemon/tasks/{id}/fail", taskId)
                    .body(new io.legion.contracts.FailTaskRequest("boom", "no_result"))
                    .retrieve().body(String.class);

            String frame = readDataLine(conn, Duration.ofSeconds(5));
            assertThat(frame).isNotNull();
            JsonNode evt = json.readTree(frame);
            assertThat(evt.path("type").asText()).isEqualTo("task:failed");
            assertThat(evt.path("payload").path("task_id").asText()).isEqualTo(taskId.toString());
        } finally {
            conn.disconnect();
        }
    }
}
