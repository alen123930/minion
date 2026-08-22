package io.legion.contracts;

/**
 * 终态上报：completed 的应答。applied=false 表示任务此前已被终态化
 * （重试/取消路径抢先），按设计 §3.5 仍是成功返回——HTTP 重试天然幂等。
 */
public record CompleteTaskResponse(boolean applied) {
}