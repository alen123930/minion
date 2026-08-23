package io.legion.daemon.agent;

import io.legion.contracts.agent.AgentStreamEvent;
import io.legion.contracts.agent.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * stream-json 解析器语义锚点：参照仓库 claude.go handleAssistant/handleUser/
 * claudeResultUsage + claude_test.go 的 fixture 形状。
 */
class ClaudeStreamParserTest {

    private final ClaudeStreamParser parser = new ClaudeStreamParser("fallback-model");

    private List<AgentStreamEvent> feed(String... lines) {
        var all = new java.util.ArrayList<AgentStreamEvent>();
        for (String line : lines) {
            all.addAll(parser.feed(line));
        }
        return all;
    }

    @Test
    void systemEventYieldsSessionId() {
        feed("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"sess-1\",\"model\":\"claude-sonnet-4\"}");
        assertEquals("sess-1", parser.sessionId());
        // 后续 system 事件更新 session id（resume 场景 CLI 可能换 id）
        feed("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"sess-2\"}");
        assertEquals("sess-2", parser.sessionId());
    }

    @Test
    void assistantTextThinkingAndToolUseBecomeEvents() {
        List<AgentStreamEvent> events = feed(
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"model\":\"m1\",\"content\":["
                        + "{\"type\":\"thinking\",\"text\":\"pondering\"},"
                        + "{\"type\":\"text\",\"text\":\"partial answer\"},"
                        + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}"
                        + "]}}");
        assertEquals(3, events.size());
        assertEquals("pondering", ((AgentStreamEvent.Thinking) events.get(0)).text());
        assertEquals("partial answer", ((AgentStreamEvent.AssistantText) events.get(1)).text());
        AgentStreamEvent.ToolUse tool = (AgentStreamEvent.ToolUse) events.get(2);
        assertEquals("Bash", tool.tool());
        assertEquals("t1", tool.callId());
    }

    @Test
    void userToolResultBecomesEvent() {
        List<AgentStreamEvent> events = feed(
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":["
                        + "{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"file list\"}]}}");
        AgentStreamEvent.ToolResult r = (AgentStreamEvent.ToolResult) events.get(0);
        assertEquals("t1", r.callId());
        assertEquals("file list", r.output());
        assertFalse(parser.sawAsyncLaunch());
    }

    @Test
    void asyncLaunchedStatusInToolResultContentTriggersBan() {
        // 异步任务禁令：后台任务逃逸生命周期控制（设计 §6.2 决策 1）
        feed("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":["
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":"
                + "{\"status\":\"async_launched\",\"task_id\":\"bg-9\"}}]}}");
        assertTrue(parser.sawAsyncLaunch());

        ClaudeStreamParser nested = new ClaudeStreamParser(null);
        // content 是块数组、status 藏在子块里的形态也要识别
        nested.feed("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":["
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"started\"},"
                + "{\"type\":\"object\",\"status\":\"async_launched\"}]}]}}");
        assertTrue(nested.sawAsyncLaunch());
    }

    @Test
    void invalidJsonLineIsCountedAndSkipped() {
        List<AgentStreamEvent> events = feed("this is not json", "   ");
        assertTrue(events.isEmpty());
        assertEquals(1, parser.invalidEventCount());
        assertEquals(0, parser.eventCount());
    }

    @Test
    void resultEventCapturesRequestLevelUsageModelUsagePreferred() {
        // 决策 4：成本请求级记录——从 result 事件原样取，禁止 token 数乘费率折算
        List<AgentStreamEvent> events = feed(
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"model\":\"wrong-bucket\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                        + "\"usage\":{\"input_tokens\":5,\"output_tokens\":5}}}",
                "{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"sess-1\","
                        + "\"result\":\"final\",\"is_error\":false,"
                        + "\"usage\":{\"input_tokens\":100,\"output_tokens\":40,"
                        + "\"cache_read_input_tokens\":10,\"cache_creation_input_tokens\":5},"
                        + "\"modelUsage\":{\"claude-sonnet-4\":{\"inputTokens\":300,\"outputTokens\":60,"
                        + "\"cacheReadInputTokens\":30,\"cacheCreationInputTokens\":15}},"
                        + "\"model\":\"claude-sonnet-4\"}");
        assertTrue(parser.sawResult());
        assertEquals("final", parser.resultText());
        assertFalse(parser.resultIsError());
        assertEquals("sess-1", parser.sessionId());
        // modelUsage 优先于 usage 与 assistant 轮累计（claudeResultUsage 契约）
        TokenUsage u = parser.usage().get("claude-sonnet-4");
        assertEquals(300, u.inputTokens());
        assertEquals(60, u.outputTokens());
        assertEquals(30, u.cacheReadTokens());
        assertEquals(15, u.cacheWriteTokens());
        assertEquals(1, parser.usage().size());
        // result 事件也向事件流发 usage 快照
        assertTrue(events.get(events.size() - 1) instanceof AgentStreamEvent.Usage);
    }

    @Test
    void resultUsageFallsBackToUsageFieldWithModelAttribution() {
        feed("{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"ok\","
                + "\"usage\":{\"input_tokens\":7,\"output_tokens\":3}}");
        // 无 model 字段 → 用构造时传入的 fallback model 归因
        TokenUsage u = parser.usage().get("fallback-model");
        assertEquals(7, u.inputTokens());
        assertEquals(3, u.outputTokens());
    }

    @Test
    void zeroUsageEntriesAreNotReported() {
        // claudeUsageHasTokens：全零桶视为未申报，不进 usage map
        feed("{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"ok\","
                + "\"modelUsage\":{\"ghost\":{\"inputTokens\":0,\"outputTokens\":0}}}");
        assertTrue(parser.usage().isEmpty());
    }

    @Test
    void fallbackAnswerDroppedAfterToolUseOrUnreadableTurn() {
        // #6006：工具前旁白不许冒充最终答案。工具轮/不可读轮清空 fallback，
        // thinking-only 轮（无工具无文本）不动已有 fallback
        feed("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"model\":\"m\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"narration before tool\"}]}}");
        assertEquals("narration before tool", parser.lastAssistantText());
        feed("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"model\":\"m\","
                + "\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\",\"input\":{}}]}}");
        assertEquals("", parser.lastAssistantText());
        // thinking-only 轮保持空 fallback 不变
        feed("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"model\":\"m\","
                + "\"content\":[{\"type\":\"thinking\",\"text\":\"just thinking\"}]}}");
        assertEquals("", parser.lastAssistantText());
    }

    @Test
    void unknownContentBlockMarksTurnUnreadableSoFallbackDrops() {
        feed("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"model\":\"m\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"answer\"}]}}");
        // 读不懂的块类型可能正携带答案，不能让旧 fallback 顶替（understood=false）
        feed("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"model\":\"m\","
                + "\"content\":[{\"type\":\"server_tool_use\",\"name\":\"web_search\"}]}}");
        assertEquals("", parser.lastAssistantText());
    }

    @Test
    void terminalReasonPromptTooLongIsCaptured() {
        // GH #6402：is_error 与 terminal_reason 同时到达时只有后者不依赖 CLI 措辞
        feed("{\"type\":\"result\",\"subtype\":\"error\",\"result\":\"context is full\","
                + "\"is_error\":true,\"terminal_reason\":\"prompt_too_long\"}");
        assertTrue(parser.contextExhausted());
        assertTrue(parser.resultIsError());
    }

    @Test
    void logEventIsForwarded() {
        List<AgentStreamEvent> events = feed(
                "{\"type\":\"log\",\"log\":{\"level\":\"info\",\"message\":\"loading session\"}}");
        AgentStreamEvent.Log log = (AgentStreamEvent.Log) events.get(0);
        assertEquals("info", log.level());
        assertEquals("loading session", log.message());
    }
}
