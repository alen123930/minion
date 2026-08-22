package io.legion.daemon.client;

/** daemon↔server 传输层失败（网络、非 2xx、响应不可解析）。unchecked：循环侧决定重试策略。 */
public class DaemonClientException extends RuntimeException {

    public DaemonClientException(String message) {
        super(message);
    }

    public DaemonClientException(String message, Throwable cause) {
        super(message, cause);
    }
}