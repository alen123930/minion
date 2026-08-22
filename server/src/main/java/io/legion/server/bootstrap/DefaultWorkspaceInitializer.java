package io.legion.server.bootstrap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.legion.server.WorkspaceDefaults;

/**
 * M0 单 workspace 硬编码：启动时幂等种子默认 workspace（设计 §4.2）。
 * 不用 @ConditionalOnBean(DataSource.class) 守卫——它在组件扫描期求值，
 * 早于 DataSource 自动配置的 bean 定义注册，必然失配；改在运行时经
 * ObjectProvider 探测，无数据源上下文（基线 contextLoads 排除 DataSource
 * 自动配置）则跳过。
 */
@Component
public class DefaultWorkspaceInitializer implements ApplicationRunner {

    private final ObjectProvider<JdbcTemplate> jdbcProvider;

    public DefaultWorkspaceInitializer(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            return;
        }
        jdbc.update(
                "INSERT INTO workspace (id, name, slug) VALUES (?, 'legion', 'legion') ON CONFLICT (id) DO NOTHING",
                WorkspaceDefaults.DEFAULT_WORKSPACE_ID);
    }
}