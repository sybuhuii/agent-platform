package com.ksyun.agent.runtime.hitl.node;

import com.ksyun.agent.core.approval.NodeResumeData;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.runtime.react.ReactAgentState;
import org.bsc.langgraph4j.CompiledGraph;

public interface NodeResumeHandler<D extends NodeResumeData> {

    String key();

    Class<D> resumeDataType();

    void validate(AgentCheckpoint checkpoint, D resumeData);

    CompiledGraph<ReactAgentState> compileResumeGraph(D resumeData);
}