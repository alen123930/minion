package io.legion.server.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.legion.contracts.ClaimTaskResponse;
import io.legion.contracts.CompleteTaskRequest;
import io.legion.contracts.CompleteTaskResponse;
import io.legion.contracts.FailTaskRequest;
import io.legion.contracts.FailTaskResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * daemon 协议端点的端到端语义：认领/终态 + 幂等 + 队列优先级。
 * 覆盖 M0-5 的 daemon↔server 通信面（设计 §4.2 的 claim/complete/fail）。
 */
class DaemonTaskApiIntegrationTest extends TaskQueueIntegrationTestBase {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    private final ObjectMapper mapper = new ObjectMapper();

    private RestClient rest() {
        return RestClient.builder().baseUrl(baseUrl()).build();
    }

    @Test
    void claimReturnsQueuedTaskAndTransitionsToDispatched() {
        Seed s = seed();
        UUID taskId = enqueue(s, "queued");

        ClaimTaskResponse resp = rest().post().uri("/api/daemon/tasks/claim").retrieve()
                .body(ClaimTaskResponse.class);

        assertThat(resp.task()).isNotNull();
        assertThat(resp.task().getId()).isEqualTo(taskId);
        assertThat(resp.task().getStatus()).isEqualTo("dispatched");
        assertThat(resp.task().getWorkspaceId()).isEqualTo(s.wsId());
        assertThat(resp.task().getIssueId()).isEqualTo(s.issueId());
        assertThat(resp.task().getAgentId()).isEqualTo(s.agentId());
        assertThat(countByStatus("queued")).isZero();
        assertThat(countByStatus("dispatched")).isEqualTo(1);
    }

    @Test
    void claimWhenQueueEmptyReturnsNullTask() {
        seed();
        ClaimTaskResponse resp = rest().post().uri("/api/daemon/tasks/claim").retrieve()
                .body(ClaimTaskResponse.class);
        assertThat(resp.task()).isNull();
    }

    @Test
    void claimPicksHighestPriorityFirst() {
        // 只断言 priority 排序（created_at 平局断是 ORDER BY 的一部分，此处不断言）
        // 同一 (issue, agent) 至多一个 pending（唯一索引），优先级排序用两个独立 issue
        Seed lowSeed = seed();
        UUID low = enqueue(lowSeed, "queued", 1);
        Seed highSeed = seed();
        UUID high = enqueue(highSeed, "queued", 10);

        ClaimTaskResponse first = rest().post().uri("/api/daemon/tasks/claim").retrieve()
                .body(ClaimTaskResponse.class);
        assertThat(first.task().getId()).isEqualTo(high);

        ClaimTaskResponse second = rest().post().uri("/api/daemon/tasks/claim").retrieve()
                .body(ClaimTaskResponse.class);
        assertThat(second.task().getId()).isEqualTo(low);
    }

    @Test
    void completeStoresResultAndIsIdempotent() {
        Seed s = seed();
        UUID taskId = enqueue(s, "queued");
        rest().post().uri("/api/daemon/tasks/claim").retrieve().body(ClaimTaskResponse.class);

        ObjectNode result = mapper.createObjectNode();
        result.put("answer", 42);
        CompleteTaskResponse resp = rest().post()
                .uri("/api/daemon/tasks/{id}/complete", taskId)
                .body(new CompleteTaskRequest(result))
                .retrieve().body(CompleteTaskResponse.class);

        assertThat(resp.applied()).isTrue();
        assertThat(statusOf(taskId)).isEqualTo("completed");
        assertThat(jdbc.queryForObject(
                "SELECT result->>'answer' FROM agent_task_queue WHERE id = ?", String.class, taskId))
                .isEqualTo("42");

        CompleteTaskResponse again = rest().post()
                .uri("/api/daemon/tasks/{id}/complete", taskId)
                .body(new CompleteTaskRequest(result))
                .retrieve().body(CompleteTaskResponse.class);
        assertThat(again.applied()).isFalse();
        assertThat(statusOf(taskId)).isEqualTo("completed");
    }

    @Test
    void failStoresErrorAndFailureClassAndIsIdempotent() {
        Seed s = seed();
        UUID taskId = enqueue(s, "queued");
        rest().post().uri("/api/daemon/tasks/claim").retrieve().body(ClaimTaskResponse.class);

        FailTaskResponse resp = rest().post()
                .uri("/api/daemon/tasks/{id}/fail", taskId)
                .body(new FailTaskRequest("boom", "stub_failure"))
                .retrieve().body(FailTaskResponse.class);

        assertThat(resp.applied()).isTrue();
        assertThat(statusOf(taskId)).isEqualTo("failed");
        assertThat(jdbc.queryForObject(
                "SELECT error FROM agent_task_queue WHERE id = ?", String.class, taskId))
                .isEqualTo("boom");
        assertThat(jdbc.queryForObject(
                "SELECT failure_class FROM agent_task_queue WHERE id = ?", String.class, taskId))
                .isEqualTo("stub_failure");

        FailTaskResponse again = rest().post()
                .uri("/api/daemon/tasks/{id}/fail", taskId)
                .body(new FailTaskRequest("boom", "stub_failure"))
                .retrieve().body(FailTaskResponse.class);
        assertThat(again.applied()).isFalse();
    }

    @Test
    void terminalReportOnNonDispatchedTaskIsNotApplied() {
        Seed s = seed();
        UUID taskId = enqueue(s, "queued");
        ObjectNode result = mapper.createObjectNode();

        CompleteTaskResponse resp = rest().post()
                .uri("/api/daemon/tasks/{id}/complete", taskId)
                .body(new CompleteTaskRequest(result))
                .retrieve().body(CompleteTaskResponse.class);

        assertThat(resp.applied()).isFalse();
        assertThat(statusOf(taskId)).isEqualTo("queued");
    }

    @Test
    void terminalReportOnUnknownTaskIsNotApplied() {
        ObjectNode result = mapper.createObjectNode();
        CompleteTaskResponse resp = rest().post()
                .uri("/api/daemon/tasks/{id}/complete", UUID.randomUUID())
                .body(new CompleteTaskRequest(result))
                .retrieve().body(CompleteTaskResponse.class);
        assertThat(resp.applied()).isFalse();
    }

    @Test
    void invalidTaskIdReturns400() {
        ObjectNode result = mapper.createObjectNode();
        int status = rest().post()
                .uri("/api/daemon/tasks/not-a-uuid/complete")
                .body(new CompleteTaskRequest(result))
                .retrieve()
                .onStatus(statusCode -> statusCode.value() == 400, (request, response) -> {
                })
                .toBodilessEntity().getStatusCode().value();
        assertThat(status).isEqualTo(400);
    }
}