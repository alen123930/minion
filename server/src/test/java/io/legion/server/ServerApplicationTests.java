package io.legion.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import io.legion.server.it.AbstractIntegrationTest;

/**
 * 基线测试：WebMvc 上下文可启动（M0-4 起上下文含 MyBatis mapper/service，
 * 需要真数据源，故与集成测试同走 Testcontainers `it` profile；M0-3 注释已预告
 * @ServiceConnection 留给本任务）。
 */
class ServerApplicationTests extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    @Test
    void contextLoads() {
    }
}