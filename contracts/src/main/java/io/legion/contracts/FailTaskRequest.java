package io.legion.contracts;

/**
 * 终态上报：failed 请求体。failureClass 是稳定失败类别（provider_network 等），
 * 非自由文本（AGENTS.md 失败归因纪律）——M0 端点在 DB 层只落这两个字段，
 * reason code 枚举化留给 M1。
 */
public record FailTaskRequest(String error, String failureClass) {
}