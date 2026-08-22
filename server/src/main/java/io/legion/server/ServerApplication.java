package io.legion.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** legion-server 入口；M0 阶段 daemon 以库形式内嵌于本进程（docs/design/02-模块划分.md）。 */
@SpringBootApplication
public class ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }
}
