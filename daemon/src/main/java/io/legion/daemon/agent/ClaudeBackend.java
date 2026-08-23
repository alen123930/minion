package io.legion.daemon.agent;

import io.legion.contracts.agent.AgentBackend;
import io.legion.contracts.agent.AgentStreamEvent;
import io.legion.contracts.agent.BackendException;
import io.legion.contracts.agent.BackendFailureReason;
import io.legion.contracts.agent.ExecOptions;
import io.legion.contracts.agent.ExecRequest;
import io.legion.contracts.agent.Outcome;
import io.legion.contracts.agent.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * claude CLI 一次性模式 backend（设计 §6.2）：
 * {@code claude -p --output-format stream-json --verbose --permission-mode bypassPermissions}，
 * prompt 走 stdin，stdout 按行读、Jackson 逐行解析。
 *
 * <p>四条必抄决策的落点：
 * <ol>
 *   <li>异步任务禁令：{@link ClaudeStreamParser#sawAsyncLaunch()} → 终态失败
 *       （后台任务逃逸生命周期控制）。</li>
 *   <li>进程组终止：watchdog 触发 → EOF stdin → {@link ProcessTree#destroyTree}
 *       （"整组是否退出"而非"leader 是否退出"，GH #5918）。</li>
 *   <li>行长度上限 32MiB：唯一定义在 {@link StreamScanner}，本类不私设上限。</li>
 *   <li>成本请求级记录：usage 从 result 事件原样取（modelUsage 优先），
 *       仅有 provider 申报的 cost_usd_ticks，绝不 token×费率折算。</li>
 * </ol>
 *
 * <p>watchdog 三独立生命期（§6.2）：total / inactivity / firstOutput 三个
 * ScheduledFuture，独立配置独立触发，不许合并成一个"超时"（MUL-3064）。
 */
public final class ClaudeBackend implements AgentBackend {

    /** 启动配置；maxLineBytes 仅供测试注入，生产恒为 32MiB 唯一定义。 */
    public record Config(String executablePath, Duration terminateGrace, int maxLineBytes) {

        public static Config defaults() {
            return new Config("claude", Duration.ofSeconds(5),
                    StreamScanner.DEFAULT_MAX_LINE_BYTES);
        }
    }

    /**
     * 子进程 stdio 泵用固定平台线程池（AGENTS.md 并发硬规则）：
     * 泵内是 synchronized 保护的阻塞 IO，JDK 21 虚拟线程会 pin 住 carrier；
     * 平台线程没有这个问题。池大小按每执行 3 线程（stdin/stdout/stderr）×
     * M0 并发预留；M1 上量时随并发槽位调整。
     */
    private static final AtomicInteger PUMP_SEQ = new AtomicInteger();

    private static final ExecutorService PUMPS = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "legion-agent-io-" + PUMP_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private final Config cfg;
    private final ScheduledExecutorService watchdogs;

    public ClaudeBackend(Config cfg) {
        this.cfg = cfg;
        this.watchdogs = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "legion-agent-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public String provider() {
        return "claude";
    }

    @Override
    public Session execute(ExecRequest request) throws BackendException {
        ExecOptions opts = request.options() == null ? ExecOptions.defaults() : request.options();
        Path executable = resolveExecutable(cfg.executablePath());
        List<String> argv = buildCommand(executable, opts);

        ProcessBuilder pb = new ProcessBuilder(argv);
        if (opts.workDir() != null) {
            pb.directory(Path.of(opts.workDir()).toFile());
        }
        if (opts.extraEnv() != null && !opts.extraEnv().isEmpty()) {
            for (Map.Entry<String, String> e : opts.extraEnv().entrySet()) {
                pb.environment().put(e.getKey(), e.getValue());
            }
        }

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new BackendException(BackendFailureReason.LAUNCH_FAILED,
                    "start claude: " + e.getMessage(), e);
        }

        Run run = new Run(process, request.prompt(), opts);
        PUMPS.submit(run::pumpStderr);
        PUMPS.submit(run::pumpStdout);
        PUMPS.submit(run::writeStdin);
        run.armWatchdogs();
        return new Session(run.events, run.outcome);
    }

    // ------------------------------------------------------------------

    /**
     * LookPath 语义（参照仓库 claude.go 的 exec.LookPath 前置检查）：
     * 找不到可执行文件在 spawn 前失败，reason 稳定为 executable_not_found。
     */
    private Path resolveExecutable(String command) {
        boolean hasSeparator = command.indexOf('/') >= 0 || command.indexOf('\\') >= 0;
        if (hasSeparator) {
            Path p = Path.of(command);
            if (Files.isRegularFile(p)) {
                return p;
            }
            throw new BackendException(BackendFailureReason.EXECUTABLE_NOT_FOUND,
                    "claude executable not found at \"" + command + "\"");
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                for (String candidate : pathCandidates(command)) {
                    Path p = Path.of(dir, candidate);
                    if (Files.isRegularFile(p)) {
                        return p;
                    }
                }
            }
        }
        throw new BackendException(BackendFailureReason.EXECUTABLE_NOT_FOUND,
                "claude executable not found on PATH: \"" + command + "\"");
    }

    private List<String> pathCandidates(String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("windows")) {
            return List.of(command, command + ".exe", command + ".cmd", command + ".bat");
        }
        return List.of(command);
    }

    /** argv 组装（设计 §6.2 的固定四旗标 + 可选 model/resume）。 */
    private List<String> buildCommand(Path executable, ExecOptions opts) {
        List<String> argv = new ArrayList<>();
        String path = executable.toString();
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
        boolean shellScript = windows
                && (path.endsWith(".cmd") || path.endsWith(".bat"));
        if (shellScript) {
            // npm 装的 CLI 在 Windows 上是 .cmd shim，CreateProcess 不认，
            // 必须经 cmd.exe 代启（参照仓库 *_invocation_windows.go 一族的处理）
            argv.add("cmd.exe");
            argv.add("/c");
        }
        argv.add(path);
        argv.add("-p");
        argv.add("--output-format");
        argv.add("stream-json");
        argv.add("--verbose");
        argv.add("--permission-mode");
        argv.add("bypassPermissions");
        if (opts.model() != null && !opts.model().isBlank()) {
            argv.add("--model");
            argv.add(opts.model());
        }
        if (opts.resumeSessionId() != null && !opts.resumeSessionId().isBlank()) {
            argv.add("--resume");
            argv.add(opts.resumeSessionId());
        }
        return argv;
    }

    // ------------------------------------------------------------------

    /** 单次执行的全部可变状态；泵/watchdog 线程共享。 */
    private final class Run {

        private final Process process;
        private final String prompt;
        private final ExecOptions opts;
        private final LinkedBlockingQueue<AgentStreamEvent> events = new LinkedBlockingQueue<>();
        private final CompletableFuture<Outcome> outcome = new CompletableFuture<>();
        private final StderrTail stderrTail = new StderrTail();
        private final CountDownLatch stderrDone = new CountDownLatch(1);
        private final CompletableFuture<Exception> writeResult = new CompletableFuture<>();

        private final AtomicBoolean watchdogFired = new AtomicBoolean();
        private final AtomicReference<Duration> firedTimeout = new AtomicReference<>();
        private final Object rescheduleLock = new Object();
        private ScheduledFuture<?> inactivityFuture;
        private boolean firstOutputSeen;

        Run(Process process, String prompt, ExecOptions opts) {
            this.process = process;
            this.prompt = prompt == null ? "" : prompt;
            this.opts = opts;
        }

        void armWatchdogs() {
            // null = 不启用（MUL-3064：三个生命期各自独立，不许合并）
            if (opts.totalTimeout() != null) {
                watchdogs.schedule(() -> fireWatchdog(opts.totalTimeout()),
                        opts.totalTimeout().toMillis(), TimeUnit.MILLISECONDS);
            }
            if (opts.firstOutputTimeout() != null) {
                watchdogs.schedule(() -> fireWatchdog(opts.firstOutputTimeout()),
                        opts.firstOutputTimeout().toMillis(), TimeUnit.MILLISECONDS);
            }
            if (opts.inactivityTimeout() != null) {
                scheduleInactivity();
            }
        }

        private void scheduleInactivity() {
            inactivityFuture = watchdogs.schedule(
                    () -> fireWatchdog(opts.inactivityTimeout()),
                    opts.inactivityTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }

        /** 每收到一条事件：firstOutput 永久取消，inactivity 重排。 */
        void activity() {
            synchronized (rescheduleLock) {
                if (opts.firstOutputTimeout() != null && !firstOutputSeen) {
                    firstOutputSeen = true;
                }
                if (opts.inactivityTimeout() != null) {
                    if (inactivityFuture != null) {
                        inactivityFuture.cancel(false);
                    }
                    scheduleInactivity();
                }
            }
        }

        private void fireWatchdog(Duration timeout) {
            if (!watchdogFired.compareAndSet(false, true)) {
                return;
            }
            firedTimeout.set(timeout);
            synchronized (rescheduleLock) {
                if (inactivityFuture != null) {
                    inactivityFuture.cancel(false);
                }
            }
            // 顺序照抄参照实现：先 EOF stdin 促其干净退出，再整树终止，
            // 整树死透后 stdout 管道写端关闭，阻塞中的泵自然以 EOF 解锁
            closeQuietly(process.getOutputStream());
            ProcessTree.destroyTree(process, cfg.terminateGrace());
            closeQuietly(process.getInputStream());
        }

        void writeStdin() {
            try {
                OutputStream stdin = process.getOutputStream();
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
                // 一次性 -p 模式把 stdin 读到 EOF 才开始执行，写完必须关。
                // （stream-json 输入模式要留 stdin 应答 control_request，M1 再留）
                stdin.close();
                writeResult.complete(null);
            } catch (IOException e) {
                writeResult.complete(e);
                closeQuietly(process.getOutputStream());
            }
        }

        void pumpStderr() {
            try (InputStream err = process.getErrorStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = err.read(buf)) >= 0) {
                    if (n > 0) {
                        stderrTail.append(buf, n);
                    }
                }
            } catch (IOException ignored) {
                // 进程树被杀时管道复位属预期；已收字节足够诊断
            } finally {
                stderrDone.countDown();
            }
        }

        void pumpStdout() {
            Exception scanError = null;
            ClaudeStreamParser parser = new ClaudeStreamParser(opts.model());
            try (StreamScanner scanner =
                         new StreamScanner(process.getInputStream(), cfg.maxLineBytes())) {
                while (true) {
                    String line;
                    try {
                        line = scanner.readLine();
                    } catch (StreamScanner.LineTooLongException e) {
                        // 超长行：读端立即停转，先关管道再 Wait，防止子进程
                        // 写满 OS 管道缓冲后死锁（参照仓库 scanErr 分支）
                        scanError = e;
                        closeQuietly(process.getInputStream());
                        break;
                    }
                    if (line == null) {
                        break;
                    }
                    activity();
                    for (AgentStreamEvent event : parser.feed(line)) {
                        events.offer(event);
                    }
                }
            } catch (IOException e) {
                // watchdog 杀树后管道复位：非故障，终态由 watchdog 标志决定
                if (!watchdogFired.get() && scanError == null) {
                    scanError = e;
                }
            }

            int exitCode;
            try {
                exitCode = ProcessTree.awaitExitCode(process, cfg.terminateGrace());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exitCode = -1;
            }
            Exception writeError = awaitWriteResult();
            awaitStderrDrain();

            FinalizeStreamResult.Input input = FinalizeStreamResult.input()
                    .provider("claude")
                    .sawResult(parser.sawResult())
                    .resultText(parser.resultText())
                    .resultIsError(parser.resultIsError())
                    .contextExhausted(parser.contextExhausted())
                    .lastAssistantText(parser.lastAssistantText())
                    .asyncLaunched(parser.sawAsyncLaunch())
                    .exitCode(exitCode)
                    .scanError(scanError)
                    .writeError(writeError)
                    .sessionId(parser.sessionId())
                    .usage(parser.usage());
            Duration fired = firedTimeout.get();
            if (fired != null) {
                input.timeout(fired);
            }
            Outcome result = FinalizeStreamResult.finalize(input);
            if (result instanceof Outcome.Failure failure) {
                result = new Outcome.Failure(failure.reason(),
                        StderrTail.attach(failure.message(), "claude", stderrTail.tail()),
                        failure.usage(), failure.sessionId());
            }
            events.offer(new AgentStreamEvent.End());
            outcome.complete(result);
        }

        private Exception awaitWriteResult() {
            try {
                return writeResult.get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                return null;
            }
        }

        private void awaitStderrDrain() {
            try {
                stderrDone.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // 关闭是善意兜底（促退出/解锁读端），失败不改变终态
        }
    }
}
