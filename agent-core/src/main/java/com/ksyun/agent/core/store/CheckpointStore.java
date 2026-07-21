package com.ksyun.agent.core.store;

import com.ksyun.agent.core.run.AgentCheckpoint;

import java.util.Optional;

/**
 * Checkpoint 存储接口。
 */
public interface CheckpointStore {

    void save(AgentCheckpoint checkpoint);

    Optional<AgentCheckpoint> load(String runId);

    void delete(String runId);
}
