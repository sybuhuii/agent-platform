package com.ksyun.agent.runtime.tool.authorization;

import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.security.ToolPermissionCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认工具权限评估器实现。
 * <p>
 * 纯 Java 实现，不依赖 ToolRegistry、Spring 或其他技术框架。
 * 不执行工具，不修改 RunContext，不抛出权限拒绝异常作为正常控制流。
 * 保持无状态和线程安全。
 * <p>
 * 评估规则：
 * 1. toolName 为空或 RunContext 为空时返回拒绝决策（失败关闭）
 * 2. RunContext 身份信息不完整（userId 或 sessionId 为空）时返回拒绝决策
 * 3. 通过 ToolPermissionCodes.invoke(toolName) 生成精确权限编码
 * 4. 先检查通配权限 tool:*:invoke，再检查精确权限
 * 5. 权限匹配必须完全匹配，不使用 contains、startsWith 或模糊匹配
 * 6. 不得根据 roles 做特殊放行
 * 7. 不得记录完整 permissions 集合
 */
public class DefaultToolPermissionEvaluator implements ToolPermissionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolPermissionEvaluator.class);

    @Override
    public ToolAuthorizationDecision evaluate(RunContext context, String toolName) {
        // 校验 toolName 非空
        if (toolName == null || toolName.isBlank()) {
            log.warn("Tool permission evaluation rejected: toolName is blank");
            return new ToolAuthorizationDecision(
                    false,
                    ToolPermissionCodes.ALL_INVOKE,
                    ToolAuthorizationDecision.MISSING_CONTEXT
            );
        }

        // 校验 RunContext 非空且身份信息完整
        if (context == null
                || context.userId() == null || context.userId().isBlank()
                || context.sessionId() == null || context.sessionId().isBlank()) {
            log.warn("Tool permission evaluation rejected: missing or incomplete RunContext for toolName={}", toolName);
            return new ToolAuthorizationDecision(
                    false,
                    ToolPermissionCodes.invoke(toolName),
                    ToolAuthorizationDecision.MISSING_CONTEXT
            );
        }

        // 通过 ToolPermissionCodes 生成精确权限编码
        String exactPermission = ToolPermissionCodes.invoke(toolName);
        String wildcardPermission = ToolPermissionCodes.ALL_INVOKE;

        // 先检查通配权限（通配优先）
        // 使用 Set.contains 精确匹配，不会错误匹配 tool:text:invoke 到 tool:text_search:invoke
        if (context.permissions().contains(wildcardPermission)) {
            return new ToolAuthorizationDecision(
                    true,
                    exactPermission,
                    ToolAuthorizationDecision.ALLOWED_WILDCARD
            );
        }

        // 检查精确权限
        if (context.permissions().contains(exactPermission)) {
            return new ToolAuthorizationDecision(
                    true,
                    exactPermission,
                    ToolAuthorizationDecision.ALLOWED_EXACT
            );
        }

        // 两者都没有时拒绝
        log.info("Tool permission denied: toolName={}, userId={}, reasonCode=MISSING_PERMISSION",
                toolName, context.userId());
        return new ToolAuthorizationDecision(
                false,
                exactPermission,
                ToolAuthorizationDecision.MISSING_PERMISSION
        );
    }
}
