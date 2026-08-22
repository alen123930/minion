package io.legion.contracts;

/** 终态上报：failed 的应答。语义同 {@link CompleteTaskResponse}。 */
public record FailTaskResponse(boolean applied) {
}