package io.legion.daemon.agent;

import io.legion.contracts.agent.BackendFailureReason;
import io.legion.contracts.agent.Outcome;
import io.legion.contracts.agent.TokenUsage;

import java.time.Duration;
import java.util.Map;

/**
 * stream-json 家族共用的终态判定（参照仓库 stream_json_result.go
 * finalizeStreamResult，逐分支照抄）。fail-closed：进程干净退出不是成功证据，
 * 成功必须显式 result 事件；失败 output 一律为空，部分 transcript 不许冒充
 * 最终答案——那正是"工具前旁白"到达用户的路径（#6006）。
 *
 * <p>分支顺序即优先级：terminal_reason（结构性事实）先于 is_error（依赖 CLI
 * 措辞）；watchdog 先于退出码；异步任务禁令压过一切成功形态。
 */
final class FinalizeStreamResult {

    private FinalizeStreamResult() {
    }

    static Input input() {
        return new Input();
    }

    static final class Input {
        String provider = "claude";
        boolean sawResult;
        String resultText = "";
        boolean resultIsError;
        boolean contextExhausted;
        String lastAssistantText = "";
        boolean asyncLaunched;
        Duration timeout;
        boolean timeoutFired;
        boolean cancelled;
        int exitCode;
        boolean abnormalExit;
        Exception scanError;
        Exception writeError;
        String sessionId = "";
        Map<String, TokenUsage> usage = Map.of();

        Input provider(String v) {
            this.provider = v;
            return this;
        }

        Input sawResult(boolean v) {
            this.sawResult = v;
            return this;
        }

        Input resultText(String v) {
            this.resultText = v;
            return this;
        }

        Input resultIsError(boolean v) {
            this.resultIsError = v;
            return this;
        }

        Input contextExhausted(boolean v) {
            this.contextExhausted = v;
            return this;
        }

        Input lastAssistantText(String v) {
            this.lastAssistantText = v;
            return this;
        }

        Input asyncLaunched(boolean v) {
            this.asyncLaunched = v;
            return this;
        }

        Input timeout(Duration v) {
            this.timeout = v;
            this.timeoutFired = true;
            return this;
        }

        Input cancelled() {
            this.cancelled = true;
            return this;
        }

        /** 非零退出码；与 abnormalExit 二选一（0 = 正常退出）。 */
        Input exitCode(int v) {
            this.exitCode = v;
            this.abnormalExit = v != 0;
            return this;
        }

        Input scanError(Exception v) {
            this.scanError = v;
            return this;
        }

        Input writeError(Exception v) {
            this.writeError = v;
            return this;
        }

        Input sessionId(String v) {
            this.sessionId = v == null ? "" : v;
            return this;
        }

        Input usage(Map<String, TokenUsage> v) {
            this.usage = v == null ? Map.of() : v;
            return this;
        }

        Outcome build() {
            return FinalizeStreamResult.finalize(this);
        }
    }

    static Outcome finalize(Input in) {
        BackendFailureReason reason = null;
        String message = null;
        if (in.contextExhausted) {
            reason = BackendFailureReason.CONTEXT_EXHAUSTED;
            message = in.provider + " ended the turn with terminal_reason="
                    + ClaudeStreamParser.TERMINAL_REASON_PROMPT_TOO_LONG
                    + ": the session's context window is exhausted and compaction could not recover it"
                    + (in.resultText.isBlank() ? "" : " (" + in.resultText.trim() + ")");
        } else if (in.resultIsError) {
            reason = BackendFailureReason.ERROR_RESULT;
            message = in.resultText.isBlank()
                    ? in.provider + " returned an error result without details"
                    : in.resultText;
        }

        if (reason == null && in.timeoutFired) {
            reason = BackendFailureReason.TIMEOUT;
            message = in.provider + " timed out after " + in.timeout;
        }
        if (reason == null && in.cancelled) {
            reason = BackendFailureReason.CANCELLED;
            message = "execution cancelled";
        }
        if (reason == null && in.scanError != null) {
            reason = in.scanError instanceof StreamScanner.LineTooLongException
                    ? BackendFailureReason.LINE_TOO_LONG
                    : BackendFailureReason.STREAM_READ_FAILED;
            message = in.provider + " stdout read error: " + in.scanError.getMessage();
        }
        if (reason == null && in.writeError != null && in.sessionId.isEmpty()) {
            reason = BackendFailureReason.INPUT_WRITE_FAILED;
            message = "write " + in.provider + " input: " + in.writeError.getMessage();
        }
        if (reason == null && in.abnormalExit) {
            reason = BackendFailureReason.ABNORMAL_EXIT;
            message = in.provider + " exited with code " + in.exitCode;
        }
        if (reason == null && !in.sawResult) {
            reason = BackendFailureReason.NO_RESULT;
            message = in.provider + " stream ended without terminal result";
        }
        if (reason == null && in.asyncLaunched) {
            // 异步任务禁令：后台任务逃逸生命周期控制（设计 §6.2 决策 1）
            reason = BackendFailureReason.ASYNC_TASK_LAUNCHED;
            message = in.provider
                    + " launched an async background task; managed runs require foreground execution";
        }

        if (reason != null) {
            return new Outcome.Failure(reason, message, in.usage, in.sessionId);
        }
        String output = !in.resultText.isEmpty() ? in.resultText : in.lastAssistantText;
        return new Outcome.Success(output, in.usage, in.sessionId);
    }
}
