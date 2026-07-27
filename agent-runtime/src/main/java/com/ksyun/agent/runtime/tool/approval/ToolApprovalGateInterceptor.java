package com.ksyun.agent.runtime.tool.approval;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.tool.ToolDefinition;
import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.runtime.registry.ToolRegistry;
import com.ksyun.agent.runtime.tool.ToolExecutionChain;
import com.ksyun.agent.runtime.tool.ToolInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具审批闸门拦截器。
 * <p>
 * 在 ACL 权限校验之后、参数校验之前执行（order = -150）。
 * <p>
 * 规则：
 * - 通过 DangerousToolApprovalPolicy 判断是否需要审批
 * - 需要审批时抛出 AgentFrameworkException(APPROVAL_REQUIRED)
 * - 不需要审批时调用 chain.proceed 继续执行
 * - 不得根据角色名称做特殊绕行
 * - 审批拦截器不构造 ToolResult，中断信号由异常承载
 * <p>
 * 异常传播路径：
 * ToolApprovalGateInterceptor 抛出 APPROVAL_REQUIRED
 * → ToolExceptionHandlingInterceptor 捕获转为 ToolResult.failure(APPROVAL_REQUIRED, ...)
 * → DefaultReactToolExecutionNode 识别 APPROVAL_REQUIRED 错误码
 * → 设置 STOP_REASON=SUSPENDED
 * → ReactRouter 路由到 SUSPEND 节点
 * → ReactSuspendNode 保存 Checkpoint
 */
public class ToolApprovalGateInterceptor implements ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ToolApprovalGateInterceptor.class);

    private final ToolRegistry toolRegistry;
    private final DangerousToolApprovalPolicy approvalPolicy;

    public ToolApprovalGateInterceptor(ToolRegistry toolRegistry, DangerousToolApprovalPolicy approvalPolicy) {
        this.toolRegistry = toolRegistry;
        this.approvalPolicy = approvalPolicy;
    }

    @Override
    public int order() {
        return -150;
    }

    @Override
    public ToolResult intercept(ToolInvocation invocation, ToolExecutionChain chain) {
        String toolName = invocation.toolCall().name();

        // 查找工具定义
        var agentTool = toolRegistry.find(toolName);
        if (agentTool.isEmpty()) {
            // 工具未注册，交给后续处理（TerminalToolExecutor 会抛 TOOL_NOT_FOUND）
            return chain.proceed(invocation);
        }

        ToolDefinition definition = agentTool.get().definition();

        // 判断是否需要审批
        if (approvalPolicy.requiresApproval(definition, invocation.runContext())) {
            log.info("Tool approval required: toolName={}, riskLevel={}, userId={}",
                    toolName, definition.riskLevel(),
                    invocation.runContext() != null ? invocation.runContext().userId() : "unknown");

            // 抛出中断信号，不构造 ToolResult
            // 异常由 ToolExceptionHandlingInterceptor 捕获转为 ToolResult.failure(APPROVAL_REQUIRED)
            throw new AgentFrameworkException(AgentErrorCode.APPROVAL_REQUIRED,
                    "Tool '" + toolName + "' requires manual approval (riskLevel=" + definition.riskLevel() + ")");
        }

        // 不需要审批，继续执行
        return chain.proceed(invocation);
    }
}
