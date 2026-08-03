-- V2__create_identity_and_session_tables.sql
-- 用户、角色与 Session 持久化表结构。
-- 本阶段只创建五张表及必要约束/索引，不创建后续阶段表。

-- ============================================================
-- roles
-- ============================================================
CREATE TABLE roles (
    role_name   VARCHAR(128) NOT NULL,
    description TEXT,
    PRIMARY KEY (role_name)
);

-- ============================================================
-- role_permissions
-- ============================================================
CREATE TABLE role_permissions (
    role_name       VARCHAR(128) NOT NULL,
    permission_code VARCHAR(256) NOT NULL,
    PRIMARY KEY (role_name, permission_code),
    CONSTRAINT fk_rp_role FOREIGN KEY (role_name)
        REFERENCES roles (role_name) ON DELETE CASCADE
);

-- ============================================================
-- users
-- ============================================================
CREATE TABLE users (
    user_id         VARCHAR(64)  NOT NULL,
    username        VARCHAR(128) NOT NULL,
    credential_hash VARCHAR(128) NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (user_id),
    CONSTRAINT uk_users_username UNIQUE (username)
);

-- ============================================================
-- user_roles
-- ============================================================
CREATE TABLE user_roles (
    user_id   VARCHAR(64)  NOT NULL,
    role_name VARCHAR(128) NOT NULL,
    PRIMARY KEY (user_id, role_name),
    CONSTRAINT fk_ur_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_ur_role FOREIGN KEY (role_name)
        REFERENCES roles (role_name) ON DELETE CASCADE
);

-- 支持按 role_name 查找受影响用户的索引
CREATE INDEX idx_user_roles_role_name ON user_roles (role_name);

-- ============================================================
-- user_sessions
-- ============================================================
CREATE TABLE user_sessions (
    session_id  VARCHAR(128) NOT NULL,
    user_id     VARCHAR(64)  NOT NULL,
    username    VARCHAR(128) NOT NULL,
    roles       TEXT[]       NOT NULL,
    permissions TEXT[]       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    expires_at  TIMESTAMPTZ,
    PRIMARY KEY (session_id)
);

CREATE INDEX idx_sessions_user_id ON user_sessions (user_id);
CREATE INDEX idx_sessions_expires_at ON user_sessions (expires_at)
    WHERE expires_at IS NOT NULL;
