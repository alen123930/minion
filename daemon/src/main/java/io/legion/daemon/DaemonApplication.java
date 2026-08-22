package io.legion.daemon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** legion-daemon 入口；对 server 的调用从 M0 起即走 localhost HTTP（协议即契约，AGENTS.md）。 */
@SpringBootApplication
public class DaemonApplication {

    public static void main(String[] args) {
        SpringApplication.run(DaemonApplication.class, args);
    }
}
