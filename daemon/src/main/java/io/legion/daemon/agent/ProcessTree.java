package io.legion.daemon.agent;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 进程树终止协议（设计 §5.5；参照仓库 proc_other.go/proc_windows.go + GH #5918）。
 *
 * <p>核心教训照搬：{@code waitFor()} 返回只代表 leader 死了。一个不持 stdout、
 * 无视 SIGTERM 的子进程会让 leader 先退、判定误通过，恰恰放过孤儿——它在任务
 * 取消后继续烧模型预算，还把队列槽占死。所以"整组是否退出"的判定必须扫描
 * 整棵树（leader + 全部后代），不是 leader。
 *
 * <p>平台对应关系（Windows 侧为 Job Object 等价语义）：
 * <ul>
 *   <li>Unix：Setpgid 进程组 ↔ {@code descendants()}；SIGTERM→SIGKILL 升级 =
 *       {@code destroy()}（SIGTERM）→ 宽限 → {@code destroyForcibly()}（SIGKILL）。</li>
 *   <li>Windows：参照实现用 Job Object + KILL_ON_JOB_CLOSE（成员资格只能在进程
 *       存在前申请，故 CREATE_SUSPENDED 起停）。JDK 无 Job Object API，等价语义
 *       用 {@code taskkill /PID x /T /F}：同样以"整棵树"为终止单位、同样原子、
 *       同样覆盖 wrapper（cmd.exe shim）之下的真实 CLI 进程。Windows 无
 *       SIGTERM/SIGKILL 之分，参照实现的 TerminateJobObject 本身就是硬终止，
 *       故此处不做宽限升级，一次到位；宽限参数仅 Unix 路径消费。
 *       descendants() 在 Windows 有覆盖不全的场景（设计 §5.5），taskkill 优先。</li>
 * </ul>
 */
final class ProcessTree {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("windows");

    private ProcessTree() {
    }

    /** 终止整棵树；宽限内未退出则强杀（Unix）。幂等：已退出直接返回。 */
    static void destroyTree(Process p, Duration grace) {
        if (!p.isAlive()) {
            return;
        }
        if (WINDOWS) {
            destroyTreeWindows(p);
            return;
        }
        // Unix：先 SIGTERM 全部后代再 leader，宽限内整组未退则整组 SIGKILL
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        if (!awaitTreeExit(p, grace)) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    private static void destroyTreeWindows(Process p) {
        ProcessHandle leader = p.toHandle();
        try {
            Process killer = new ProcessBuilder(
                    "taskkill", "/PID", String.valueOf(leader.pid()), "/T", "/F")
                    .start();
            killer.getInputStream().readAllBytes();
            killer.waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            // taskkill 不可用时退回 JDK 句柄枚举（覆盖面 weaker，聊胜于无）
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    /** 整棵树（leader + 所有后代）是否全部退出。 */
    static boolean treeGone(Process p) {
        if (p.isAlive()) {
            return false;
        }
        return p.descendants().noneMatch(ProcessHandle::isAlive);
    }

    /** 轮询等待整树退出；判定单位是树不是 leader（GH #5918 的教训本体）。 */
    static boolean awaitTreeExit(Process p, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!treeGone(p)) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** 测试辅助：等 leader 退出并返回退出码（负值 = 被强杀/未知）。 */
    static int awaitExitCode(Process p, Duration timeout) throws InterruptedException {
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            destroyTree(p, Duration.ofSeconds(1));
            p.waitFor();
        }
        return p.isAlive() ? -1 : exitCode(p);
    }

    static int exitCode(Process p) {
        try {
            return p.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    static List<ProcessHandle> livingDescendants(Process p) {
        return p.descendants().filter(ProcessHandle::isAlive).toList();
    }
}
