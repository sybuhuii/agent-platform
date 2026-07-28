package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.AssistantAgentMessage;
import com.ksyun.agent.core.message.SummaryAgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import com.ksyun.agent.core.message.ToolAgentMessage;
import com.ksyun.agent.core.tool.ToolCall;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上下文消息历史验证器，纯 Java 实现。
 * <p>
 * 职责：
 * - 在分组前或分组过程中验证消息历史的合法性
 * - 非法时使用明确结构化错误
 * <p>
 * 检查以下非法情况：
 * 1. ToolAgentMessage 找不到对应 Assistant ToolCall
 * 2. Assistant 声明的 ToolCall 缺少 ToolAgentMessage
 * 3. 相同 ToolCall ID 出现多个结果
 * 4. ToolCall ID 为空
 * 5. ToolAgentMessage 关联 ID 为空
 * 6. 同一 ToolCall ID 在多个 Assistant 消息中重复
 * 7. 工具交互尚未结束就出现下一条普通对话消息
 * 8. 消息集合中存在 null 元素
 * <p>
 * 约束：
 * - 不得静默删除孤立 Tool 消息
 * - 不得自动伪造 ToolResult 补齐
 * - 不得根据工具名称模糊配对
 * - 错误信息可包含消息下标和安全 ToolCall 短标识
 * - 不得包含完整工具参数或结果
 * - 不得记录完整消息历史
 * - 不得把非法历史继续发送给模型
 * - 保持纯 Java
 * - 线程安全、无状态
 */
public class ContextMessageHistoryValidator {

