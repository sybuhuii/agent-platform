package com.ksyun.agent.infrastructure.config;

import com.ksyun.agent.core.store.RoleStore;
import com.ksyun.agent.core.store.SessionStore;
import com.ksyun.agent.core.store.UserStore;
import com.ksyun.agent.infrastructure.store.PostgresRoleStore;
import com.ksyun.agent.infrastructure.store.PostgresSessionStore;
import com.ksyun.agent.infrastructure.store.PostgresUserStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * PostgreSQL 身份 Store 自动配置。
 * <p>
 * 仅在 {@code agent.persistence.backend=postgresql} 时激活。
 * auth.enabled=false 时 {@link SecurityConfiguration} 不激活，
 * 不会产生内存 Store Bean 冲突，因此不需要额外检查 auth.enabled。
 * 依赖第一阶段 {@link PostgreSQLAutoConfiguration} 提供的 JdbcTemplate 和事务 Bean。
 * <p>
 * 装配顺序早于 {@link SecurityConfiguration}，确保 PostgreSQL 模式下
 * 三个 Store 接口优先使用 PostgreSQL 实现，而非内存后备。
 * 每个 Store Bean 使用 {@code @ConditionalOnMissingBean}，支持调用方替换。
 * <p>
 * PostgreSQL 模式数据库不可用时启动失败，不回退到内存 Store。
 */
@AutoConfiguration(after = PostgreSQLAutoConfiguration.class, before = SecurityConfiguration.class)
@ConditionalOnProperty(name = "agent.persistence.backend", havingValue = "postgresql")
@ConditionalOnExpression("${agent.auth.enabled:true}")
public class PostgreSQLIdentityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public UserStore userStore(JdbcTemplate jdbcTemplate,
                               PlatformTransactionManager transactionManager) {
        return new PostgresUserStore(jdbcTemplate, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public RoleStore roleStore(JdbcTemplate jdbcTemplate,
                               PlatformTransactionManager transactionManager) {
        return new PostgresRoleStore(jdbcTemplate, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionStore sessionStore(JdbcTemplate jdbcTemplate) {
        return new PostgresSessionStore(jdbcTemplate);
    }
}
