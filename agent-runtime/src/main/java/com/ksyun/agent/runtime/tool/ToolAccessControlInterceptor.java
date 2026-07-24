package com.ksyun.agent.runtime.tool;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.runtime.tool.authorization.ToolAuthorizationDecision;
import com.ksyun.agent.runtime.tool.authorization.ToolPermissionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 工具 ACL 拦截器。
 * <p>
 * 通过 ToolPermissionEvaluator 评估当前用户是否有权调用指定工具。
 * <p>
 * 授权规则：
 * 1. 拥有 tool:*:invoke 时允许调用任意已注册工具
 * 2. 拥有 tool:{当前工具名}:invoke 时允许调用该工具
 * 3. 两者都没有时拒绝
 * 4. 角色名称本身不代表自动放行或自动拒绝
 * 5. 最终权限由 Session 创建时保存的 permissions 快照决定
 * 6. 权限集合为空时默认拒绝全部工具
 * 7. RunContext 为空或身份信息不完整时必须失败关闭，不得默认放行
 * <p>
 * 拒绝时：
 * - 不调用拦截器链 next
 * - 不解析或执行工具参数
 * - 不直接抛出普通权限异常终止 ReAct
 * - 构造 success=false 的 ToolResult，errorCode=PERMISSION_DENIED
 * - content 使用可安全回灌 LLM 的说明
 * <p>
 * 不在日志中记录完整 permissions 集合、roles、sessionId 或密码。
 * 拦截器无状态、可并发复用。
 */
public class ToolAccessControlInterceptor implements ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ToolAccessControlInterceptor.class);

    private static final String DENIED_CONTENT_TEMPLATE =
            "当前用户没有调用工具\"%s\"的权限。请不要假设工具已经执行，可向用户说明权限不足，或选择其他已授权工具。";

    private final ToolPermissionEvaluator permissionEvaluator;

    public ToolAccessControlInterceptor(ToolPermissionEvaluator permissionEvaluator) {
        this.permissionEvaluator = permissionEvaluator;
    }

    @Override
    public int order() {
        return -200;
    }

    @Override
    public ToolResult intercept(ToolInvocation invocation, ToolExecutionChain chain) {
        String toolName = invocation.toolCall().name();

        // 调用 ToolPermissionEvaluator 评估权限
        ToolAuthorizationDecision decision = permissionEvaluator.evaluate(invocation.runContext(), toolName);

        // 允许时调用拦截器链 next
        if (decision.allowed()) {
            log.info("Tool access allowed: toolName={}, userId={}, reasonCode={}",
                    toolName,
                    invocation.runContext() != null ? invocation.runContext().userId() : "unknown",
                    decision.reasonCode());
            return chain.proceed(invocation);
        }

        // 拒绝时不得调用 next，不得解析或执行工具参数
        // 不得直接抛出普通权限异常终止 ReAct
        // 构造 success=false 的 ToolResult
        String deniedContent = String.format(DENIED_CONTENT_TEMPLATE, toolName);

        // 日志记录非敏感信息
        log.warn("Tool access denied: toolName={}, userId={}, runId={}, authorization=DENIED, reasonCode={}",
                toolName,
                invocation.runContext() != null ? invocation.runContext().userId() : "unknown",
                invocation.runContext() != null ? invocation.runContext().runId() : "unknown",
                decision.reasonCode());

        // metadata 仅包含非敏感信息
        return ToolResult.failure(
                AgentErrorCode.PERMISSION_DENIED.name(),
                deniedContent,
                Map.of(
                        "denied", true,
                        "requiredPermission", decision.requiredPermission()
                )
        );
    }
}
