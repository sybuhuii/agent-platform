-- Correct the conversation, checkpoint, approval and audit schemas introduced by V4/V5.
-- V4/V5 may already have been applied, therefore all corrections live in a new migration.

-- ============================================================
-- Conversation participant and visible result metadata
-- ============================================================
ALTER TABLE agent_threads RENAME COLUMN agent_name TO participant_name;

ALTER TABLE agent_threads
    ADD COLUMN participant_type VARCHAR(16) NOT NULL DEFAULT 'AGENT';

UPDATE agent_threads t
SET participant_name = COALESCE(NULLIF(trim(t.participant_name), ''), cp.agent_name),
    participant_type = CASE
        WHEN cp.execution_type = 'SUPERVISOR' THEN 'SUPERVISOR'
        ELSE 'AGENT'
    END
FROM (
    SELECT DISTINCT ON (thread_id)
        thread_id, agent_name, execution_type
    FROM agent_checkpoints
    ORDER BY thread_id, updated_at DESC, version DESC, checkpoint_id DESC
) cp
WHERE cp.thread_id = t.thread_id;

UPDATE agent_threads
SET participant_name = 'legacy-unknown'
WHERE participant_name IS NULL OR length(trim(participant_name)) = 0;

ALTER TABLE agent_threads ALTER COLUMN participant_name SET NOT NULL;

UPDATE agent_threads
SET title = left(title, 80)
WHERE length(title) > 80;

ALTER TABLE agent_threads
    ADD CONSTRAINT chk_at_participant_type
        CHECK (participant_type IN ('AGENT', 'SUPERVISOR')),
    ADD CONSTRAINT chk_at_participant_name_nonempty
        CHECK (length(trim(participant_name)) > 0),
    ADD CONSTRAINT chk_at_time_order
        CHECK (last_message_at >= created_at AND updated_at >= created_at),
    ADD CONSTRAINT chk_at_title_length
        CHECK (length(title) <= 80);

ALTER TABLE agent_threads ALTER COLUMN next_sequence SET DEFAULT 0;

ALTER TABLE agent_messages
    ADD COLUMN run_id VARCHAR(64),
    ADD COLUMN success BOOLEAN,
    ADD COLUMN error_code VARCHAR(64),
    ADD COLUMN run_status VARCHAR(16);

UPDATE agent_messages
SET run_id = 'legacy-' || message_id
WHERE run_id IS NULL;

UPDATE agent_messages
SET deduplication_key = 'legacy-' || message_id
WHERE deduplication_key IS NULL;

UPDATE agent_messages
SET success = TRUE, run_status = 'COMPLETED'
WHERE role = 'ASSISTANT' AND success IS NULL AND run_status IS NULL;

ALTER TABLE agent_messages ALTER COLUMN run_id SET NOT NULL;
ALTER TABLE agent_messages ALTER COLUMN deduplication_key SET NOT NULL;

ALTER TABLE agent_messages
    ADD CONSTRAINT fk_am_thread
        FOREIGN KEY (thread_id) REFERENCES agent_threads (thread_id),
    ADD CONSTRAINT chk_am_run_status
        CHECK (run_status IS NULL OR run_status IN (
            'CREATED', 'RUNNING', 'INTERRUPTED', 'SUSPENDED', 'COMPLETED', 'FAILED'
        )),
    ADD CONSTRAINT chk_am_role_metadata
        CHECK (
            (role = 'USER' AND success IS NULL AND error_code IS NULL AND run_status IS NULL)
            OR
            (role = 'ASSISTANT' AND success IS NOT NULL AND run_status IS NOT NULL)
        );

CREATE INDEX idx_messages_run_id ON agent_messages (run_id)
    WHERE run_id IS NOT NULL;

-- ============================================================
-- Versioned checkpoint schema constraints and current approval link
-- ============================================================
ALTER TABLE agent_checkpoints
    ADD COLUMN current_approval_id VARCHAR(64);

UPDATE agent_checkpoints
SET current_approval_id = pending_approval->'payload'->>'approvalId'
WHERE pending_approval IS NOT NULL;

