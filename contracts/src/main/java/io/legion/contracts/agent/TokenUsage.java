package io.legion.contracts.agent;

/**
 * 单模型 token/成本用量（参照仓库 agent.go TokenUsage）。
 *
 * <p>成本是 provider 自己申报的金额刻度（1e-10 美元/tick），不是本地折算：
 * 阶梯计费（如 xAI 200K token 后 2x 计费）无法从聚合 token 数复现，
 * 必须原样保存 provider 的请求级数字。禁止 token 数 × 费率折算（AGENTS.md）。
 *
 * @param inputTokens      输入 token
 * @param outputTokens     输出 token
 * @param cacheReadTokens  缓存命中读 token
 * @param cacheWriteTokens 缓存写入 token
 * @param costUsdTicks     provider 申报成本，1e-10 USD/tick；0 = 未申报
 */
public record TokenUsage(long inputTokens, long outputTokens,
                         long cacheReadTokens, long cacheWriteTokens,
                         long costUsdTicks) {

    /** 1 USD = 10^10 ticks；int64 全程精确，sub-cent 成本不经过浮点漂移。 */
    public static final long COST_USD_TICKS_PER_USD = 10_000_000_000L;

    /** 任一字段非零即有效账单记录（provider 可能只报 token 或只报成本）。 */
    public boolean hasAnyTokens() {
        return inputTokens > 0 || outputTokens > 0
                || cacheReadTokens > 0 || cacheWriteTokens > 0 || costUsdTicks > 0;
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                cacheReadTokens + other.cacheReadTokens,
                cacheWriteTokens + other.cacheWriteTokens,
                // ACP 家族的 provider 成本是累计值，聚合取 max 而非求和（0 表示未申报）；
                // stream-json 家族按增量累加时 max 与 sum 等价（其中至少一边为 0）
                Math.max(costUsdTicks, other.costUsdTicks));
    }
}
