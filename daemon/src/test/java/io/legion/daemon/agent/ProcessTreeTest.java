package io.legion.daemon.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProcessTree 终止协议的平台行为前提钉住：
 * taskkill 对不存在 PID 必须返回非零——destroyTreeWindows 的"非零即走
 * JDK 句柄兜底"分支依赖这个前提（评审 minor：退出码不检查时 taskkill
 * 失败会被静默当成杀树成功，随后 waitFor 无限阻塞）。
 */
class ProcessTreeTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void taskkillOnMissingPidReturnsNonZero() throws Exception {
        Integer exit = ProcessTree.killViaTaskkill(999999999L);
        assertTrue(exit != null && exit != 0,
                "taskkill on a missing pid must report failure, got " + exit);
    }

    @Test
    void awaitExitCodeReturnsPromptlyForExitedProcess() throws Exception {
        Process quick = new ProcessBuilder(
                System.getProperty("os.name", "").toLowerCase().contains("windows")
                        ? "cmd.exe" : "sh")
                .command(System.getProperty("os.name", "").toLowerCase().contains("windows")
                        ? new String[]{"cmd.exe", "/c", "exit /b 0"}
                        : new String[]{"sh", "-c", "exit 0"})
                .start();
        assertTrue(ProcessTree.awaitExitCode(quick, Duration.ofSeconds(5)) == 0);
    }
}
