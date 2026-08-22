package io.legion.daemon.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.legion.contracts.AgentTaskRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DaemonClient 的协议契约测试：用 JDK 内置 HttpServer 扮演 server 端，
 * 校验请求路径/方法/JSON 体与响应解析。协议形状由 contracts 测试钉住，这里钉传输。
 */
class DaemonClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpServer server;
    private DaemonClient client;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private String claimResponse = "{\"task\":null}";
    private String terminalResponse = "{\"applied\":true}";
    private int statusCode = 200;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/daemon/tasks", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = exchange.getRequestURI().getPath().endsWith("/claim")
                    ? claimResponse : terminalResponse;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new DaemonClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void claimParsesReturnedTask() {
        claimResponse = "{\"task\":{\"id\":\"00000000-0000-0000-0000-000000000001\","
                + "\"workspace_id\":\"00000000-0000-0000-0000-000000000002\","
                + "\"issue_id\":\"00000000-0000-0000-0000-000000000003\","
                + "\"agent_id\":\"00000000-0000-0000-0000-000000000004\","
                + "\"status\":\"dispatched\",\"context\":{\"prompt\":\"hi\"},\"priority\":5}}";

        AgentTaskRow task = client.claim();

        assertEquals("00000000-0000-0000-0000-000000000001", task.getId().toString());
        assertEquals("00000000-0000-0000-0000-000000000002", task.getWorkspaceId().toString());
        assertEquals("dispatched", task.getStatus());
        assertEquals("hi", task.getContext().path("prompt").asText());
    }

    @Test
    void claimReturnsNullWhenQueueEmpty() {
        assertNull(client.claim());
    }

    @Test
    void completePostsResultToTaskPath() {
        ObjectNode result = mapper.createObjectNode();
        result.put("stub", "stub-marker");
        UUID taskId = UUID.randomUUID();

        boolean applied = client.complete(taskId, result);

        assertTrue(applied);
        assertEquals("/api/daemon/tasks/" + taskId + "/complete", lastPath.get());
        assertTrue(lastBody.get().contains("stub-marker"));
    }

    @Test
    void failPostsErrorAndFailureClass() {
        UUID taskId = UUID.randomUUID();

        boolean applied = client.fail(taskId, "boom", "stub_failure");

        assertTrue(applied);
        assertEquals("/api/daemon/tasks/" + taskId + "/fail", lastPath.get());
        assertTrue(lastBody.get().contains("boom"));
        // 线格式 snake_case：failureClass → failure_class
        assertTrue(lastBody.get().contains("failure_class"));
    }

    @Test
    void terminalReportOnAlreadyTerminalTaskIsSuccessNotError() {
        terminalResponse = "{\"applied\":false}";
        ObjectNode result = mapper.createObjectNode();
        result.put("stub", "x");

        assertFalse(client.complete(UUID.randomUUID(), result));
    }

    @Test
    void non2xxThrows() {
        statusCode = 500;
        assertThrows(DaemonClientException.class, client::claim);
    }
}