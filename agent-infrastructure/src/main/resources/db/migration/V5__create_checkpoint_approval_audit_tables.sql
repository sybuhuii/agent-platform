-- V5__create_checkpoint_approval_audit_tables.sql
-- Checkpoint 持久化、审批记录和工具调用审计表。

-- ============================================================
-- agent_checkpoints
-- ============================================================
CREATE TABLE agent_checkpoints (
    checkpoint_id      VARCHAR(64)   NOT NULL,
    run_id             VARCHAR(64)   NOT NULL,
    thread_id          VARCHAR(64)   NOT NULL,
    user_id            VARCHAR(64)   NOT NULL,
    execution_type     VARCHAR(16)   NOT NULL,
    purpose            VARCHAR(16)   NOT NULL,
    agent_name         VARCHAR(128)  NOT NULL,
    node_name          VARCHAR(128)  NOT NULL,
    payload_version    INTEGER       NOT NULL,
    payload_kind       VARCHAR(32)   NOT NULL,
    payload            JSONB         NOT NULL,
    pending_approval   JSONB,
    status             VARCHAR(16)   NOT NULL,
    version            BIGINT        NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (checkpoint_id)
);

-- run_id 唯一索引：一个 run 最多一个 HITL_RECOVERY Checkpoint（优先）+ 一个 THREAD_MEMORY
CREATE UNIQUE INDEX ukc_run_id ON agent_checkpoints (run_id, purpose);

-- thread_id 查询索引
CREATE INDEX idx_cp_thread ON agent_checkpoints (thread_id);

-- 用户待审批查询：只查 SUSPENDED 的 HITL_RECOVERY
CREATE INDEX idx_cp_pending_user ON agent_checkpoints (user_id, status, purpose)
    WHERE purpose = 'HITL_RECOVERY' AND status = 'SUSPENDED';

-- thread + purpose 组合查询
CREATE INDEX idx_cp_thread_purpose ON agent_checkpoints (user_id, thread_id, purpose);

-- execution_type / status 校验约束
ALTER TABLE agent_checkpoints ADD CONSTRAINT chk_cp_execution_type
    CHECK (execution_type IN ('REACT_AGENT', 'SUPERVISOR'));

ALTER TABLE agent_checkpoints ADD CONSTRAINT chk_cp_purpose
    CHECK (purpose IN ('HITL_RECOVERY', 'THREAD_MEMORY'));

ALTER TABLE agent_checkpoints ADD CONSTRAINT chk_cp_status
    CHECK (status IN ('SUSPENDED', 'RESUMING', 'COMPLETED', 'FAILED'));

-- version 非负
ALTER TABLE agent_checkpoints ADD CONSTRAINT chk_cp_version
    CHECK (version >= 0);

-- payload 大小限制（1MB = 1048576 bytes）
-- JSONB 的 length 是字符数而非字节数，此处用近似值
ALTER TABLE agent_checkpoints ADD CONSTRAINT chk_cp_payload_size
    CHECK (length(payload::text) <= 1048576);

-- ============================================================
-- approval_records
-- ============================================================
CREATE TABLE approval_records (
    approval_id        VARCHAR(64)   NOT NULL,
    run_id             VARCHAR(64)   NOT NULL,
    thread_id          VARCHAR(64)   NOT NULL,
    user_id            VARCHAR(64)   NOT NULL,
    agent_name         VARCHAR(128)  NOT NULL,
    operation_type     VARCHAR(8)    NOT NULL,
    operation_name     VARCHAR(128)  NOT NULL,
    risk_level         VARCHAR(8)    NOT NULL,
    reason             TEXT          NOT NULL,
    requested_at       TIMESTAMPTZ   NOT NULL,
    decided_by         VARCHAR(64),
    decision_status    VARCHAR(16),
    decided_at         TIMESTAMPTZ,
    decision_comment   TEXT,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (approval_id)
);

-- run_id 索引（与 checkpoint 同事务写入，按 run 查询）
CREATE INDEX idx_ar_run ON approval_records (run_id);

-- 用户待审批查询
CREATE INDEX idx_ar_pending_user ON approval_records (user_id, decision_status)
    WHERE decision_status IS NULL;

-- operation_type 约束
ALTER TABLE approval_records ADD CONSTRAINT chk_ar_operation_type
    CHECK (operation_type IN ('TOOL', 'NODE'));

-- risk_level 约束
ALTER TABLE approval_records ADD CONSTRAINT chk_ar_risk_level
    CHECK (risk_level IN ('SAFE', 'LOW', 'MEDIUM', 'HIGH'));

-- decision_status 约束（null = PENDING）
ALTER TABLE approval_records ADD CONSTRAINT chk_ar_decision_status
    CHECK (decision_status IS NULL OR decision_status IN ('APPROVED', 'REJECTED'));

-- decided_by / decided_at 在有决策时不得为空
ALTER TABLE approval_records ADD CONSTRAINT chk_ar_decision_consistency
    CHECK (
        (decision_status IS NULL AND decided_by IS NULL AND decided_at IS NULL)
        OR (decision_status IS NOT NULL AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
    );

-- ============================================================
-- tool_invocation_audits
-- ============================================================
CREATE TABLE tool_invocation_audits (
    audit_id           VARCHAR(64)   NOT NULL,
    run_id             VARCHAR(64)   NOT NULL,
    thread_id          VARCHAR(64)   NOT NULL,
    user_id            VARCHAR(64)   NOT NULL,
    tool_call_id       VARCHAR(64)   NOT NULL,
    tool_name          VARCHAR(128)  NOT NULL,
    argument_key_summary JSONB,
    authorized         BOOLEAN       NOT NULL,
    status             VARCHAR(16)   NOT NULL,
    success            BOOLEAN,
    error_code         VARCHAR(64),
    started_at         TIMESTAMPTZ   NOT NULL,
    completed_at       TIMESTAMPTZ,
    duration_ms        BIGINT,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (audit_id)
);

-- run_id + tool_call_id 索引（同一 run 的审计查询）
CREATE INDEX idx_taa_run ON tool_invocation_audits (run_id);

-- 用户审计查询
CREATE INDEX idx_taa_user ON tool_invocation_audits (user_id, started_at DESC);

-- status 约束
ALTER TABLE tool_invocation_audits ADD CONSTRAINT chk_taa_status
    CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'SUSPENDED', 'EXCEPTION'));

-- 终态一致性：终态必须有 completed_at 和 duration_ms
ALTER TABLE tool_invocation_audits ADD CONSTRAINT chk_taa_terminal_consistency
    CHECK (
        (status = 'STARTED' AND completed_at IS NULL AND duration_ms IS NULL)
        OR (status != 'STARTED' AND completed_at IS NOT NULL AND duration_ms IS NOT NULL)
    );

-- duration_ms 非负
ALTER TABLE tool_invocation_audits ADD CONSTRAINT chk_taa_duration
    CHECK (duration_ms IS NULL OR duration_ms >= 0);

-- argument_key_summary 大小限制（4KB）
ALTER TABLE tool_invocation_audits ADD CONSTRAINT chk_taa_arg_keys_size
    CHECK (argument_key_summary IS NULL OR length(argument_key_summary::text) <= 4096);
