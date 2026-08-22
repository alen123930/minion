package io.multica.daemon;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 基线测试：无 web 容器的上下文可启动（仅验证脚手架接线，无业务逻辑）。 */
@SpringBootTest
class DaemonApplicationTests {

    @Test
    void contextLoads() {
    }
}
