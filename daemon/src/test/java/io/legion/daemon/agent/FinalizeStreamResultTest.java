package io.legion.daemon.agent;

import io.legion.contracts.agent.BackendFailureReason;
import io.legion.contracts.agent.Outcome;
import io.legion.contracts.agent.TokenUsage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 终态判定契约（参照仓库 stream_json_result.go finalizeStreamResult）：
 * fail-closed——进程干净退出不构成成功证据，成功必须显式 result 事件；
 * 失败时 output 一律为空，部分 transcript 不许冒充最终答案。
 */
class FinalizeStreamResultTest {

    private final Map<String, TokenUsage> usage = Map.of("m", new TokenUsage(1, 2, 0, 0, 3));

    @Test
    void successUsesResultText() {
        Outcome o = FinalizeStreamResult.finalize(FinalizeStreamResult.input()
                .sawResult(true).resultText("final answer").usage(usage).sessionId("s1"));
        assertTrue(o instanceof Outcome.Success);
        assertEquals("final answer", ((Outcome.Success) o).output());
        assertEquals(usage, ((Outcome.Success) o).usage());
    }

    @Test
    void successWithoutResultTextFallsBackToLastAssistantText() {
        Outcome o = FinalizeStreamResult.finalize(FinalizeStreamResult.input()
                .sawResult(true).resultText("").lastAssistantText("last turn"));
        assertEquals("last turn", ((Outcome.Success) o).output());
    }

    @Test
    void explicitResultWithNoTextAnywhereIsSuccessWithEmptyOutput() {
        // 空输出是平台信号"本轮无最终文本"，各投递面自行决策；这里返回散文
        // 会污染所有投递面（GH #6462 的英文哨兵到达 Lark 线程）
        Outcome o = FinalizeStreamResult.finalize(FinalizeStreamResult.input()
                .sawResult(true).resultText("").lastAssistantText(""));
        assertTrue(o instanceof Outcome.Success);
        assertEquals("", ((Outcome.Success) o).output());
    }

    @Test
    void noResultEventFailsClosedEvenWithAssistantText() {
        Outcome o = FinalizeStreamResult.finalize(FinalizeStreamResult.input()
                .sawResult(false).lastAssistantText("partial transcript"));
        assertEquals(BackendFailureReason.NO_RESULT, ((Outcome.Failure) o).reason());
        assertTrue(((Outcome.Failure) o).message().contains("without"));
    }

    @Test
    void terminalReasonWinsOverIsError() {
        Outcome o = FinalizeStreamResult.finalize(FinalizeStreamResult.input()
                .sawResult(true).resultIsError(true).resultText("context full")
                .contextExhausted(true));
        assertEquals(BackendFailureReason.CONTEXT_EXHAUSTED, ((Outcome.Failure) o).reason());
    }

    @Test
    void isErrorResultFailsWithResultTextAsMessage() {
        Outcome o = FinalizeStreamResult.finalize(FinalizeStreamResult.input()
                .sawResult(true).resultIsError(true).resultText("provider 500"));
        assertEquals(BackendFailureReason.ERROR_RESULT, ((Outcome.Failure) o).reason());
        assertEquals("provider 500", ((Outcome.Failure) o).message());
    }

    @Test
    void isErrorWithoutDetailsStillHasMessage() {
        Outcome o = FinalizeStreamResult.finalize(FinalizeStreamResult.input()
                .sawResult(true).resultIsError(true).resultText(""));
        assertEquals("claude returned an error result without details",
                ((Outcome.Failure) o).message());
    }

    @Test
    void timeoutBeatsCleanExit() {
        Outcome o = FinalizeStreamResult.finalize(FinalizeStreamResult.input()
                .sawResult(true).resultText("late answer")
                .timeout(Duration.ofMinutes(30)));
        assertEquals(BackendFailureReason.TIMEOUT, ((Outcome.Failure) o).reason());
    }

    @Test
    void abnormalExitWithoutResultFails() {
        Outcome o = FinalizeStreamResult.finalize(FinalizeStreamResult.input().exitCode(3));
        assertEquals(BackendFailureReason.ABNORMAL_EXIT, ((Outcome.Failure) o).reason());
    }

    @Test
    void lineTooLongScanErrorFails() {
        Outcome o = FinalizeStreamResult.finalize(FinalizeStreamResult.input()
                .scanError(new StreamScanner.LineTooLongException(33 * 1024 * 1024)));
        assertEquals(BackendFailureReason.LINE_TOO_LONG, ((Outcome.Failure) o).reason());
    }

    @Test
    void inputWriteErrorBeforeAnySessionFails() {
        Outcome o = FinalizeStreamResult.finalize(FinalizeStreamResult.input()
                .writeError(new RuntimeException("broken pipe")).sessionId(""));
        assertEquals(BackendFailureReason.INPUT_WRITE_FAILED, ((Outcome.Failure) o).reason());
    }

    @Test
    void asyncLaunchGuardFailsEvenWithCleanResult() {
        // 异步任务禁令优先于一切成功形态
        Outcome o = FinalizeStreamResult.finalize(FinalizeStreamResult.input()
                .sawResult(true).resultText("spawned it in background")
                .asyncLaunched(true));
        assertEquals(BackendFailureReason.ASYNC_TASK_LAUNCHED, ((Outcome.Failure) o).reason());
    }

    @Test
    void failureOutcomeKeepsUsageAndSessionId() {
        // usage 先于任何 early-return 上报——失败终态也必须原样携带
        Outcome o = FinalizeStreamResult.finalize(FinalizeStreamResult.input()
                .sawResult(true).resultIsError(true).usage(usage).sessionId("s9"));
        Outcome.Failure f = (Outcome.Failure) o;
        assertEquals(usage, f.usage());
        assertEquals("s9", f.sessionId());
    }
}
