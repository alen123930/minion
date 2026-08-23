package io.legion.daemon.agent;

import io.legion.contracts.agent.AgentStreamEvent;
import io.legion.contracts.agent.BackendException;
import io.legion.contracts.agent.BackendFailureReason;
import io.legion.contracts.agent.ExecOptions;
import io.legion.contracts.agent.ExecRequest;
import io.legion.contracts.agent.Outcome;
import io.legion.contracts.agent.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真子进程集成：fixture CLI 脚本回放 stream-json 帧（参照仓库 claude_test.go 的
 * 可执行 fixture 纪律——不依赖真 claude CLI，所有默认测试禁止执行真实 agent）。
 * 超时用例隐含验证进程树终止：stdout 不 EOF，outcome 若能完成说明整树被杀、泵被解锁。
 */
class ClaudeBackendTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("windows");

    @TempDir
    Path dir;

    /** 写一个回放 fixture：先吞 stdin 到 stdin.txt（验 prompt 落盘），再输出 stream 行。 */
    private Path fixture(String name, List<String> streamLines, String... extra) throws IOException {
        Path stream = dir.resolve("stream-" + name + ".txt");
        Files.writeString(stream, String.join("\n", streamLines) + "\n");
        Path stdin = dir.resolve("stdin-" + name + ".txt");
        String stdinFile = stdin.toString().replace("'", "'\"'\"'");
        String streamFile = stream.toString().replace("'", "'\"'\"'");
        List<String> script = new ArrayList<>();
        if (WINDOWS) {
            script.add("@echo off");
            script.add("@more > \"" + stdin + "\"");
            for (String line : extra) {
                script.add(line);
            }
            script.add("@type \"" + stream + "\"");
        } else {
            script.add("#!/bin/sh");
            script.add("cat > '" + stdinFile + "'");
            for (String line : extra) {
                script.add(line);
            }
            script.add("cat '" + streamFile + "'");
        }
        Path scriptFile = dir.resolve(WINDOWS ? name + ".cmd" : name + ".sh");
        Files.write(scriptFile, script);
        if (!WINDOWS) {
            scriptFile.toFile().setExecutable(true);
        }
        return scriptFile;
    }

    private Path sleepFixture(String name) throws IOException {
        List<String> script = new ArrayList<>();
        if (WINDOWS) {
            script.add("@ping -n 61 127.0.0.1 >nul");
        } else {
            script.add("#!/bin/sh");
            script.add("sleep 60");
        }
        Path scriptFile = dir.resolve(WINDOWS ? name + ".cmd" : name + ".sh");
        Files.write(scriptFile, script);
        if (!WINDOWS) {
            scriptFile.toFile().setExecutable(true);
        }
        return scriptFile;
    }

    private List<AgentStreamEvent> drain(Session session) throws InterruptedException {
        List<AgentStreamEvent> events = new ArrayList<>();
        while (true) {
            AgentStreamEvent e = session.events().poll(15, TimeUnit.SECONDS);
            if (e == null) {
                throw new AssertionError("timed out waiting for End sentinel");
            }
            if (e instanceof AgentStreamEvent.End) {
                return events;
            }
            events.add(e);
        }
    }

    private ExecRequest request(ExecOptions options) {
        return new ExecRequest("please do the thing", options);
    }

    // ------------------------------------------------------------------

    @Test
    void successFlowStreamsEventsAndOutcome() throws Exception {
        Path cli = fixture("ok", List.of(
                "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"sess-9\"}",
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"model\":\"claude-sonnet-4\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"working\"}]}}",
                "{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"sess-9\","
                        + "\"result\":\"all done\",\"is_error\":false,"
                        + "\"model\":\"claude-sonnet-4\","
                        + "\"usage\":{\"input_tokens\":11,\"output_tokens\":7}}"));
        ClaudeBackend backend = new ClaudeBackend(
                new ClaudeBackend.Config(cli.toString(), Duration.ofSeconds(5),
                        StreamScanner.DEFAULT_MAX_LINE_BYTES));

        Session session = backend.execute(request(ExecOptions.defaults()));
        List<AgentStreamEvent> events = drain(session);

        assertEquals("system", events.get(0).wireType());
        assertEquals("sess-9", ((AgentStreamEvent.SystemInfo) events.get(0)).sessionId());
        assertEquals("working",
                ((AgentStreamEvent.AssistantText) events.get(1)).text());
        assertEquals("usage", events.get(2).wireType());

        Outcome o = session.outcome().get(5, TimeUnit.SECONDS);
        Outcome.Success success = assertInstanceOf(Outcome.Success.class, o);
        assertEquals("all done", success.output());
        assertEquals("sess-9", success.sessionId());
        assertEquals(11, success.usage().get("claude-sonnet-4").inputTokens());

        // prompt 通过 stdin 传入（设计 §6.2：prompt 走 stdin）
        Path stdinFile = dir.resolve("stdin-ok.txt");
        assertTrue(Files.exists(stdinFile));
        assertTrue(Files.readString(stdinFile).contains("please do the thing"));
    }

    @Test
    void executableMissingFailsFastWithStableReason() {
        ClaudeBackend backend = new ClaudeBackend(
                new ClaudeBackend.Config(dir.resolve("no-such-cli").toString(),
                        Duration.ofSeconds(5), StreamScanner.DEFAULT_MAX_LINE_BYTES));
        BackendException e = assertThrows(BackendException.class,
                () -> backend.execute(request(ExecOptions.defaults())));
        assertEquals(BackendFailureReason.EXECUTABLE_NOT_FOUND, e.reason());
    }

    @Test
    void abnormalExitFailsWithStderrTail() throws Exception {
        Path cli = fixture("exit3", List.of(
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"model\":\"m\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"partial\"}]}}"),
                WINDOWS ? "@echo V8 fatal heap OOM 1>&2" : "echo 'V8 fatal heap OOM' >&2",
                WINDOWS ? "@exit /b 3" : "exit 3");
        ClaudeBackend backend = new ClaudeBackend(
                new ClaudeBackend.Config(cli.toString(), Duration.ofSeconds(5),
                        StreamScanner.DEFAULT_MAX_LINE_BYTES));

        Session session = backend.execute(request(ExecOptions.defaults()));
        Outcome o = session.outcome().get(15, TimeUnit.SECONDS);
        Outcome.Failure failure = assertInstanceOf(Outcome.Failure.class, o);
        // 异常退出 fail-closed，且无 result 事件——退出码分支先命中
        assertEquals(BackendFailureReason.ABNORMAL_EXIT, failure.reason());
        assertTrue(failure.message().contains("3"));
        // stderr 有界尾巴拼进失败消息——exit status 单独不可诊断（参照 stderr_tail.go）
        assertTrue(failure.message().contains("V8 fatal heap OOM"));
    }

    @Test
    void asyncLaunchBanFailsTheRun() throws Exception {
        Path cli = fixture("async", List.of(
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":["
                        + "{\"type\":\"tool_result\",\"tool_use_id\":\"t1\","
                        + "\"content\":{\"status\":\"async_launched\"}}]}}",
                "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"spawned\","
                        + "\"is_error\":false}"));
        ClaudeBackend backend = new ClaudeBackend(
                new ClaudeBackend.Config(cli.toString(), Duration.ofSeconds(5),
                        StreamScanner.DEFAULT_MAX_LINE_BYTES));

        Session session = backend.execute(request(ExecOptions.defaults()));
        Outcome o = session.outcome().get(15, TimeUnit.SECONDS);
        Outcome.Failure failure = assertInstanceOf(Outcome.Failure.class, o);
        assertEquals(BackendFailureReason.ASYNC_TASK_LAUNCHED, failure.reason());
    }

    @Test
    void oversizedLineFailsClosed() throws Exception {
        Path cli = fixture("bigline", List.of("{\"type\":\"system\",\"session_id\":\"s\"}"));
        // 注入小上限（生产恒为 32MiB，见 StreamScanner 唯一定义）
        ClaudeBackend backend = new ClaudeBackend(
                new ClaudeBackend.Config(cli.toString(), Duration.ofSeconds(5), 256));
        StringBuilder big = new StringBuilder("{\"type\":\"assistant\",\"message\":{");
        for (int i = 0; i < 64; i++) {
            big.append("\"k").append(i).append("\":\"")
                    .append("x".repeat(32)).append("\",");
        }
        big.append("\"z\":1}}");
        Files.writeString(dir.resolve("stream-bigline.txt"),
                "{\"type\":\"system\",\"session_id\":\"s\"}\n" + big + "\n");

        Session session = backend.execute(request(ExecOptions.defaults()));
        Outcome o = session.outcome().get(15, TimeUnit.SECONDS);
        Outcome.Failure failure = assertInstanceOf(Outcome.Failure.class, o);
        assertEquals(BackendFailureReason.LINE_TOO_LONG, failure.reason());
    }

    @Test
    void totalTimeoutKillsTreeAndCompletesOutcome() throws Exception {
        Path cli = sleepFixture("sleeper");
        ClaudeBackend backend = new ClaudeBackend(
                new ClaudeBackend.Config(cli.toString(), Duration.ofMillis(300),
                        StreamScanner.DEFAULT_MAX_LINE_BYTES));

        long start = System.nanoTime();
        Session session = backend.execute(request(new ExecOptions(null, null, null,
                Duration.ofMillis(300), null, null, null)));
        Outcome o = session.outcome().get(20, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        Outcome.Failure failure = assertInstanceOf(Outcome.Failure.class, o);
        assertEquals(BackendFailureReason.TIMEOUT, failure.reason());
        // 不杀整树 stdout 永不 EOF，outcome 永远完不成——快速完成即证明树终止生效
        assertTrue(elapsedMs < 10_000, "outcome should complete promptly, took " + elapsedMs + "ms");
    }

    @Test
    void inactivityTimeoutFiresWhenStreamGoesSilent() throws Exception {
        Path cli = fixture("then-sleep", List.of(
                "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s\"}"));
        // stream.txt 只有一行，type 完立刻进入长眠：单轮静默看门狗应触发
        Path script = dir.resolve(WINDOWS ? "then-sleep.cmd" : "then-sleep.sh");
        if (WINDOWS) {
            Files.write(script, Files.readAllLines(script).stream()
                    .map(l -> l.startsWith("@type") ? l + "\r\n@ping -n 61 127.0.0.1 >nul" : l)
                    .toList());
        } else {
            Files.write(script, Files.readAllLines(script).stream()
                    .map(l -> l.startsWith("cat '") ? l + "\nsleep 60" : l)
                    .toList());
        }
        ClaudeBackend backend = new ClaudeBackend(
                new ClaudeBackend.Config(cli.toString(), Duration.ofMillis(300),
                        StreamScanner.DEFAULT_MAX_LINE_BYTES));

        Session session = backend.execute(request(new ExecOptions(null, null, null,
                null, Duration.ofMillis(400), null, null)));
        Outcome o = session.outcome().get(20, TimeUnit.SECONDS);
        assertEquals(BackendFailureReason.TIMEOUT, ((Outcome.Failure) o).reason());
    }

    @Test
    void firstOutputTimeoutFiresWhenNothingArrives() throws Exception {
        Path cli = sleepFixture("silent-sleeper");
        ClaudeBackend backend = new ClaudeBackend(
                new ClaudeBackend.Config(cli.toString(), Duration.ofMillis(300),
                        StreamScanner.DEFAULT_MAX_LINE_BYTES));

        Session session = backend.execute(request(new ExecOptions(null, null, null,
                null, null, Duration.ofMillis(400), null)));
        Outcome o = session.outcome().get(20, TimeUnit.SECONDS);
        assertEquals(BackendFailureReason.TIMEOUT, ((Outcome.Failure) o).reason());
    }
}
