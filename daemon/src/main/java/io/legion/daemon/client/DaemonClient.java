package io.legion.daemon.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.legion.contracts.AgentTaskRow;
import io.legion.contracts.ClaimTaskResponse;
import io.legion.contracts.CompleteTaskRequest;
import io.legion.contracts.CompleteTaskResponse;
import io.legion.contracts.FailTaskRequest;
import io.legion.contracts.FailTaskResponse;
import io.legion.contracts.ReportUsageRequest;
import io.legion.contracts.StreamEvent;
import io.legion.contracts.TaskMessagesRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * daemon↔server 协议的 HTTP client（设计 §二：M0 起就走 HTTP，即使同进程）。
 * 用 JDK 内置 HttpClient 而非 Spring 的 client——daemon 模块保持零 web 依赖，
 * M1 拆独立进程时这个类原样带走。
 */
public class DaemonClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI base;

    public DaemonClient(URI base) {
        this.base = base;
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                // 契约字段统一 snake_case（server 侧全局 jackson 策略，M0-4 §4.2）——
                // DTO 保持 camelCase，线格式对齐，否则 failureClass ↔ failure_class 对不上
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                // 协议演进时 server 可能多带字段，未知字段不允许炸掉旧 client
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** 认领下一个任务；队列为空返回 null。 */
    public AgentTaskRow claim() {
        HttpResponse<String> resp = post("/api/daemon/tasks/claim", "{}");
        try {
            return json.readValue(resp.body(), ClaimTaskResponse.class).task();
        } catch (IOException e) {
            throw new DaemonClientException("claim 响应解析失败", e);
        }
    }

    /** 上报 completed（幂等，见设计 §3.5）；返回本次是否真的落库。 */
    public boolean complete(UUID taskId, JsonNode result) {
        try {
            String body = json.writeValueAsString(new CompleteTaskRequest(result));
            HttpResponse<String> resp = post("/api/daemon/tasks/" + taskId + "/complete", body);
            return json.readValue(resp.body(), CompleteTaskResponse.class).applied();
        } catch (IOException e) {
            throw new DaemonClientException("complete 响应解析失败", e);
        }
    }

    /** 上报 failed（幂等）；返回本次是否真的落库。 */
    public boolean fail(UUID taskId, String error, String failureClass) {
        try {
            String body = json.writeValueAsString(new FailTaskRequest(error, failureClass));
            HttpResponse<String> resp = post("/api/daemon/tasks/" + taskId + "/fail", body);
            return json.readValue(resp.body(), FailTaskResponse.class).applied();
        } catch (IOException e) {
            throw new DaemonClientException("fail 响应解析失败", e);
        }
    }

    /** 流式事件批次转发（设计 §4.2 messages 端点；server 映射成 SSE task:message）。 */
    public void messages(UUID taskId, List<StreamEvent> events) {
        post("/api/daemon/tasks/" + taskId + "/messages",
                writeBody(new TaskMessagesRequest(events)));
    }

    /** usage 上报——先于一切 early return 的计费路径（设计 §4.2/§5.3）。 */
    public void reportUsage(UUID taskId, ReportUsageRequest request) {
        post("/api/daemon/tasks/" + taskId + "/usage", writeBody(request));
    }

    private String writeBody(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (IOException e) {
            throw new DaemonClientException("请求序列化失败: " + payload.getClass().getSimpleName(), e);
        }
    }

    private HttpResponse<String> post(String path, String body) {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new DaemonClientException("POST " + path + " 传输失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DaemonClientException("POST " + path + " 被中断", e);
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new DaemonClientException(
                    "POST " + path + " 返回 " + resp.statusCode() + ": " + resp.body());
        }
        return resp;
    }
}