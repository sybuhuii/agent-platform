package com.ksyun.agent.bootstrap.sample.node;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.runtime.hitl.node.NodeResumeHandler;
import com.ksyun.agent.runtime.react.ReactAgentGraphFactory;
import com.ksyun.agent.runtime.react.ReactAgentState;
import com.ksyun.agent.runtime.react.ReactNodeNames;
import org.bsc.langgraph4j.CompiledGraph;

import java.util.Objects;
import java.util.function.Supplier;

/** 节点审批样例的白名单恢复处理器。 */
public final class SampleNodeResumeHandler implements NodeResumeHandler<SampleNodeResumeData> {

    private final Supplier<ReactAgentGraphFactory> graphFactorySupplier;

    public SampleNodeResumeHandler(Supplier<ReactAgentGraphFactory> graphFactorySupplier) {
        this.graphFactorySupplier = Objects.requireNonNull(graphFactorySupplier);
    }

    @Override
    public String key() {
        return SampleNodeApprovalNode.HANDLER_KEY;
    }

    @Override
    public Class<SampleNodeResumeData> resumeDataType() {
        return SampleNodeResumeData.class;
    }

    @Override
    public void validate(AgentCheckpoint checkpoint, SampleNodeResumeData resumeData) {
        if (!ReactNodeNames.PRE_EXECUTION.equals(checkpoint.nodeName())
                || !SampleNodeApprovalNode.STEP_ID.equals(resumeData.stepId())
                || resumeData.continuationIndex() != 1) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Sample node continuation data is invalid");
        }
    }

    @Override
    public CompiledGraph<ReactAgentState> compileResumeGraph(SampleNodeResumeData resumeData) {
        return graphFactorySupplier.get().compileForNodeResume(
                ReactNodeNames.PRE_EXECUTION,
                new SampleNodeResumeAction(resumeData));
    }
}
