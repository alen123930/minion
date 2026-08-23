package io.legion.daemon.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legion.contracts.agent.AgentStreamEvent;
import io.legion.contracts.agent.TokenUsage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * claude CLI stream-json 单行解析器（参照仓库 claude.go 的 SDK 消息处理）。
 * 纯函数式状态机：一行进、事件出；终态字段由 getter 暴露给
 * {@link FinalizeStreamResult} 判定。不接触进程/IO——子进程编排见 ClaudeBackend。
 *
 * <p>usage 语义（决策 4）：assistant 轮增量累计、result 事件整批覆盖
 * （modelUsage 优先）；成本只取 provider 申报的 cost_usd_ticks，绝不折算。
 */
final class ClaudeStreamParser {

    /** GH #6402：唯一被认可的 terminal_reason——上下文耗尽且压缩无法恢复。 */
    static final String TERMINAL_REASON_PROMPT_TOO_LONG = "prompt_too_long";

    private final ObjectMapper json = new ObjectMapper();
    private final String fallbackModel;

    private String sessionId;
    private String lastAssistantText = "";
    private String resultText;
    private boolean sawResult;
    private boolean resultIsError;
    private boolean contextExhausted;
    private boolean sawAsyncLaunch;
    private Map<String, TokenUsage> usage = new LinkedHashMap<>();
    private int eventCount;
    private int invalidEventCount;

    ClaudeStreamParser(String fallbackModel) {
        this.fallbackModel = fallbackModel;
    }

    /** 喂一行（原始字符串，不含换行符）；返回本行产生的事件。 */
    List<AgentStreamEvent> feed(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        JsonNode msg;
        try {
            msg = json.readTree(trimmed);
        } catch (Exception e) {
            // 单行坏帧不终止会话（CLI 升级可能插入非 JSON 横幅），计数留给日志观测
            invalidEventCount++;
            return List.of();
        }
        if (!msg.isObject()) {
            invalidEventCount++;
            return List.of();
        }
        eventCount++;
        String type = msg.path("type").asText("");
        switch (type) {
            case "system":
                return handleSystem(msg);
            case "assistant":
                return handleAssistant(msg);
            case "user":
                return handleUser(msg);
            case "result":
                return handleResult(msg);
            case "log":
                return handleLog(msg);
            default:
                // 未知顶层类型：观测计数，不判失败（协议演进容忍）
                return List.of();
        }
    }

    private List<AgentStreamEvent> handleSystem(JsonNode msg) {
        String sid = msg.path("session_id").asText("");
        if (!sid.isEmpty()) {
            sessionId = sid;
            return List.of(new AgentStreamEvent.SystemInfo(sessionId));
        }
        return List.of();
    }

    private List<AgentStreamEvent> handleAssistant(JsonNode msg) {
        List<AgentStreamEvent> events = new ArrayList<>();
        JsonNode message = msg.get("message");
        if (message == null || !message.isObject()) {
            applyFallback(null, 0);
            return events;
        }
        JsonNode content = message.get("content");
        if (content == null || !content.isArray()) {
            applyFallback(null, 0);
            return events;
        }

        // assistant 轮增量 usage（按模型分桶累计）
        accumulateUsage(message);

        StringBuilder text = new StringBuilder();
        int toolUses = 0;
        boolean understood = true;
        for (JsonNode block : content) {
            String blockType = block.path("type").asText("");
            switch (blockType) {
                case "text":
                    String t = block.path("text").asText("");
                    if (!t.isEmpty()) {
                        text.append(t);
                        events.add(new AgentStreamEvent.AssistantText(t));
                    }
                    break;
                case "thinking":
                    String th = block.path("text").asText("");
                    if (!th.isEmpty()) {
                        events.add(new AgentStreamEvent.Thinking(th));
                    }
                    break;
                case "tool_use":
                    toolUses++;
                    JsonNode input = block.get("input");
                    events.add(new AgentStreamEvent.ToolUse(
                            block.path("name").asText(""),
                            block.path("id").asText(""),
                            input == null ? null : input.toString()));
                    break;
                default:
                    // 读不懂的块可能正携带答案（server_tool_use 等），
                    // 不能让旧 fallback 顶替这一轮（understood=false）
                    understood = false;
                    break;
            }
        }
        applyFallback(understood && (toolUses == 0) && text.length() > 0 ? text.toString() : null,
                toolUses);
        if (!understood) {
            lastAssistantText = "";
        }
        return events;
    }

    /**
     * fallback 推进规则（参照仓库 assistantTurn.resolveFallback，#6006）：
     * 工具轮是中间轮；不可读轮信息量为零。两者都清空 fallback，防止
     * "工具前旁白"在终态缺文本时被当作最终答案展示。
     */
    private void applyFallback(String text, int toolUses) {
        if (toolUses > 0) {
            lastAssistantText = "";
        } else if (text != null) {
            lastAssistantText = text;
        }
        // text == null 且无工具：thinking-only 或不可读轮，保持原 fallback
    }

    private List<AgentStreamEvent> handleUser(JsonNode msg) {
        List<AgentStreamEvent> events = new ArrayList<>();
        JsonNode content = msg.path("message").path("content");
        if (!content.isArray()) {
            return events;
        }
        for (JsonNode block : content) {
            if (!"tool_result".equals(block.path("type").asText())) {
                continue;
            }
            JsonNode raw = block.get("content");
            String output = raw == null ? "" : raw.toString();
            if (raw != null && raw.isTextual()) {
                output = raw.asText();
            }
            events.add(new AgentStreamEvent.ToolResult(
                    block.path("tool_use_id").asText(""), output));
            if (hasAsyncLaunchStatus(raw)) {
                sawAsyncLaunch = true;
            }
        }
        return events;
    }

