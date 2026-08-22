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

class CommentApiIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    @Test
    void createCommentEchoesFieldsAndDefaultsAuthor() {
        UUID issueId = insertIssue("comment target");
        ResponseEntity<String> res = rest.postForEntity(
                "/api/issues/" + issueId + "/comments",
                mapOf("body", "hello there"),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode comment = parseObject(res.getBody());
        assertThat(comment.get("issue_id").asText()).isEqualTo(issueId.toString());
        assertThat(comment.get("body").asText()).isEqualTo("hello there");
        assertThat(comment.get("author_type").asText()).isEqualTo("member");
        assertThat(comment.get("author_id").asText()).isEqualTo(MEMBER_ID.toString());
    }

    @Test
    void createCommentOnMissingIssueReturns404() {
        ResponseEntity<String> res = rest.postForEntity(
                "/api/issues/" + UUID.randomUUID() + "/comments",
                mapOf("body", "orphan"),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createCommentRejectsBlankBody() {
        UUID issueId = insertIssue("blank comment target");
        ResponseEntity<String> res = rest.postForEntity(
                "/api/issues/" + issueId + "/comments",
                mapOf("body", "   "),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createCommentBumpsIssueUpdatedAt() {
        UUID issueId = jdbc.queryForObject(
                "INSERT INTO issue (workspace_id, title, creator_type, creator_id, updated_at) VALUES (?, ?, 'member', ?, ?) RETURNING id",
                UUID.class, WS_ID, "touch target", MEMBER_ID, OffsetDateTime.now().minusHours(1));

        rest.postForEntity("/api/issues/" + issueId + "/comments", mapOf("body", "bump"), String.class);

        OffsetDateTime after = jdbc.queryForObject(
                "SELECT updated_at FROM issue WHERE id = ?", OffsetDateTime.class, issueId);
        assertThat(after).isAfter(OffsetDateTime.now().minusMinutes(1));
    }

    @Test
    void agentAuthorRequiresAuthorId() {
        UUID issueId = insertIssue("agent author target");
        ResponseEntity<String> res = rest.postForEntity(
                "/api/issues/" + issueId + "/comments",
                mapOf("author_type", "agent", "body", "no id"),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
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