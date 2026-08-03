package com.ksyun.agent.bootstrap.sample.node;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.message.AssistantAgentMessage;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.runtime.react.ReactAgentState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/** 从保存的 continuationIndex 恢复样例节点，不重新执行中断前逻辑。 */
public final class SampleNodeResumeAction implements NodeAction<ReactAgentState> {

    private final SampleNodeResumeData resumeData;

    public SampleNodeResumeAction(SampleNodeResumeData resumeData) {
        this.resumeData = resumeData;
    }

    @Override
    public Map<String, Object> apply(ReactAgentState state) {
        PendingApproval approval = getPendingApproval(state);
        boolean approved = approval != null && approval.status() == ApprovalStatus.APPROVED;
        String content = approved
                ? "节点审批已通过，已从续跑位置 " + resumeData.continuationIndex()
                    + " 恢复并完成演示发布步骤。"
                : "节点审批已拒绝，演示发布步骤已回退，受控操作未执行。";
        AgentResult result = AgentResult.success(
                getAgentDefinition(state).name(), content);

        Map<String, Object> updates = new HashMap<>();
        updates.put(MESSAGES, new ArrayList<>(List.of(
                new AssistantAgentMessage(content, List.of()))));
        updates.put(FINAL_RESULT, result);
        updates.put(RUN_STATUS, RunStatus.COMPLETED);
        updates.put(STOP_REASON, null);
        updates.put(FAILURE_MESSAGE, null);
        updates.put(FAILURE_ERROR_CODE, null);
        updates.put(PENDING_APPROVAL, null);
        updates.put(CHECKPOINT_ID, null);
        updates.put(NODE_RESUME_HANDLER_KEY, null);
        updates.put(NODE_RESUME_DATA, null);
        return updates;
    }
}