ALTER TABLE agent_checkpoints
    ADD CONSTRAINT chk_cp_payload_version CHECK (payload_version >= 1),
    ADD CONSTRAINT chk_cp_payload_kind CHECK (
        payload_kind IN (
            'REACT_HITL',
            'SUPERVISOR_HITL',
            'THREAD_MEMORY_REACT',
            'THREAD_MEMORY_SUPERVISOR'
        )
    ),
    ADD CONSTRAINT chk_cp_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    ADD CONSTRAINT chk_cp_time_order CHECK (updated_at >= created_at),
    ADD CONSTRAINT chk_cp_current_approval CHECK (
        (purpose = 'THREAD_MEMORY' AND current_approval_id IS NULL AND pending_approval IS NULL)
        OR purpose = 'HITL_RECOVERY'
    );

CREATE INDEX idx_cp_run_latest
    ON agent_checkpoints (run_id, purpose, updated_at DESC, version DESC, checkpoint_id DESC);

DROP INDEX IF EXISTS idx_cp_thread;
DROP INDEX IF EXISTS idx_cp_thread_purpose;

CREATE INDEX idx_cp_thread_latest
    ON agent_checkpoints (thread_id, updated_at DESC, version DESC, checkpoint_id DESC);

CREATE INDEX idx_cp_user_thread_latest
    ON agent_checkpoints (
        user_id, thread_id, purpose, updated_at DESC, version DESC, checkpoint_id DESC
    );

-- ============================================================
-- Durable approval lifecycle records
-- ============================================================
ALTER TABLE approval_records RENAME COLUMN decision_status TO status;

ALTER TABLE approval_records DROP CONSTRAINT chk_ar_decision_status;
ALTER TABLE approval_records DROP CONSTRAINT chk_ar_decision_consistency;

ALTER TABLE approval_records
    ADD COLUMN checkpoint_id VARCHAR(64),
    ADD COLUMN node_name VARCHAR(128),
    ADD COLUMN safe_arguments JSONB NOT NULL DEFAULT '{}';

UPDATE approval_records ar
SET checkpoint_id = cp.checkpoint_id,
    node_name = cp.node_name,
    safe_arguments = COALESCE(
        cp.pending_approval->'payload'->'safeArguments',
        '{}'::jsonb
    )
FROM agent_checkpoints cp
WHERE cp.run_id = ar.run_id
  AND cp.purpose = 'HITL_RECOVERY';

UPDATE approval_records SET status = 'PENDING' WHERE status IS NULL;

ALTER TABLE approval_records ALTER COLUMN status SET NOT NULL;
ALTER TABLE approval_records ALTER COLUMN status SET DEFAULT 'PENDING';

ALTER TABLE approval_records
    ADD CONSTRAINT chk_ar_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    ADD CONSTRAINT chk_ar_safe_arguments_object
        CHECK (jsonb_typeof(safe_arguments) = 'object'),
    ADD CONSTRAINT chk_ar_decision_consistency CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status IN ('APPROVED', 'REJECTED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_ar_time_order
        CHECK (updated_at >= created_at);

DROP INDEX IF EXISTS idx_ar_pending_user;
CREATE INDEX idx_ar_user_status_requested
    ON approval_records (user_id, status, requested_at);
CREATE INDEX idx_ar_thread_requested
    ON approval_records (thread_id, requested_at DESC);
CREATE INDEX idx_ar_requested_at
    ON approval_records (requested_at DESC);

-- ============================================================
-- Tool audit query indexes and state consistency
-- ============================================================
CREATE INDEX idx_taa_thread_started
    ON tool_invocation_audits (thread_id, started_at DESC);
CREATE INDEX idx_taa_tool_status_started
    ON tool_invocation_audits (tool_name, status, started_at DESC);
CREATE INDEX idx_taa_run_tool_call
    ON tool_invocation_audits (run_id, tool_call_id);

ALTER TABLE tool_invocation_audits
    ADD CONSTRAINT chk_taa_success_consistency CHECK (
        (status = 'STARTED' AND success IS NULL)
        OR (status = 'SUCCEEDED' AND success = TRUE)
        OR (status IN ('FAILED', 'SUSPENDED', 'EXCEPTION') AND success = FALSE)
    ),
    ADD CONSTRAINT chk_taa_time_order CHECK (
        updated_at >= created_at
        AND (completed_at IS NULL OR completed_at >= started_at)
    );