    /**
     * async_launched 识别（照抄参照 claude.go claudeToolResultHasAsyncLaunch 三形态）：
     * (a) content 对象顶层 status；(b) content 是数组、任一子块带 status；
     * (c) content 对象内嵌 content 数组携带 status。漏 (c) 会让后台任务
     * 逃过禁令（评审 major：参照能拦、旧实现放行）。
     */
    private boolean hasAsyncLaunchStatus(JsonNode raw) {
        if (raw == null) {
            return false;
        }
        if (raw.isObject()) {
            if ("async_launched".equals(raw.path("status").asText())) {
                return true;
            }
            JsonNode nested = raw.get("content");
            return nested != null && nested.isArray() && arrayHasAsyncLaunchStatus(nested);
        }
        return raw.isArray() && arrayHasAsyncLaunchStatus(raw);
    }

    private boolean arrayHasAsyncLaunchStatus(JsonNode array) {
        for (JsonNode item : array) {
            if (item.isObject()
                    && "async_launched".equals(item.path("status").asText())) {
                return true;
            }
        }
        return false;
    }

    private List<AgentStreamEvent> handleResult(JsonNode msg) {
        sawResult = true;
        resultText = msg.path("result").asText("");
        resultIsError = msg.path("is_error").asBoolean(false);
        contextExhausted = TERMINAL_REASON_PROMPT_TOO_LONG
                .equals(msg.path("terminal_reason").asText("").trim());
        String sid = msg.path("session_id").asText("");
        if (!sid.isEmpty()) {
            sessionId = sid;
        }
        Map<String, TokenUsage> resultUsage = resultUsage(msg);
        if (!resultUsage.isEmpty()) {
            usage = resultUsage;
        }
        List<AgentStreamEvent> events = new ArrayList<>();
        events.add(new AgentStreamEvent.Usage(usage));
        return events;
    }

    private List<AgentStreamEvent> handleLog(JsonNode msg) {
        JsonNode log = msg.get("log");
        if (log == null || !log.isObject()) {
            return List.of();
        }
        return List.of(new AgentStreamEvent.Log(
                log.path("level").asText("info"),
                log.path("message").asText("")));
    }

    /** assistant 轮 usage：message.usage + message.model 归因。 */
    private void accumulateUsage(JsonNode message) {
        String model = message.path("model").asText("");
        JsonNode u = message.get("usage");
        if (model.isEmpty() || u == null || !u.isObject()) {
            return;
        }
        TokenUsage delta = new TokenUsage(
                u.path("input_tokens").asLong(0),
                u.path("output_tokens").asLong(0),
                u.path("cache_read_input_tokens").asLong(0),
                u.path("cache_creation_input_tokens").asLong(0),
                0);
        if (!delta.hasAnyTokens()) {
            return;
        }
        usage.merge(model, delta, TokenUsage::plus);
    }

    /**
     * result 事件 usage（claudeResultUsage 契约）：modelUsage（camelCase 字段）
     * 优先；全零桶与空模型名跳过；空则回落 usage 字段 + model/fallback 归因。
     */
    private Map<String, TokenUsage> resultUsage(JsonNode msg) {
        Map<String, TokenUsage> result = new LinkedHashMap<>();
        JsonNode modelUsage = msg.get("modelUsage");
        if (modelUsage != null && modelUsage.isObject()) {
            modelUsage.fields().forEachRemaining(e -> {
                JsonNode u = e.getValue();
                TokenUsage t = new TokenUsage(
                        u.path("inputTokens").asLong(0),
                        u.path("outputTokens").asLong(0),
                        u.path("cacheReadInputTokens").asLong(0),
                        u.path("cacheCreationInputTokens").asLong(0),
                        u.path("costUsdTicks").asLong(0));
                if (!e.getKey().isEmpty() && t.hasAnyTokens()) {
                    result.put(e.getKey(), t);
                }
            });
            if (!result.isEmpty()) {
                return result;
            }
        }
        JsonNode u = msg.get("usage");
        String model = msg.path("model").asText(fallbackModel == null ? "" : fallbackModel);
        if (u == null || !u.isObject() || model.isEmpty()) {
            return result;
        }
        TokenUsage t = new TokenUsage(
                u.path("input_tokens").asLong(0),
                u.path("output_tokens").asLong(0),
                u.path("cache_read_input_tokens").asLong(0),
                u.path("cache_creation_input_tokens").asLong(0),
                u.path("cost_usd_ticks").asLong(0));
        if (t.hasAnyTokens()) {
            result.put(model, t);
        }
        return result;
    }

    public String sessionId() {
        return sessionId;
    }

    public String lastAssistantText() {
        return lastAssistantText;
    }

    public String resultText() {
        return resultText == null ? "" : resultText;
    }

    public boolean sawResult() {
        return sawResult;
    }

    public boolean resultIsError() {
        return resultIsError;
    }

    public boolean contextExhausted() {
        return contextExhausted;
    }

    public boolean sawAsyncLaunch() {
        return sawAsyncLaunch;
    }

    public Map<String, TokenUsage> usage() {
        return usage;
    }

    public int eventCount() {
        return eventCount;
    }

    public int invalidEventCount() {
        return invalidEventCount;
    }
}
