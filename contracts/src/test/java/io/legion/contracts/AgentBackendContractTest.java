package io.legion.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.legion.contracts.agent.AgentStreamEvent;
import io.legion.contracts.agent.BackendFailureReason;
import io.legion.contracts.agent.Outcome;
import io.legion.contracts.agent.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 agent 适配层契约（设计 §6.1/§6.4：接口放 contracts，server/daemon 共用）：
 * - 线格式 snake_case（与 DaemonProtocolDtoTest 同一纪律）
 * - TokenUsage 刻度常量：cost_usd_ticks 是 1e-10 美元刻度（AGENTS.md 硬规则）
 * - reason code 是稳定枚举，禁止从错误字符串解析——code 值本身就是协议
 */
class AgentBackendContractTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void taskMessagesRequestCarriesStreamEventFrames() throws Exception {
        String json = mapper.writeValueAsString(new TaskMessagesRequest(List.of(
                new StreamEvent("assistant_text", Map.of("text", "hi")),
                new StreamEvent("tool_use", Map.of("tool", "Bash")))));
        assertTrue(json.contains("\"events\""));
        assertTrue(json.contains("assistant_text"));
        TaskMessagesRequest req = mapper.readValue(json, TaskMessagesRequest.class);
        assertEquals(2, req.events().size());
        assertEquals("assistant_text", req.events().get(0).type());
    }

    @Test
    void reportUsageRequestSerializesSnakeCase() throws Exception {
        String json = mapper.writeValueAsString(
                new ReportUsageRequest("sess-1", 120L, 30L, 5_000_000_000L));
        assertTrue(json.contains("session_id"));
        assertTrue(json.contains("input_tokens"));
        assertTrue(json.contains("output_tokens"));
        assertTrue(json.contains("cost_usd_ticks"));
        ReportUsageRequest req = mapper.readValue(json, ReportUsageRequest.class);
        assertEquals(5_000_000_000L, req.costUsdTicks());
    }

    @Test
    void costTickScaleIsTenTicksPerNanodollar() {
        // 1e-10 美元刻度：1 USD = 10_000_000_000 ticks。int64 全程精确，
        // sub-cent 成本不经过 double 漂移（参照仓库 agent.go CostUSDTicksPerUSD）
        assertEquals(10_000_000_000L, TokenUsage.COST_USD_TICKS_PER_USD);
    }

    @Test
    void tokenUsageReportsPresence() {
        assertTrue(new TokenUsage(0, 1, 0, 0, 0).hasAnyTokens());
        assertTrue(new TokenUsage(0, 0, 7, 0, 0).hasAnyTokens());
        assertTrue(new TokenUsage(0, 0, 0, 9, 0).hasAnyTokens());
        assertFalse(new TokenUsage(0, 0, 0, 0, 0).hasAnyTokens());
        // 只有成本没有 token 也算有效账单记录（provider 只报钱不报 token 的形态）
        assertTrue(new TokenUsage(0, 0, 0, 0, 42).hasAnyTokens());
    }

    @Test
    void failureReasonCodesAreStableSnakeCase() {
        assertEquals("executable_not_found", BackendFailureReason.EXECUTABLE_NOT_FOUND.code());
        assertEquals("async_task_launched", BackendFailureReason.ASYNC_TASK_LAUNCHED.code());
        assertEquals("context_exhausted", BackendFailureReason.CONTEXT_EXHAUSTED.code());
        assertEquals("line_too_long", BackendFailureReason.LINE_TOO_LONG.code());
        assertEquals("no_result", BackendFailureReason.NO_RESULT.code());
        assertEquals("timeout", BackendFailureReason.TIMEOUT.code());
    }

    @Test
    void streamEventVariantsExposeWireType() {
        assertEquals("assistant_text", new AgentStreamEvent.AssistantText("hi").wireType());
        assertEquals("thinking", new AgentStreamEvent.Thinking("hmm").wireType());
        assertEquals("tool_use", new AgentStreamEvent.ToolUse("Bash", "c1", null).wireType());
        assertEquals("tool_result", new AgentStreamEvent.ToolResult("c1", "out").wireType());
        assertEquals("system", new AgentStreamEvent.SystemInfo("sess-1").wireType());
        assertEquals("usage", new AgentStreamEvent.Usage(Map.of()).wireType());
        assertEquals("log", new AgentStreamEvent.Log("info", "msg").wireType());
        assertEquals("end", new AgentStreamEvent.End().wireType());
    }

    @Test
    void outcomeFailureCarriesUsageForBilling() {
        // usage 先于任何 early-return 上报（AGENTS.md）——失败终态也必须带 usage
        Map<String, TokenUsage> usage = Map.of("claude-4", new TokenUsage(10, 5, 0, 0, 1));
        Outcome.Failure f = new Outcome.Failure(
                BackendFailureReason.NO_RESULT, "no result", usage, "sess-1");
        assertEquals(BackendFailureReason.NO_RESULT, f.reason());
        assertEquals(10, f.usage().get("claude-4").inputTokens());
        assertEquals("sess-1", f.sessionId());
        Outcome.Success s = new Outcome.Success("out", usage, "sess-1");
        assertEquals("out", s.output());
    }
}
