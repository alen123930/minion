package io.legion.server.bootstrap;

import io.legion.daemon.client.DaemonClient;
import io.legion.daemon.loop.TaskWorkerLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * M0 内嵌 worker（设计 §二：daemon 以库形式内嵌于 server，对 server 的调用走 localhost HTTP）。
 * 在 server 就绪后启动调度器，按 poll-interval 驱动 {@link TaskWorkerLoop#poll()}。
 * 测试一律关（测试 application.yml legacy.daemon.embedded.enabled=false），
 * 避免 worker 抢跑各集成测试自己种下的任务。
 *
 * <p>端口在 run() 时解析而非构造时：local.server.port 在 web server 启动后才写入 Environment，
 * 构造期读只会拿到默认值。daemon 拆独立进程后此类删除，TaskWorkerLoop 原样带走。
 */
@Component
@ConditionalOnProperty(name = "legion.daemon.embedded.enabled", havingValue = "true", matchIfMissing = false)
public class EmbeddedDaemonStarter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedDaemonStarter.class);

    private final Environment env;
    private final Duration pollInterval;
    private ScheduledExecutorService scheduler;

    public EmbeddedDaemonStarter(Environment env,
                                 @Value("${legion.daemon.embedded.poll-interval:3s}") Duration pollInterval) {
        this.env = env;
        this.pollInterval = pollInterval;
    }

    @Override
    public void run(ApplicationArguments args) {
        String port = env.getProperty("local.server.port",
                env.getProperty("server.port", "8080"));
        URI base = URI.create("http://127.0.0.1:" + port);
        TaskWorkerLoop loop = new TaskWorkerLoop(new DaemonClient(base), pollInterval);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "legion-daemon-embedded");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(loop::poll, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("内嵌 worker 已启动，每 {} 轮询 {}", pollInterval, base);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}