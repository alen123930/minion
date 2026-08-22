package io.legion.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 daemon↔server 协议的 JSON 形状（AGENTS.md：协议即产品契约）。
 * 端点路径不变、字段名不变——这里变了，下游 client 就会跟着漂。
 */
class DaemonProtocolDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void claimResponseSerializesTaskFields() throws Exception {
        AgentTaskRow task = new AgentTaskRow();
        task.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        task.setWorkspaceId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        task.setIssueId(UUID.fromString("00000000-0000-0000-0000-000000000003"));
        task.setAgentId(UUID.fromString("00000000-0000-0000-0000-000000000004"));
        task.setStatus("dispatched");
        task.setContext(mapper.readTree("{\"prompt\":\"hi\"}"));
        task.setPriority(5);

        String json = mapper.writeValueAsString(new ClaimTaskResponse(task));
        JsonNode root = mapper.readTree(json);

        assertEquals("00000000-0000-0000-0000-000000000001",
                root.path("task").path("id").asText());
        assertEquals("00000000-0000-0000-0000-000000000002",
                root.path("task").path("workspaceId").asText());
        assertEquals("dispatched", root.path("task").path("status").asText());
        assertEquals("hi", root.path("task").path("context").path("prompt").asText());
        assertEquals(5, root.path("task").path("priority").asInt());
    }

    @Test
    void claimResponseWithNullTaskRoundTrips() throws Exception {
        String json = mapper.writeValueAsString(new ClaimTaskResponse(null));
        ClaimTaskResponse resp = mapper.readValue(json, ClaimTaskResponse.class);
        assertNull(resp.task());
    }

    @Test
    void completeRequestCarriesJsonResult() throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.put("stub", "m0-worker");
        String json = mapper.writeValueAsString(new CompleteTaskRequest(result));
        CompleteTaskRequest req = mapper.readValue(json, CompleteTaskRequest.class);
        assertEquals("m0-worker", req.result().path("stub").asText());
    }

    @Test
    void failRequestCarriesErrorAndStableClass() throws Exception {
        String json = mapper.writeValueAsString(new FailTaskRequest("boom", "stub_failure"));
        FailTaskRequest req = mapper.readValue(json, FailTaskRequest.class);
        assertEquals("boom", req.error());
        assertEquals("stub_failure", req.failureClass());
    }

    @Test
    void terminalResponsesExposeAppliedFlag() {
        assertTrue(new CompleteTaskResponse(true).applied());
        assertFalse(new FailTaskResponse(false).applied());
    }
}