package com.ksyun.agent.runtime.tool.authorization;

import com.ksyun.agent.core.run.RunContext;

/**
 * 工具权限评估器接口。
 * <p>
 * 纯 Java 接口，不依赖 ToolRegistry、Spring 或其他技术框架。
 * 不执行工具，不修改 RunContext，不抛出权限拒绝异常作为正常控制流。
 * 保持无状态和线程安全。
 */
public interface ToolPermissionEvaluator {

    /**
     * 评估当前用户是否有权调用指定工具。
     *
     * @param context  运行上下文，包含 userId、permissions 等
     * @param toolName 工具名称，非空
     * @return 授权决策，不为 null
     */
    ToolAuthorizationDecision evaluate(RunContext context, String toolName);
}
