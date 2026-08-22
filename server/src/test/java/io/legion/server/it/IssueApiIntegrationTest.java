package io.legion.server.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import com.fasterxml.jackson.databind.JsonNode;

class IssueApiIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    @Test
    void listReturnsIssuesSortedByUpdatedAtDesc() {
        UUID older = jdbc.queryForObject(
                "INSERT INTO issue (workspace_id, title, creator_type, creator_id, updated_at) VALUES (?, ?, 'member', ?, ?) RETURNING id",
                UUID.class, WS_ID, "older", MEMBER_ID, OffsetDateTime.now().minusHours(2));
        UUID newer = jdbc.queryForObject(
                "INSERT INTO issue (workspace_id, title, creator_type, creator_id, updated_at) VALUES (?, ?, 'member', ?, ?) RETURNING id",
                UUID.class, WS_ID, "newer", MEMBER_ID, OffsetDateTime.now().minusHours(1));

        ResponseEntity<String> res = rest.getForEntity("/api/issues", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode arr = parseArray(res.getBody());
        assertThat(arr.size()).isGreaterThanOrEqualTo(2);
        int newerIdx = indexOf(arr, newer);
        int olderIdx = indexOf(arr, older);
        assertThat(newerIdx).isLessThan(olderIdx);
    }

    @Test
    void createIssueEchoesFieldsAndDefaultsCreator() {
        ResponseEntity<String> res = rest.postForEntity(
                "/api/issues",
                mapOf("title", "hello", "description", "world", "status", "todo"),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode issue = parseObject(res.getBody());
        assertThat(issue.get("title").asText()).isEqualTo("hello");
        assertThat(issue.get("description").asText()).isEqualTo("world");
        assertThat(issue.get("status").asText()).isEqualTo("todo");
        assertThat(issue.get("creator_type").asText()).isEqualTo("member");
        assertThat(issue.get("creator_id").asText()).isEqualTo(MEMBER_ID.toString());
        assertThat(issue.get("workspace_id").asText()).isEqualTo(WS_ID.toString());
    }

    @Test
    void createIssueRejectsBlankTitle() {
        ResponseEntity<String> res = rest.postForEntity("/api/issues", mapOf("title", "  "), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createIssueWithAgentAssigneeEnqueuesTask() {
        UUID agentId = insertAgent("agent-enqueue");
        ResponseEntity<String> res = rest.postForEntity(
                "/api/issues",
                mapOf("title", "run me", "status", "todo",
                        "assignee_type", "agent", "assignee_id", agentId.toString()),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode issue = parseObject(res.getBody());
        assertThat(countQueuedTasks(UUID.fromString(issue.get("id").asText()), agentId)).isEqualTo(1);
    }

    @Test
    void createIssueAssignedToBacklogDoesNotEnqueue() {
        UUID agentId = insertAgent("agent-parked");
        ResponseEntity<String> res = rest.postForEntity(
                "/api/issues",
                mapOf("title", "park me", "status", "backlog",
                        "assignee_type", "agent", "assignee_id", agentId.toString()),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode issue = parseObject(res.getBody());
        assertThat(countQueuedTasks(UUID.fromString(issue.get("id").asText()), agentId)).isZero();
    }

    @Test
    void createIssueWithUnknownAgentAssigneeRejected() {
        UUID ghost = UUID.randomUUID();
        ResponseEntity<String> res = rest.postForEntity(
                "/api/issues",
                mapOf("title", "ghost assignee", "status", "todo",
                        "assignee_type", "agent", "assignee_id", ghost.toString()),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createIssueRejectsUnknownStatus() {
        ResponseEntity<String> res = rest.postForEntity(
                "/api/issues",
                mapOf("title", "garbage status", "status", "frozen"),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getIssueDetailIncludesComments() {
        UUID issueId = insertIssue("detail me");
        rest.postForEntity("/api/issues/" + issueId + "/comments",
                mapOf("body", "first thought"), String.class);
        rest.postForEntity("/api/issues/" + issueId + "/comments",
                mapOf("body", "second thought"), String.class);

        ResponseEntity<String> res = rest.getForEntity("/api/issues/" + issueId, String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = parseObject(res.getBody());
        assertThat(body.get("issue").get("title").asText()).isEqualTo("detail me");
        JsonNode comments = body.get("comments");
        assertThat(comments.size()).isEqualTo(2);
        assertThat(comments.get(0).get("body").asText()).isEqualTo("first thought");
        assertThat(comments.get(1).get("body").asText()).isEqualTo("second thought");
    }

    @Test
    void getMissingIssueReturns404() {
        ResponseEntity<String> res = rest.getForEntity("/api/issues/" + UUID.randomUUID(), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getIssueWithInvalidUuidReturns400() {
        ResponseEntity<String> res = rest.getForEntity("/api/issues/not-a-uuid", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private int indexOf(JsonNode arr, UUID id) {
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i).get("id").asText().equals(id.toString())) {
                return i;
            }
        }
        throw new AssertionError("issue " + id + " not present in list: " + arr);
    }

    private JsonNode parseArray(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("not a JSON array: " + body, e);
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