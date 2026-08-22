package io.legion.daemon.loop;

import com.sun.net.httpserver.HttpServer;
import io.legion.daemon.client.DaemonClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轮询循环的语义：一次 poll = 认领 + 状态流转桩（完成）。队列空则跳过完成；
 * 认领失败要吞掉异常继续下一轮，不能把调度线程打停（scheduleWithFixedDelay
 * 对抛出的异常会取消后续执行）。
 */
class TaskWorkerLoopTest {

    private HttpServer server;
    private TaskWorkerLoop loop;
    private final CopyOnWriteArrayList<String> claimedIds = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> completeBody = new AtomicReference<>();
    private String claimResponse = "{\"task\":null}";
    private int claimStatus = 200;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/daemon/tasks", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            if (path.endsWith("/claim")) {
                body = claimResponse.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(claimStatus, body.length);
            } else {
                completeBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String[] segments = path.split("/");
                claimedIds.add(segments[segments.length - 2]);
                body = "{\"applied\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
            }
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        DaemonClient client = new DaemonClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        loop = new TaskWorkerLoop(client, Duration.ofMillis(10));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void pollClaimsThenCompletesStub() {
        UUID taskId = UUID.randomUUID();
        claimResponse = "{\"task\":{\"id\":\"" + taskId
                + "\",\"status\":\"queued\",\"context\":{}}}";

        loop.poll();

        assertEquals(1, claimedIds.size());
        assertEquals(taskId.toString(), claimedIds.get(0));
        assertTrue(completeBody.get().contains("m0-worker"));
    }

    @Test
    void pollSkipsCompleteWhenQueueEmpty() {
        loop.poll();
        assertEquals(0, claimedIds.size());
    }

    @Test
    void pollSurvivesClaimFailure() {
        claimStatus = 500;
        // 不应抛异常：认领瞬态失败要留给下一轮
        loop.poll();
    }
}