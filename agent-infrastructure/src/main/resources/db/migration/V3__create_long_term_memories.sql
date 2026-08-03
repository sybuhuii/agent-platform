-- V3__create_long_term_memories.sql
-- 长期记忆持久化表结构。

CREATE TABLE long_term_memories (
    memory_id    VARCHAR(64)   NOT NULL,
    user_id      VARCHAR(64)   NOT NULL,
    namespace    VARCHAR(64)   NOT NULL,
    memory_key   VARCHAR(128)  NOT NULL,
    memory_value TEXT          NOT NULL,
    category     VARCHAR(16)   NOT NULL,
    metadata     JSONB         NOT NULL DEFAULT '{}',
    version      BIGINT        NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL,

    PRIMARY KEY (memory_id),

    -- 业务唯一键：同一用户、同一命名空间、同一 key 只有一条记忆
    CONSTRAINT uk_ltm_user_ns_key UNIQUE (user_id, namespace, memory_key),

    -- category 只允许当前 MemoryCategory 枚举值
    CONSTRAINT chk_ltm_category CHECK (category IN ('PROFILE', 'PREFERENCE', 'FACT', 'RULE')),

    -- version 非负
    CONSTRAINT chk_ltm_version CHECK (version >= 0),

    -- metadata 必须是 JSON 对象
    CONSTRAINT chk_ltm_metadata_object CHECK (jsonb_typeof(metadata) = 'object'),

    -- 时间约束：updated_at 不能早于 created_at
    CONSTRAINT chk_ltm_time_order CHECK (updated_at >= created_at)
);

-- 唯一索引已满足 user_id + namespace + memory_key 的查询和排序需求，
-- 不再创建完全重复的索引。