    /**
     * 验证消息历史的合法性。
     *
     * @param messages 消息列表
     * @throws AgentFrameworkException 验证失败时抛出
     */
    public void validate(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        // 检查 8：消息集合中存在 null 元素
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) == null) {
                throw new AgentFrameworkException(
                        AgentErrorCode.INVALID_MESSAGE_HISTORY,
                        "Message at index " + i + " is null");
            }
        }

        // 摘要校验规则：
        // 1. 上下文中最多存在一条 SummaryAgentMessage
        // 2. 摘要 content 不能为空（由 SummaryAgentMessage 构造器保证）
        // 3. 摘要不得位于未完成的工具交互内部
        // 4. 摘要不得作为 ToolResult
        int summaryCount = 0;
        for (int i = 0; i < messages.size(); i++) {
            AgentMessage msg = messages.get(i);
            if (msg instanceof SummaryAgentMessage) {
                summaryCount++;
                if (summaryCount > 1) {
                    throw new AgentFrameworkException(
                            AgentErrorCode.INVALID_MESSAGE_HISTORY,
                            "At most one SummaryAgentMessage allowed, found multiple at index " + i);
                }
            }
        }

        // 建立 toolCallId -> 首次出现的 Assistant 索引
        Map<String, Integer> toolCallIdToAssistantIndex = new HashMap<>();
        // 检查 4：ToolCall ID 为空
        // 检查 6：同一 ToolCall ID 在多个 Assistant 消息中重复
        for (int i = 0; i < messages.size(); i++) {
            AgentMessage msg = messages.get(i);
            if (msg instanceof AssistantAgentMessage assistant) {
                for (ToolCall tc : assistant.toolCalls()) {
                    if (tc.id() == null || tc.id().isBlank()) {
                        throw new AgentFrameworkException(
                                AgentErrorCode.INVALID_MESSAGE_HISTORY,
                                "ToolCall ID is blank at message index " + i);
                    }
                    Integer prevIndex = toolCallIdToAssistantIndex.putIfAbsent(tc.id(), i);
                    if (prevIndex != null && prevIndex != i) {
                        throw new AgentFrameworkException(
                                AgentErrorCode.INVALID_MESSAGE_HISTORY,
                                "Duplicate ToolCall ID '" + shortenId(tc.id())
                                        + "' found at message index " + i
                                        + ", previously declared at index " + prevIndex);
                    }
                }
            }
        }

        // 检查 5：ToolAgentMessage 关联 ID 为空
        // 检查 1：ToolAgentMessage 找不到对应 Assistant ToolCall
        // 检查 3：相同 ToolCall ID 出现多个结果
        Map<String, Integer> toolCallIdToToolResultIndex = new HashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            AgentMessage msg = messages.get(i);
            if (msg instanceof ToolAgentMessage tool) {
                if (tool.toolCallId() == null || tool.toolCallId().isBlank()) {
                    throw new AgentFrameworkException(
                            AgentErrorCode.INVALID_MESSAGE_HISTORY,
                            "ToolAgentMessage at index " + i + " has blank toolCallId");
                }
                if (!toolCallIdToAssistantIndex.containsKey(tool.toolCallId())) {
                    throw new AgentFrameworkException(
                            AgentErrorCode.TOOL_MESSAGE_PAIRING_FAILED,
                            "ToolAgentMessage at index " + i + " references unknown ToolCall ID '"
                                    + shortenId(tool.toolCallId()) + "'");
                }
                Integer prevResultIndex = toolCallIdToToolResultIndex.putIfAbsent(tool.toolCallId(), i);
                if (prevResultIndex != null) {
                    throw new AgentFrameworkException(
                            AgentErrorCode.INVALID_MESSAGE_HISTORY,
                            "Duplicate ToolResult for ToolCall ID '" + shortenId(tool.toolCallId())
                                    + "' at message index " + i
                                    + ", previous result at index " + prevResultIndex);
                }
            }
        }

        // 检查 7：工具交互尚未结束就出现下一条普通对话消息
        // 检查 2：Assistant 声明的 ToolCall 缺少 ToolAgentMessage
        Set<String> pendingToolCallIds = new HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            AgentMessage msg = messages.get(i);

            if (msg instanceof AssistantAgentMessage assistant && !assistant.toolCalls().isEmpty()) {
                // 开始新的工具交互，收集 ToolCall IDs
                for (ToolCall tc : assistant.toolCalls()) {
                    pendingToolCallIds.add(tc.id());
                }
            } else if (msg instanceof ToolAgentMessage tool) {
                // Tool 结果，从 pending 中移除
                pendingToolCallIds.remove(tool.toolCallId());
            } else {
                // 普通消息（User、无 ToolCall 的 Assistant、System、Summary）
                // 如果是 System 或 Summary 消息，不视为中断工具交互
                if (msg instanceof SystemAgentMessage || msg instanceof SummaryAgentMessage) {
                    // System 和 Summary 消息不中断工具交互检查流程
                    continue;
                }
                // 非系统普通消息出现时，pending 必须为空
                if (!pendingToolCallIds.isEmpty()) {
                    throw new AgentFrameworkException(
                            AgentErrorCode.TOOL_MESSAGE_PAIRING_FAILED,
                            "Non-tool message at index " + i
                                    + " interrupts incomplete tool interaction"
                                    + " (" + pendingToolCallIds.size() + " pending ToolCall result(s))");
                }
            }
        }

        // 最终检查：所有声明的 ToolCall 都应该有结果
        for (int i = 0; i < messages.size(); i++) {
            AgentMessage msg = messages.get(i);
            if (msg instanceof AssistantAgentMessage assistant) {
                for (ToolCall tc : assistant.toolCalls()) {
                    if (!toolCallIdToToolResultIndex.containsKey(tc.id())) {
                        throw new AgentFrameworkException(
                                AgentErrorCode.TOOL_MESSAGE_PAIRING_FAILED,
                                "Assistant at index " + i + " declares ToolCall ID '"
                                        + shortenId(tc.id()) + "' with no matching ToolResult");
                    }
                }
            }
        }
    }

    /**
     * 截短 ToolCall ID 用于安全日志输出。
     * <p>
     * 不得暴露完整工具参数或结果。
     */
    private String shortenId(String id) {
        if (id == null) {
            return "<null>";
        }
        return id.length() <= 8 ? id : id.substring(0, 8) + "...";
    }
}
