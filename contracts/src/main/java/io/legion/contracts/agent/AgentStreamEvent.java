package io.legion.contracts.agent;

/**
 * backend 流式事件（设计 §6.1 的 StreamEvent；为避免与 SSE 帧契约
 * io.legion.contracts.StreamEvent 同名，落地为 AgentStreamEvent）。
 * sealed + BlockingQueue 携带，End 哨兵收尾（设计 §6.1：队列不能 close）。
 */
public sealed interface AgentStreamEvent {

    /** 线类型名（snake_case，SSE 转发与 daemon→server messages 批次共用）。 */
    String wireType();

    record AssistantText(String text) implements AgentStreamEvent {
        @Override
        public String wireType() {
            return "assistant_text";
        }
    }

    record Thinking(String text) implements AgentStreamEvent {
        @Override
        public String wireType() {
            return "thinking";
        }
    }

    /** @param input 工具入参（JSON 对象，已解析；可能为 null）。 */
    record ToolUse(String tool, String callId, Object input) implements AgentStreamEvent {
        @Override
        public String wireType() {
            return "tool_use";
        }
    }

    record ToolResult(String callId, String output) implements AgentStreamEvent {
        @Override
        public String wireType() {
            return "tool_result";
        }
    }

    /** system 事件携带的会话 id——尽早固定 resume 指针用。 */
    record SystemInfo(String sessionId) implements AgentStreamEvent {
        @Override
        public String wireType() {
            return "system";
        }
    }

    /** result 事件解析出的请求级用量（按模型分桶，原样上报不折算）。 */
    record Usage(java.util.Map<String, TokenUsage> usage) implements AgentStreamEvent {
        @Override
        public String wireType() {
            return "usage";
        }
    }

    record Log(String level, String message) implements AgentStreamEvent {
        @Override
        public String wireType() {
            return "log";
        }
    }

    /** 流结束哨兵：事件队列到此为止。 */
    record End() implements AgentStreamEvent {
        @Override
        public String wireType() {
            return "end";
        }
    }
}
