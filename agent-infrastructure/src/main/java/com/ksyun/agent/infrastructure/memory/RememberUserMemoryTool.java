package com.ksyun.agent.infrastructure.memory;

import com.ksyun.agent.application.memory.LongTermMemoryApplicationService;
import com.ksyun.agent.application.memory.MemoryWriteCommand;
import com.ksyun.agent.application.memory.MemoryView;
import com.ksyun.agent.core.memory.MemoryCategory;
import com.ksyun.agent.core.tool.AgentTool;
import com.ksyun.agent.core.tool.ToolDefinition;
import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.core.tool.ToolRiskLevel;

import java.util.Map;
import java.util.Objects;

/**
 * 长期记忆写入工具。
 * <p>
 * 工具名称固定：remember_user_memory
 * 风险等级：SAFE
 * <p>
 * 工具参数固定包含：category、key、value
 * 不得包含：userId、sessionId、threadId、namespace、memoryId、version
 * <p>
 * namespace 映射固定：
 * PROFILE    → profile
 * PREFERENCE → preferences
 * FACT       → facts
 * RULE       → rules
 * <p>
 * 工具执行流程：
 * 1. 取得当前 ToolInvocation 中的 RunContext
 * 2. 从 RunContext 取得 userId
 * 3. 不得从参数取得 userId
 * 4. 解析 category
 * 5. 根据 category 确定 namespace
 * 6. 构造 MemoryWriteCommand
 * 7. 调用 LongTermMemoryApplicationService.putForAuthenticatedUser
 * 8. 返回结构化 ToolResult
 * <p>
 * ToolResult 只包含安全信息：category、namespace、key、version、success
 * 不得返回 userId、默认不得回显完整 value、不得返回 MemoryEntry 完整对象
 * 校验失败返回结构化工具失败
 * 不得吞掉 MemoryStore 异常
 * 不得访问 CheckpointStore
 * 不得直接修改 InMemoryMemoryStore 内部 Map
 */
public class RememberUserMemoryTool implements AgentTool {

    private static final String TOOL_NAME = "remember_user_memory";

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            TOOL_NAME,
            "保存用户明确表达的长期偏好、背景信息、稳定事实或长期规则。"
                    + "适合保存跨会话复用的信息，不适合保存一次性请求、临时参数或敏感信息。"
                    + "同一 category 和 key 最多调用一次。",
            """
            {
              "type": "object",
              "properties": {
                "category": {
                  "type": "string",
                  "enum": ["PROFILE", "PREFERENCE", "FACT", "RULE"],
                  "description": "记忆类别：PROFILE=个人背景，PREFERENCE=偏好，FACT=事实，RULE=规则"
                },
                "key": {
                  "type": "string",
                  "maxLength": 128,
                  "description": "记忆键名，使用稳定语义，例如 programming_language"
                },
                "value": {
                  "type": "string",
                  "maxLength": 4096,
                  "description": "记忆值，用户明确表达的内容"
                }
              },
              "required": ["category", "key", "value"],
              "additionalProperties": false
            }
            """,
            "tool:remember_user_memory:invoke",
            ToolRiskLevel.LOW
    );

    private static final Map<MemoryCategory, String> CATEGORY_NAMESPACE_MAP = Map.of(
            MemoryCategory.PROFILE, "profile",
            MemoryCategory.PREFERENCE, "preferences",
            MemoryCategory.FACT, "facts",
            MemoryCategory.RULE, "rules"
    );

    private final LongTermMemoryApplicationService memoryService;

    public RememberUserMemoryTool(LongTermMemoryApplicationService memoryService) {
        this.memoryService = Objects.requireNonNull(memoryService, "memoryService must not be null");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        // 1. 取得当前 ToolInvocation 中的 RunContext
        if (invocation.runContext() == null) {
            return ToolResult.failure(
                    "TOOL_ACCESS_DENIED",
                    "No authenticated context available"
            );
        }

        String userId = invocation.runContext().userId();
        if (userId == null || userId.isBlank()) {
            return ToolResult.failure(
                    "TOOL_ACCESS_DENIED",
                    "Authenticated userId required"
            );
        }

        // 2. 解析参数
        Map<String, Object> arguments = invocation.toolCall().arguments();
        String categoryStr = (String) arguments.get("category");
        String key = (String) arguments.get("key");
        String value = (String) arguments.get("value");

        // 3. 校验参数
        if (categoryStr == null || categoryStr.isBlank()) {
            return ToolResult.failure(
                    "INVALID_ARGUMENT",
                    "category is required"
            );
        }
        if (key == null || key.isBlank()) {
            return ToolResult.failure(
                    "INVALID_ARGUMENT",
                    "key is required"
            );
        }
        if (value == null || value.isBlank()) {
            return ToolResult.failure(
                    "INVALID_ARGUMENT",
                    "value is required"
            );
        }

        // 4. 解析 category
        MemoryCategory category;
        try {
            category = MemoryCategory.valueOf(categoryStr.trim());
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(
                    "INVALID_ARGUMENT",
                    "Invalid category: " + categoryStr + ". Must be PROFILE, PREFERENCE, FACT, or RULE"
            );
        }

        // 5. 根据 category 确定 namespace
        String namespace = CATEGORY_NAMESPACE_MAP.get(category);
        if (namespace == null) {
            return ToolResult.failure(
                    "INVALID_ARGUMENT",
                    "No namespace mapping for category: " + category
            );
        }

        // 6. 构造 MemoryWriteCommand
        MemoryWriteCommand command = new MemoryWriteCommand(
                namespace,
                key.trim(),
                value.trim(),
                category,
                Map.of()
        );

        // 7. 调用 LongTermMemoryApplicationService.putForAuthenticatedUser
        try {
            MemoryView result = memoryService.putForAuthenticatedUser(userId, command);

            // 8. 返回结构化 ToolResult
            // 只包含安全信息：category、namespace、key、version、success
            // 不得返回 userId、默认不得回显完整 value
            return ToolResult.success(
                    "Memory saved successfully",
                    Map.of(
                            "category", result.category(),
                            "namespace", result.namespace(),
                            "key", result.key(),
                            "version", result.version(),
                            "success", true
                    )
            );
        } catch (Exception e) {
            // 不得吞掉 MemoryStore 异常
            String errorCode = e instanceof com.ksyun.agent.core.exception.AgentFrameworkException afe
                    ? afe.getErrorCode().name()
                    : "MEMORY_STORE_FAILED";
            return ToolResult.failure(
                    errorCode,
                    "Failed to save memory: " + e.getMessage()
            );
        }
    }
}
