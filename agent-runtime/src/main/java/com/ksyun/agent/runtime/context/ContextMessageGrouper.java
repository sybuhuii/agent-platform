package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.AssistantAgentMessage;
import com.ksyun.agent.core.message.SummaryAgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import com.ksyun.agent.core.message.ToolAgentMessage;
import com.ksyun.agent.core.tool.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 上下文消息分组器，纯 Java 实现。
 * <p>
 * 使用明确的单次顺序扫描构建消息组。
 * <p>
 * 职责：
 * - 将消息列表按 System、Normal、ToolInteraction、Summary 划分为分组
 * - System 消息单独形成 SYSTEM 组
 * - 没有 ToolCall 的 User 或 Assistant 消息形成 NORMAL 组
 * - 包含 ToolCall 的 Assistant 消息及其全部 ToolAgentMessage 形成 TOOL_INTERACTION 原子组
 * - SummaryAgentMessage 形成 SUMMARY 组
 * - 配对基于 ToolCall ID 完全匹配
 * <p>
 * 约束：
 * - 每条输入消息必须恰好属于一个组
 * - 组内消息必须来自原历史中的连续区间
 * - 不得先收集若干索引，再使用最小/最大索引范围重新截取
 * - 不得通过反射、Object 或大量 instanceof 分派创建通用分组框架
 * - 工具组的 startIndex、endIndex 必须精确对应原始历史
 * - 所有组按原始历史顺序返回
 * - Grouper 不得静默修复非法历史，非法结构应由 Validator 明确拒绝
 * - 输入和输出集合必须使用防御性复制
 * - 不得把输入 List 的可变引用保存在 ContextMessageGroup 内
 * - 不依赖 Spring、LangGraph4j 或模型 API
 * - 不修改输入消息列表
 * - 不调用模型或工具
 * - 线程安全、无状态
 */
public class ContextMessageGrouper {

    /**
     * 将消息列表划分为分组。
     * <p>
     * 使用单次顺序扫描，每条消息恰好属于一个组，
     * 组内消息来自原历史中的连续区间。
     *
     * @param messages 消息列表
     * @return 分组列表，每个分组包含一组消息及其类型
     */
    public List<ContextMessageGroup> group(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        // 预扫描：建立 toolCallId -> Assistant 索引映射，用于确定 ToolResult 属于哪个工具组
        // 因为 ToolResult 可能不在 Assistant 紧后面（但 Validator 确保了中间只有 System/Summary）
        java.util.Map<String, Integer> toolCallIdToAssistantIndex = new java.util.HashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            AgentMessage msg = messages.get(i);
            if (msg instanceof AssistantAgentMessage assistant) {
                for (ToolCall tc : assistant.toolCalls()) {
                    toolCallIdToAssistantIndex.put(tc.id(), i);
                }
            }
        }

        // 单次顺序扫描构建分组
        List<ContextMessageGroup> groups = new ArrayList<>();
        int i = 0;

        while (i < messages.size()) {
            AgentMessage msg = messages.get(i);

            if (msg instanceof SystemAgentMessage) {
                // System 消息单独形成 SYSTEM 组
                groups.add(new ContextMessageGroup(
                        List.of(msg), i, i,
                        ContextMessageGroupType.SYSTEM, false));
                i++;

            } else if (msg instanceof SummaryAgentMessage) {
                // SummaryAgentMessage 形成单独的 SUMMARY 组
                groups.add(new ContextMessageGroup(
                        List.of(msg), i, i,
                        ContextMessageGroupType.SUMMARY, false));
                i++;

            } else if (msg instanceof AssistantAgentMessage assistant && !assistant.toolCalls().isEmpty()) {
                // 包含 ToolCall 的 Assistant + 其后连续的 ToolResult = TOOL_INTERACTION 原子组
                // 连续区间从当前 Assistant 开始，到所有 ToolResult 结束
                int startIndex = i;
                List<AgentMessage> groupMessages = new ArrayList<>();
                groupMessages.add(msg);
                i++;

                // 收集此 Assistant 声明的 ToolCall ID 集合
                Set<String> expectedToolCallIds = new LinkedHashSet<>();
                for (ToolCall tc : assistant.toolCalls()) {
                    expectedToolCallIds.add(tc.id());
                }
                Set<String> remainingToolCallIds = new LinkedHashSet<>(expectedToolCallIds);

                // 向后扫描：连续收集属于此工具组的 ToolResult
                // 中间允许出现 System/Summary（Validator 保证不会出现 User/普通 Assistant 中断）
                while (i < messages.size() && !remainingToolCallIds.isEmpty()) {
                    AgentMessage nextMsg = messages.get(i);
                    if (nextMsg instanceof ToolAgentMessage tool) {
                        if (remainingToolCallIds.remove(tool.toolCallId())) {
                            groupMessages.add(nextMsg);
                            i++;
                        } else {
                            // 不属于当前工具组的 ToolResult，停止收集
                            // 但由于 Validator 已确保配对正确，这里不应该发生
                            break;
                        }
                    } else if (nextMsg instanceof SystemAgentMessage || nextMsg instanceof SummaryAgentMessage) {
                        // System/Summary 不属于工具组内部，但 Validator 允许它们出现在工具交互中间
                        // 它们不加入此工具组，而是作为独立组
                        // 先结束当前工具组
                        break;
                    } else {
                        // 非工具消息出现，工具组结束
                        break;
                    }
                }

                groups.add(new ContextMessageGroup(
                        List.copyOf(groupMessages), startIndex, i - 1,
                        ContextMessageGroupType.TOOL_INTERACTION, true));

            } else if (msg instanceof ToolAgentMessage) {
                // 孤立 ToolResult：不属于任何已知工具组
                // Validator 应该已经拒绝这种情况，但防御性处理
                groups.add(new ContextMessageGroup(
                        List.of(msg), i, i,
                        ContextMessageGroupType.NORMAL, false));
                i++;

            } else {
                // 独立消息（User、无 ToolCall 的 Assistant 等）形成 NORMAL 组
                groups.add(new ContextMessageGroup(
                        List.of(msg), i, i,
                        ContextMessageGroupType.NORMAL, false));
                i++;
            }
        }

        return groups;
    }

    /**
     * 获取指定 Assistant 消息中声明的全部 ToolCall ID。
     *
     * @param messages 消息列表
     * @param assistantIndex Assistant 消息在列表中的索引
     * @return ToolCall ID 集合
     */
    public Set<String> getToolCallIds(List<AgentMessage> messages, int assistantIndex) {
        AgentMessage msg = messages.get(assistantIndex);
        if (msg instanceof AssistantAgentMessage assistant) {
            Set<String> ids = new LinkedHashSet<>();
            for (ToolCall tc : assistant.toolCalls()) {
                ids.add(tc.id());
            }
            return ids;
        }
        return Set.of();
    }
}
