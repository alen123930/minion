package io.legion.contracts;

/**
 * POST /api/daemon/tasks/{id}/usage 载荷（设计 §4.2：usage 上报先于一切 early return）。
 * 成本字段是 1e-10 美元刻度的 provider 申报值，禁止 token×费率折算（AGENTS.md）。
 */
public record ReportUsageRequest(String sessionId,
                                 long inputTokens,
                                 long outputTokens,
                                 long costUsdTicks) {
}
