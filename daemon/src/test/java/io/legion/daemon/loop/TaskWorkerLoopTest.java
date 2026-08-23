package io.legion.daemon.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.legion.contracts.agent.AgentStreamEvent;
import io.legion.contracts.agent.BackendException;
import io.legion.contracts.agent.BackendFailureReason;
import io.legion.contracts.agent.Outcome;
import io.legion.daemon.agent.FakeBackend;
import io.legion.daemon.client.DaemonClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * worker 全流程体：认领 → backend 执行 → messages 转发 → usage 先报 → 终态。
 * 顺序纪律（设计 §5.3）：usage 先于终态上报；终态携带稳定 failure_class。
 */
class TaskWorkerLoopTest {

    private HttpServer server;
    private TaskWorkerLoop loop;
    private final ObjectMapper json = new ObjectMapper();
    private final CopyOnWriteArrayList<String> requestLog = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> bodies = new CopyOnWriteArrayList<>();
    private String claimResponse = "{\"task\":null}";
    private int claimStatus = 200;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/daemon/tasks", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] resp;
            if (path.endsWith("/claim")) {
                requestLog.add("claim");
                resp = claimResponse.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(claimStatus, resp.length);
            } else if (path.endsWith("/messages")) {
                requestLog.add("messages");
                bodies.add(body);
                resp = "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resp.length);
            } else if (path.endsWith("/usage")) {
                requestLog.add("usage");
                bodies.add(body);
                resp = "{\"applied\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resp.length);
            } else if (path.endsWith("/complete")) {
                requestLog.add("complete");
                bodies.add(body);
                resp = "{\"applied\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resp.length);
            } else if (path.endsWith("/fail")) {
                requestLog.add("fail");
                bodies.add(body);
                resp = "{\"applied\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resp.length);
            } else {
                requestLog.add("unexpected:" + path);
                resp = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, resp.length);
            }
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        DaemonClient client = new DaemonClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        loop = new TaskWorkerLoop(client, new FakeBackend(), Duration.ofMillis(10));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void queueTaskWithPrompt(String prompt) {
        claimResponse = "{\"task\":{\"id\":\"" + UUID.randomUUID()
                + "\",\"status\":\"dispatched\",\"context\":{\"prompt\":"
                + json.valueToTree(prompt) + "}}}";
    }

    @Test
    void pollRunsBackendForwardsMessagesThenUsageThenComplete() throws Exception {
        queueTaskWithPrompt("hello there");

        loop.poll();

        // FakeBackend 流出 2 条 assistant_text + 1 条 usage → 3 个 messages 批次
        assertEquals(List.of("claim", "messages", "messages", "messages", "usage", "complete"),
                requestLog);
        StringBuilder allEvents = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            allEvents.append(bodies.get(i));
        }
        String messagesBody = bodies.get(0);
        JsonNode events = json.readTree(messagesBody).path("events");
        assertTrue(events.isArray() && events.size() == 1, "events: " + messagesBody);
        assertEquals("assistant_text", events.get(0).path("type").asText());
        assertTrue(allEvents.toString().contains("hello there"));

        String usageBody = bodies.get(3);
        assertTrue(usageBody.contains("input_tokens"), usageBody);
        assertTrue(usageBody.contains("cost_usd_ticks"), usageBody);

        String completeBody = bodies.get(4);
        assertTrue(completeBody.contains("output"), completeBody);
        assertTrue(completeBody.contains("session_id"), completeBody);
    }

    @Test
    void failingBackendReportsFailWithStableReasonClass() throws Exception {
        queueTaskWithPrompt("doomed");
        loop = new TaskWorkerLoop(new DaemonClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort())),
                FakeBackend.failing(BackendFailureReason.NO_RESULT, "stream ended without terminal result"),
                Duration.ofMillis(10));

        loop.poll();

        // 失败路径同样 usage 先报（计费不可漏），再 fail 带稳定 failure_class
        assertEquals(List.of("claim", "messages", "messages", "messages", "usage", "fail"),
                requestLog);
        String failBody = bodies.get(bodies.size() - 1);
        assertTrue(failBody.contains("no_result"), failBody);
        assertTrue(failBody.contains("without terminal result"), failBody);
    }

    /**
     * usage 先于 early-return（设计 §5.3 / 参照 daemon.go:5086）：
     * 结算异常路径（outcome 异常完成——泵线程病态死亡等）已产生的 usage
     * 必须照报，计费不可漏；且不许 complete/fail 冒充终态（任务滞留 dispatched）。
     */
    @Test
    void settlementExceptionStillReportsProducedUsage() throws Exception {
        queueTaskWithPrompt("partial work");
        io.legion.contracts.agent.TokenUsage produced =
                new io.legion.contracts.agent.TokenUsage(31, 13, 0, 0, 777_000_000L);
        loop = new TaskWorkerLoop(new DaemonClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort())),
                new io.legion.contracts.agent.AgentBackend() {
                    @Override
                    public String provider() {
                        return "exploding";
                    }

                    @Override
                    public io.legion.contracts.agent.Session execute(
                            io.legion.contracts.agent.ExecRequest request) {
                        var events = new java.util.concurrent.LinkedBlockingQueue<AgentStreamEvent>();
                        var outcome = new java.util.concurrent.CompletableFuture<Outcome>();
                        Thread runner = new Thread(() -> {
                            events.offer(new AgentStreamEvent.SystemInfo("err-session"));
                            events.offer(new AgentStreamEvent.Usage(
                                    java.util.Map.of("boom-model", produced)));
                            events.offer(new AgentStreamEvent.End());
                            outcome.completeExceptionally(
                                    new IllegalStateException("pump thread died"));
                        }, "legion-exploding-agent");
                        runner.setDaemon(true);
                        runner.start();
                        return new io.legion.contracts.agent.Session(events, outcome);
                    }
                },
                Duration.ofMillis(10));

        loop.poll();

        assertEquals(List.of("claim", "messages", "messages", "usage"), requestLog);
        JsonNode usage = json.readTree(bodies.get(bodies.size() - 1));
        assertEquals("err-session", usage.path("session_id").asText());
        assertEquals(31, usage.path("input_tokens").asLong());
        assertEquals(13, usage.path("output_tokens").asLong());
        assertEquals(777_000_000L, usage.path("cost_usd_ticks").asLong());
    }

    @Test
    void launchFailureFailsTaskWithoutUsageCall() {
        queueTaskWithPrompt("no cli here");
        loop = new TaskWorkerLoop(new DaemonClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort())),
                new io.legion.contracts.agent.AgentBackend() {
                    @Override
                    public String provider() {
                        return "throwing";
                    }

                    @Override
                    public io.legion.contracts.agent.Session execute(
                            io.legion.contracts.agent.ExecRequest request) {
                        throw new BackendException(BackendFailureReason.EXECUTABLE_NOT_FOUND,
                                "claude executable not found at \"claude\"");
                    }
                },
                Duration.ofMillis(10));

        loop.poll();

        assertEquals(List.of("claim", "fail"), requestLog);
        assertTrue(bodies.get(0).contains("executable_not_found"));
    }

    @Test
    void pollSkipsWhenQueueEmpty() {
        loop.poll();
        assertEquals(List.of("claim"), requestLog);
    }

    @Test
    void pollSurvivesClaimFailure() {
        claimStatus = 500;
        // 瞬态失败吞掉留给下一轮——不能让调度线程死掉
        loop.poll();
        assertEquals(List.of("claim"), requestLog);
    }

    @Test
    void outcomeWaitDerivesFromTotalTimeout() {
        // outcome 等待必须比 total-timeout 长（看门狗杀树后 outcome 才完成），
        // 否则任务先被判"结算异常"滞留 dispatched。默认 total=30m 时 15m 硬编码会提前抛
        assertEquals(Duration.ofMinutes(30).plusSeconds(30), TaskWorkerLoop.outcomeWait(
                new io.legion.contracts.agent.ExecOptions(null, null, null,
                        Duration.ofMinutes(30), null, null, null)));
        assertEquals(Duration.ofMinutes(15), TaskWorkerLoop.outcomeWait(
                io.legion.contracts.agent.ExecOptions.defaults()));
    }
}
