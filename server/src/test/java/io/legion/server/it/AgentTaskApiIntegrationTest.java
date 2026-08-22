package io.legion.server.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import com.fasterxml.jackson.databind.JsonNode;

class AgentTaskApiIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    @Test
    void manualTriggerEnqueuesTaskForIssue() {
        UUID agentId = insertAgent("manual-run");
        UUID issueId = insertIssue("manual target");

        ResponseEntity<String> res = rest.postForEntity(
                "/api/agents/" + agentId + "/tasks",
                mapOf("issue_id", issueId.toString()),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = parseObject(res.getBody());
        assertThat(body.get("coalesced").asBoolean()).isFalse();
        JsonNode task = body.get("task");
        assertThat(task.get("issue_id").asText()).isEqualTo(issueId.toString());
        assertThat(task.get("agent_id").asText()).isEqualTo(agentId.toString());
        assertThat(task.get("status").asText()).isEqualTo("queued");
        assertThat(countQueuedTasks(issueId, agentId)).isEqualTo(1);
    }

    @Test
    void manualTriggerCoalescesExistingPendingTask() {
        UUID agentId = insertAgent("manual-coalesce");
        UUID issueId = insertIssue("coalesce target");

        rest.postForEntity("/api/agents/" + agentId + "/tasks", mapOf("issue_id", issueId.toString()), String.class);
        ResponseEntity<String> res = rest.postForEntity(
                "/api/agents/" + agentId + "/tasks",
                mapOf("issue_id", issueId.toString()),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = parseObject(res.getBody());
        assertThat(body.get("coalesced").asBoolean()).isTrue();
        assertThat(body.get("task").get("status").asText()).isEqualTo("queued");
        assertThat(countQueuedTasks(issueId, agentId)).isEqualTo(1);
    }

    @Test
    void manualTriggerMissingAgentReturns404() {
        UUID issueId = insertIssue("orphan target");
        ResponseEntity<String> res = rest.postForEntity(
                "/api/agents/" + UUID.randomUUID() + "/tasks",
                mapOf("issue_id", issueId.toString()),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void manualTriggerMissingIssueReturns404() {
        UUID agentId = insertAgent("no-issue");
        ResponseEntity<String> res = rest.postForEntity(
                "/api/agents/" + agentId + "/tasks",
                mapOf("issue_id", UUID.randomUUID().toString()),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void manualTriggerCoalesceDoesNotReBroadcastTaskQueued() throws Exception {
        UUID agentId = insertAgent("coalesce-no-rebroadcast");
        UUID issueId = insertIssue("coalesce target");

        HttpURLConnection conn = openStream(issueId);
        try {
            String connected = readDataLine(conn, Duration.ofSeconds(5));
            assertThat(connected).isNotNull();

            ResponseEntity<String> first = rest.postForEntity(
                    "/api/agents/" + agentId + "/tasks",
                    mapOf("issue_id", issueId.toString()), String.class);
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String queued = readDataLine(conn, Duration.ofSeconds(5));
            assertThat(json.readTree(queued).get("type").asText()).isEqualTo("task:queued");

            ResponseEntity<String> second = rest.postForEntity(
                    "/api/agents/" + agentId + "/tasks",
                    mapOf("issue_id", issueId.toString()), String.class);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
            // coalesce（0 行受影响）不广播 task:queued：短窗口内不应再收到 data 帧
            assertThat(readDataLine(conn, Duration.ofMillis(1500)))
                    .as("coalesced trigger must not emit task:queued").isNull();
        } finally {
            conn.disconnect();
        }
    }

    private JsonNode parseObject(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("not a JSON object: " + body, e);
        }
    }

    private java.util.Map<String, Object> mapOf(Object... kv) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}