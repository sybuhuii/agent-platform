package com.ksyun.agent.core.tool;

import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.run.RunContext;

import java.util.Optional;

/**
 * 工具调用上下文，将 ToolCall 与运行上下文和审批信息绑定。
 *
 * @param toolCall   工具调用请求
 * @param runContext 运行上下文
 * @param approval   当前审批上下文，普通调用为 Optional.empty()
 */
public record ToolInvocation(
        ToolCall toolCall,
        RunContext runContext,
        Optional<PendingApproval> approval
) {

    /**
     * 兼容两参数构造器，approval 默认为 Optional.empty()。
     */
    public ToolInvocation(ToolCall toolCall, RunContext runContext) {
        this(toolCall, runContext, Optional.empty());
    }

    public ToolInvocation {
        if (toolCall == null) {
            throw new IllegalArgumentException("toolCall must not be null");
        }
        if (runContext == null) {
            throw new IllegalArgumentException("runContext must not be null");
        }
        approval = approval == null ? Optional.empty() : approval;
    }
}
