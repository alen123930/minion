package io.legion.server.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import com.fasterxml.jackson.databind.JsonNode;

class IssueStreamIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    @Test
    void streamDeliversConnectedThenCommentAndTaskQueuedEvents() throws Exception {
        UUID agentId = insertAgent("stream-agent");
        UUID issueId = insertIssue("stream target");

        HttpURLConnection conn = openStream(issueId);
        try {
            assertThat(conn.getResponseCode()).isEqualTo(200);
            assertThat(conn.getContentType()).contains("text/event-stream");

            String connected = readDataLine(conn, Duration.ofSeconds(5));
            assertThat(connected).as("expected a connected frame").isNotNull();
            JsonNode connectedEvt = json.readTree(connected);
            assertThat(connectedEvt.get("type").asText()).isEqualTo("connected");
            assertThat(connectedEvt.get("payload").get("issue_id").asText()).isEqualTo(issueId.toString());

            rest.postForEntity("/api/issues/" + issueId + "/comments",
                    java.util.Map.of("body", "stream comment"), String.class);
            String commentEvt = readDataLine(conn, Duration.ofSeconds(5));
            assertThat(commentEvt).isNotNull();
            assertThat(json.readTree(commentEvt).get("type").asText()).isEqualTo("comment:created");

            rest.postForEntity("/api/agents/" + agentId + "/tasks",
                    java.util.Map.of("issue_id", issueId.toString()), String.class);
            String queuedEvt = readDataLine(conn, Duration.ofSeconds(5));
            assertThat(queuedEvt).isNotNull();
            assertThat(json.readTree(queuedEvt).get("type").asText()).isEqualTo("task:queued");
        } finally {
            conn.disconnect();
        }
    }

    @Test
    void streamOnMissingIssueReturns404() throws Exception {
        HttpURLConnection conn = openStream(UUID.randomUUID());
        try {
            assertThat(conn.getResponseCode()).isEqualTo(404);
        } finally {
            conn.disconnect();
        }
    }
}