package io.legion.contracts.agent;

/**
 * backend 失败的稳定归因枚举（AGENTS.md：reason code 在阻塞分支一次决定、
 * 原样携带，禁止从错误字符串解析）。code() 即 daemon→server failure_class 线值。
 */
public enum BackendFailureReason {

    /** 配置的可执行文件在 PATH 上不存在——启动前置检查失败。 */
    EXECUTABLE_NOT_FOUND("executable_not_found"),

    /** 子进程 spawn 失败（IO 错误、权限等）。 */
    LAUNCH_FAILED("launch_failed"),

    /** prompt 写入 stdin 失败（管道破裂等）。 */
    INPUT_WRITE_FAILED("input_write_failed"),

    /** stdout 读失败（非行长上限的 IO 错误）。 */
    STREAM_READ_FAILED("stream_read_failed"),

    /** 单行超过 32MiB 上限（GH #4520 治理故事，见 StreamScanner）。 */
    LINE_TOO_LONG("line_too_long"),

    /** 子进程非零退出且无结构化 result——失败 fail-closed。 */
    ABNORMAL_EXIT("abnormal_exit"),

    /** 流在 result 事件到达前结束：只有显式 result 才算成功。 */
    NO_RESULT("no_result"),

    /** result 事件携带 is_error=true。 */
    ERROR_RESULT("error_result"),

    /** terminal_reason=prompt_too_long：上下文窗口耗尽且压缩无法恢复（GH #6402）。
     *  排在 is_error 之前判定——该形态下 is_error 与 terminal_reason 同时到达，
     *  只有后者不依赖 CLI 措辞地指认了故障。 */
    CONTEXT_EXHAUSTED("context_exhausted"),

    /** 异步任务禁令：事件流里观察到后台任务启动，直接判失败（生命周期逃逸）。 */
    ASYNC_TASK_LAUNCHED("async_task_launched"),

    /** 三独立生命期 watchdog（total/inactivity/firstOutput）触发。 */
    TIMEOUT("timeout"),

    /** 外部取消（M1 取消监视器接入）。 */
    CANCELLED("cancelled");

    private final String code;

    BackendFailureReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
