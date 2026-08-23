package io.legion.contracts.agent;

/** 启动阶段失败的载体：reason 在阻塞分支一次决定、逐层透传原值。 */
public class BackendException extends RuntimeException {

    private final BackendFailureReason reason;

    public BackendException(BackendFailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public BackendException(BackendFailureReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public BackendFailureReason reason() {
        return reason;
    }
}
