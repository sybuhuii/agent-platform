package com.ksyun.agent.infrastructure.config;

import com.ksyun.agent.core.sanitizer.SensitiveValueSanitizer;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.core.store.ToolAuditStore;
import com.ksyun.agent.infrastructure.checkpoint.CheckpointPayloadCodec;
import com.ksyun.agent.infrastructure.store.PostgresCheckpointStore;
import com.ksyun.agent.infrastructure.store.PostgresToolAuditStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * PostgreSQL Checkpoint 和工具审计自动配置。
 * <p>
 * 仅在 {@code agent.persistence.backend=postgresql} 时激活。
 * 装配顺序早于 {@link AgentFrameworkAutoConfiguration}。
 * <p>
 * 装配：
 * - CheckpointPayloadCodec（JSON 序列化/反序列化）
 * - PostgresCheckpointStore（替代 InMemoryCheckpointStore）
 * - PostgresToolAuditStore（替代 InMemoryToolAuditStore）
 */
@AutoConfiguration(after = PostgreSQLAutoConfiguration.class, before = AgentFrameworkAutoConfiguration.class)
@ConditionalOnProperty(name = "agent.persistence.backend", havingValue = "postgresql")
public class PostgreSQLCheckpointAuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CheckpointPayloadCodec.class)
    public CheckpointPayloadCodec checkpointPayloadCodec(
            ObjectMapper objectMapper,
            SensitiveValueSanitizer sanitizer) {
        return new CheckpointPayloadCodec(objectMapper, sanitizer);
    }

    @Bean
    @ConditionalOnMissingBean(CheckpointStore.class)
    public CheckpointStore checkpointStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            CheckpointPayloadCodec codec) {
        return new PostgresCheckpointStore(jdbcTemplate, transactionManager, codec);
    }

    @Bean
    @ConditionalOnMissingBean(ToolAuditStore.class)
    public ToolAuditStore toolAuditStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        return new PostgresToolAuditStore(jdbcTemplate, transactionManager, objectMapper);
    }
}
