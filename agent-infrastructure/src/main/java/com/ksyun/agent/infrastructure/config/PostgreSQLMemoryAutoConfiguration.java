package com.ksyun.agent.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ksyun.agent.core.store.MemoryStore;
import com.ksyun.agent.infrastructure.store.PostgresMemoryStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL 长期记忆自动配置。
 * <p>
 * 仅在 {@code agent.memory.backend=postgresql} 时激活。
 * 同时要求 {@code agent.persistence.backend=postgresql}（基础设施总开关），
 * 配置冲突时启动快速失败，不回退内存模式。
 * <p>
 * 装配顺序早于 {@link AgentFrameworkAutoConfiguration}，
 * 确保 {@code @ConditionalOnMissingBean(MemoryStore.class)} 优先匹配 PostgreSQL 实现，
 * 而非后续 InMemory 后备 Bean。
 * <p>
 * 依赖第一阶段 {@link PostgreSQLAutoConfiguration} 提供的 JdbcTemplate。
 * 数据库不可用时启动失败，不静默兜底。
 */
@AutoConfiguration(after = PostgreSQLAutoConfiguration.class, before = AgentFrameworkAutoConfiguration.class)
@ConditionalOnProperty(name = "agent.memory.backend", havingValue = "postgresql")
@EnableConfigurationProperties({MemoryProperties.class, PersistenceProperties.class})
public class PostgreSQLMemoryAutoConfiguration {

    /**
     * 校验配置一致性：memory.backend=postgresql 时必须 persistence.backend=postgresql。
     * 快速失败，不回退内存 Store。
     */
    @Bean
    MemoryPostgresConfigValidator memoryPostgresConfigValidator(
            MemoryProperties memoryProperties, PersistenceProperties persistenceProperties) {
        if (!"postgresql".equals(persistenceProperties.getBackend())) {
            throw new IllegalStateException(
                    "agent.memory.backend=postgresql requires "
                            + "agent.persistence.backend=postgresql, "
                            + "but agent.persistence.backend=" + persistenceProperties.getBackend());
        }
        // 返回一个无害的 validator Bean，仅用于触发上述校验
        return new MemoryPostgresConfigValidator();
    }

    @Bean
    @ConditionalOnMissingBean(MemoryStore.class)
    @ConditionalOnProperty(
            name = "agent.memory.enabled", havingValue = "true", matchIfMissing = true)
    public MemoryStore memoryStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new PostgresMemoryStore(jdbcTemplate, objectMapper);
    }

    /**
     * 仅用于触发配置校验的无状态 Bean。
     */
    static class MemoryPostgresConfigValidator {
    }
}
