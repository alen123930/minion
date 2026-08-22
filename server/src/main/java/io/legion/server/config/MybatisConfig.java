package io.legion.server.config;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.apache.ibatis.type.OffsetDateTimeTypeHandler;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis 基线配置（设计 §4.8）：mapUnderscoreToCamelCase + 统一 TypeHandler
 * 集中注册，禁止各 mapper 各写各的。UUID 用本项目自带的 UuidTypeHandler
 * （MyBatis 3.5.19 无内置 UUID handler，缺它 #{uuidParam} 绑定即抛错）。
 * JSONB↔JsonNode TypeHandler 等第一个 JSONB 消费方（M1 入队 context）再进这里，
 * M0 无 JSONB 读写。
 */
@Configuration
public class MybatisConfig {

    @Bean
    ConfigurationCustomizer legionMybatisCustomizer() {
        return configuration -> {
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
            configuration.getTypeHandlerRegistry().register(OffsetDateTime.class, OffsetDateTimeTypeHandler.class);
        };
    }
}