ALTER TABLE tool_invocation_audits
    ALTER COLUMN thread_id TYPE VARCHAR(256);

ALTER TABLE agent_checkpoints
    ALTER COLUMN thread_id TYPE VARCHAR(256);

ALTER TABLE approval_records
    ALTER COLUMN thread_id TYPE VARCHAR(256);