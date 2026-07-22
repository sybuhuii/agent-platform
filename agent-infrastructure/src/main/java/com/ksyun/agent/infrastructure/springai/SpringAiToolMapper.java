package com.ksyun.agent.infrastructure.springai;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.tool.ToolDefinition;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 将框架 ToolDefinition 转换为 Spring AI ToolCallback。
 * <p>
 * 仅向模型暴露 Schema，禁止执行真实 AgentTool。
 * 即使配置错误导致 Spring AI 内部执行，SafeToolCallback 也会快速失败。
 * 无状态、线程安全。
 */
public class SpringAiToolMapper {

    /**
     * 将框架工具定义列表转换为 Spring AI ToolCallback 列表。
     * <p>
     * 只转换 ModelRequest.tools 中明确传入的工具，
     * 不得自动把 ToolRegistry 中的全部工具发送给模型。
     */
    public List<ToolCallback> map(List<ToolDefinition> toolDefinitions) {
        if (toolDefinitions == null || toolDefinitions.isEmpty()) {
            return List.of();
        }
        return toolDefinitions.stream()
                .filter(Objects::nonNull)
                .map(SafeToolCallback::new)
                .map(ToolCallback.class::cast)
                .toList();
    }

    /**
     * 安全 ToolCallback：仅声明工具 Schema，调用时立即快速失败。
     * <p>
     * 这是架构层面最重要的安全机制之一：
     * - Spring AI 只负责把工具 Schema 发送给模型
     * - Spring AI 只负责返回模型生成的 ToolCall
     * - Spring AI 不得调用任何 AgentTool
     * - 真实工具执行只能走 ToolInvocationGateway → 拦截器链 → TerminalToolExecutor → AgentTool.execute
     * <p>
     * 即使 internalToolExecutionEnabled 配置错误导致 Spring AI 尝试内部执行，
     * SafeToolCallback 也会抛出异常，绝不会调用真实 AgentTool。
     */
    static class SafeToolCallback implements ToolCallback {

        private static final String EXECUTION_FORBIDDEN_MESSAGE =
                "Tool execution through Spring AI is strictly forbidden. "
                        + "All tool invocations must go through ToolInvocationGateway.";

        private final org.springframework.ai.tool.definition.ToolDefinition springAiToolDefinition;

        SafeToolCallback(ToolDefinition frameworkToolDef) {
            // 不暴露 requiredPermission、内部 Java 类名或其他安全策略信息
            this.springAiToolDefinition = DefaultToolDefinition.builder()
                    .name(frameworkToolDef.name())
                    .description(frameworkToolDef.description())
                    .inputSchema(frameworkToolDef.inputSchema())
                    .build();
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return springAiToolDefinition;
        }

        @Override
        public String call(String toolInput) {
            throw new AgentFrameworkException(
                    AgentErrorCode.TOOL_ACCESS_DENIED,
                    EXECUTION_FORBIDDEN_MESSAGE
            );
        }

        @Override
        public String call(String toolInput, @Nullable ToolContext toolContext) {
            throw new AgentFrameworkException(
                    AgentErrorCode.TOOL_ACCESS_DENIED,
                    EXECUTION_FORBIDDEN_MESSAGE
            );
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SafeToolCallback that)) return false;
            return Objects.equals(springAiToolDefinition.name(), that.springAiToolDefinition.name());
        }

        @Override
        public int hashCode() {
            return Objects.hash(springAiToolDefinition.name());
        }
    }
}
