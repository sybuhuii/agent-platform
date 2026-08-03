package com.ksyun.agent.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * 持久化后端配置属性。
 * <p>
 * 约束：
 * - backend 默认 in-memory，不依赖任何数据库即可启动。
 * - backend 仅接受白名单值：in-memory、postgresql。
 * - 仅 backend=postgresql 时才要求 PostgreSQL 连接配置。
 * - PostgreSQL 模式缺少必要配置时启动快速失败，且错误信息不包含密码。
 * - 不得在日志、异常或最终报告中记录数据库密码。
 */
@ConfigurationProperties(prefix = "agent.persistence")
public class PersistenceProperties {

    /** 允许的后端白名单 */
    private static final Set<String> ALLOWED_BACKENDS = Set.of("in-memory", "postgresql");

    /** 持久化后端，默认内存模式 */
    private String backend = "in-memory";

    /** PostgreSQL 连接配置 */
    private PostgreSQL postgresql = new PostgreSQL();

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        if (backend == null || backend.isBlank()) {
            throw new IllegalArgumentException(
                    "agent.persistence.backend must not be blank");
        }
        String trimmed = backend.trim();
        if (!ALLOWED_BACKENDS.contains(trimmed)) {
            throw new IllegalArgumentException(
                    "agent.persistence.backend must be one of "
                            + ALLOWED_BACKENDS + ", got: " + trimmed);
        }
        this.backend = trimmed;
    }

    public PostgreSQL getPostgresql() {
        return postgresql;
    }

    public void setPostgresql(PostgreSQL postgresql) {
        this.postgresql = postgresql != null ? postgresql : new PostgreSQL();
    }

    /**
     * PostgreSQL 连接配置。
     * <p>
     * url/username/password 仅在 backend=postgresql 时使用；
     * 默认为空字符串，由 {@link PostgreSQLAutoConfiguration} 在启用时校验非空。
     * 密码仅在 DataSource 装配时使用，不得记录或拼接进异常信息。
     */
    public static class PostgreSQL {

        /** JDBC 连接串 */
        private String url = "";

        /** 数据库用户名 */
        private String username = "";

        /** 数据库密码 */
        private String password = "";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url != null ? url : "";
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username != null ? username : "";
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password != null ? password : "";
        }
    }
}
