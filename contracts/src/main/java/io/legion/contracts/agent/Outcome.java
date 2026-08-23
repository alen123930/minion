package io.legion.contracts.agent;

import java.util.Map;

/**
 * 终态（设计 §6.1）：Success | Failure。fail-closed——只有显式的成功才是 Success；
 * 失败时 output 一律为空，部分 transcript 绝不允许冒充最终答案
 * （finalizeStreamResult 契约：失败上游的 fallback 会把"工具前旁白"当答案展示给用户）。
 *
 * <p>Failure 也携带 usage 与 sessionId：usage 先于任何 early-return 上报，
 * 计费不可漏（AGENTS.md 结算纪律）。
 */
public sealed interface Outcome {

    record Success(String output, Map<String, TokenUsage> usage, String sessionId)
            implements Outcome {
    }

    record Failure(BackendFailureReason reason, String message,
                   Map<String, TokenUsage> usage, String sessionId)
            implements Outcome {
    }
}
