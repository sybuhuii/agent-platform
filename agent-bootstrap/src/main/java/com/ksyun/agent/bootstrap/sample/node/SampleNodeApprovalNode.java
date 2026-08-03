package com.ksyun.agent.bootstrap.sample.node;

import com.ksyun.agent.core.approval.NodeInterruptRequest;
import com.ksyun.agent.core.tool.ToolRiskLevel;
import com.ksyun.agent.runtime.hitl.node.NodeHitlInterruptService;
import com.ksyun.agent.runtime.react.ReactAgentState;
import com.ksyun.agent.runtime.react.ReactNodeNames;
import com.ksyun.agent.runtime.react.ReactStateKeys;
import com.ksyun.agent.runtime.react.node.ReactPreExecutionNode;

import java.util.Map;
import java.util.Objects;

/** 仅对 node_approval_demo_agent 生效的节点审批演示入口。 */
public final class SampleNodeApprovalNode implements ReactPreExecutionNode {

    public static final String AGENT_NAME = "node_approval_demo_agent";
    public static final String HANDLER_KEY = "sample_node_approval";
    public static final String STEP_ID = "publish_demo_snapshot";

    private final NodeHitlInterruptService interruptService;

    public SampleNodeApprovalNode(NodeHitlInterruptService interruptService) {
        this.interruptService = Objects.requireNonNull(interruptService);
    }

    @Override
    public Map<String, Object> apply(ReactAgentState state) {
        if (!AGENT_NAME.equals(ReactStateKeys.getAgentDefinition(state).name())) {
            return Map.of();
        }
        NodeInterruptRequest request = new NodeInterruptRequest(
                ReactNodeNames.PRE_EXECUTION,
                "publish_demo_snapshot",
                "演示节点即将执行受控发布步骤，需要人工确认。",
                ToolRiskLevel.HIGH,
                Map.of("step", "发布演示快照", "continuationIndex", 1),
                HANDLER_KEY,
                new SampleNodeResumeData(STEP_ID, 1));
        return interruptService.suspend(state, request);
    }
}
