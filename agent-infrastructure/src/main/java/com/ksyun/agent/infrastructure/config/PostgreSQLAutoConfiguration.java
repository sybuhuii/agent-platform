package com.ksyun.agent.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * PostgreSQL 持久化自动配置。
 * <p>
 * 仅在 {@code agent.persistence.backend=postgresql} 时激活：
 * - 创建受 Spring 管理的 HikariCP 连接池 DataSource。
 * - 创建后续 Store 可复用的 JdbcTemplate。
 * - 提供 DataSourceTransactionManager 事务管理能力。
 * - 装配 Flyway 并在启动时执行 migrate，迁移失败则终止启动。
 * <p>
 * 默认 {@code in-memory} 模式下本配置不激活，不创建任何数据库 Bean，
 * 不触发数据库连接，也不执行 Flyway。
 * <p>
 * 所有 Bean 均使用 {@code @ConditionalOnMissingBean}，允许外部替换默认实现。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "agent.persistence.backend", havingValue = "postgresql")
@EnableConfigurationProperties(PersistenceProperties.class)
public class PostgreSQLAutoConfiguration {

    /**
     * 创建 HikariCP 连接池 DataSource。
     * <p>
     * PostgreSQL 必要配置缺失时快速失败，错误信息不包含密码。
     * 数据库连接失败时由 HikariCP 抛出明确异常，终止启动，不静默回退内存模式。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(DataSource.class)
    public HikariDataSource dataSource(PersistenceProperties properties) {
        PersistenceProperties.PostgreSQL pg = properties.getPostgresql();
        validatePostgreSqlConfig(pg);

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(pg.getUrl());
        dataSource.setUsername(pg.getUsername());
        dataSource.setPassword(pg.getPassword());
        dataSource.setPoolName("agent-platform-postgresql");

        return dataSource;
    }

    /**
     * Spring JDBC 操作组件，供后续 Store 实现复用。
     */
    @Bean
    @ConditionalOnMissingBean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * 事务管理能力，供后续需要事务的 Store 使用。
     */
    @Bean
    @ConditionalOnMissingBean(PlatformTransactionManager.class)
    public DataSourceTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * 装配 Flyway，使用 classpath:db/migration 默认迁移目录。
     * <p>
     * 通过 initMethod=migrate 在 Bean 初始化时执行迁移；
     * 迁移失败抛出异常，终止应用启动。
     */
    @Bean(initMethod = "migrate")
    @ConditionalOnMissingBean
    public Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
    }

    /**
     * 校验 PostgreSQL 连接配置完整性。
     * 仅校验非空，不记录或拼接密码值。
     */
    private void validatePostgreSqlConfig(PersistenceProperties.PostgreSQL pg) {
        if (pg.getUrl() == null || pg.getUrl().isBlank()) {
            throw new IllegalStateException(
                    "agent.persistence.postgresql.url is required "
                            + "when agent.persistence.backend=postgresql");
        }
        if (pg.getUsername() == null || pg.getUsername().isBlank()) {
            throw new IllegalStateException(
                    "agent.persistence.postgresql.username is required "
                            + "when agent.persistence.backend=postgresql");
        }
        if (pg.getPassword() == null || pg.getPassword().isBlank()) {
            throw new IllegalStateException(
                    "agent.persistence.postgresql.password is required "
                            + "when agent.persistence.backend=postgresql");
        }
    }
}
