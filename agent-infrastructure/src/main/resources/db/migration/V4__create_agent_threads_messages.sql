-- V4__create_agent_threads_messages.sql
-- 用户可见的会话索引和聊天消息持久化。

-- ============================================================
-- agent_threads
-- ============================================================
CREATE TABLE agent_threads (
    thread_id       VARCHAR(64)   NOT NULL,
    user_id         VARCHAR(64)   NOT NULL,
    title           VARCHAR(256)  NOT NULL DEFAULT '',
    pinned          BOOLEAN       NOT NULL DEFAULT FALSE,
    archived        BOOLEAN       NOT NULL DEFAULT FALSE,
    agent_name      VARCHAR(128),
    next_sequence   BIGINT        NOT NULL DEFAULT 2,
    created_at      TIMESTAMPTZ   NOT NULL,
    last_message_at TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (thread_id)
);

-- 同一 thread_id 只能属于一个 user（防止归属歧义）
CREATE UNIQUE INDEX ukt_thread_user ON agent_threads (thread_id, user_id);

-- 用户会话列表：pinned 优先、最近消息优先，排除已归档
CREATE INDEX idx_threads_user_list ON agent_threads (user_id, pinned DESC, last_message_at DESC, thread_id)
    WHERE NOT archived;

-- ============================================================
-- agent_messages
-- ============================================================
CREATE TABLE agent_messages (
    message_id         VARCHAR(64)   NOT NULL,
    thread_id          VARCHAR(64)   NOT NULL,
    sequence_no        BIGINT        NOT NULL,
    role               VARCHAR(16)   NOT NULL,
    content            TEXT          NOT NULL,
    deduplication_key  VARCHAR(256),
    created_at         TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (message_id),
    CONSTRAINT ukm_thread_sequence UNIQUE (thread_id, sequence_no)
);

-- 去重键唯一约束：同一 thread 内去重键不可重复
CREATE UNIQUE INDEX ukm_dedup_key ON agent_messages (thread_id, deduplication_key)
    WHERE deduplication_key IS NOT NULL;

-- 消息按 sequence 分页查询
CREATE INDEX idx_messages_thread_seq ON agent_messages (thread_id, sequence_no);

-- 角色校验
ALTER TABLE agent_messages ADD CONSTRAINT chk_am_role
    CHECK (role IN ('USER', 'ASSISTANT'));

-- content 非空
ALTER TABLE agent_messages ADD CONSTRAINT chk_am_content_nonempty
    CHECK (length(trim(content)) > 0);
