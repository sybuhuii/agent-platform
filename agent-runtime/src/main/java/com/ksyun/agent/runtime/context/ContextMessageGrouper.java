package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.AssistantAgentMessage;
import com.ksyun.agent.core.message.SummaryAgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import com.ksyun.agent.core.message.ToolAgentMessage;
import com.ksyun.agent.core.tool.ToolCall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上下文消息分组器，纯 Java 实现。
 * <p>
 * 职责：
 * - 将消息列表按 System、Normal、ToolInteraction 划分为分组
 * - System 消息单独形成 SYSTEM 组
 * - 没有 ToolCall 的 User 或 Assistant 消息形成 NORMAL 组
 * - 包含 ToolCall 的 Assistant 消息及其全部 ToolAgentMessage 形成 TOOL_INTERACTION 原子组
 * - 配对基于 ToolCall ID 完全匹配
 * <p>
 * 约束：
 * - 不依赖 Spring、LangGraph4j 或模型 API
 * - 不修改输入消息列表
 * - 不调用模型或工具
 * - 线程安全、无状态
 */
public class ContextMessageGrouper {

    /**
     * 将消息列表划分为分组。
     *
     * @param messages 消息列表
     * @return 分组列表，每个分组包含一组消息及其类型
     */
    public List<ContextMessageGroup> group(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        // 1. 建立 toolCallId -> AssistantAgentMessage 索引的映射
        Map<String, Integer> toolCallIdToAssistantIndex = new HashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            AgentMessage msg = messages.get(i);
            if (msg instanceof AssistantAgentMessage assistant) {
                for (ToolCall tc : assistant.toolCalls()) {
                    toolCallIdToAssistantIndex.put(tc.id(), i);
                }
            }
        }

        // 2. 建立 assistantIndex -> 其关联的 ToolAgentMessage 索引列表
        Map<Integer, List<Integer>> assistantToToolMessages = new HashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            AgentMessage msg = messages.get(i);
            if (msg instanceof ToolAgentMessage tool) {
                Integer assistantIndex = toolCallIdToAssistantIndex.get(tool.toolCallId());
                if (assistantIndex != null) {
                    assistantToToolMessages.computeIfAbsent(assistantIndex, k -> new ArrayList<>()).add(i);
                }
            }
        }

        // 3. 构建分组
        List<ContextMessageGroup> groups = new ArrayList<>();
        boolean[] assigned = new boolean[messages.size()];

        for (int i = 0; i < messages.size(); i++) {
            if (assigned[i]) {
                continue;
            }

            AgentMessage msg = messages.get(i);

            if (msg instanceof SystemAgentMessage) {
                // System 消息单独形成 SYSTEM 组
                assigned[i] = true;
                groups.add(new ContextMessageGroup(
                        List.of(msg), i, i,
                        ContextMessageGroupType.SYSTEM, false));
            } else if (msg instanceof SummaryAgentMessage) {
                // SummaryAgentMessage 形成单独的 SUMMARY 原子组
                assigned[i] = true;
                groups.add(new ContextMessageGroup(
                        List.of(msg), i, i,
                        ContextMessageGroupType.SUMMARY, false));
            } else if (msg instanceof AssistantAgentMessage assistant && !assistant.toolCalls().isEmpty()) {
                // 包含 ToolCall 的 AssistantAgentMessage + 其所有 ToolAgentMessage = TOOL_INTERACTION 组
                List<Integer> indices = new ArrayList<>();
                indices.add(i);
                assigned[i] = true;

                List<Integer> toolMsgIndices = assistantToToolMessages.get(i);
                if (toolMsgIndices != null) {
                    for (int toolIdx : toolMsgIndices) {
                        indices.add(toolIdx);
                        assigned[toolIdx] = true;
                    }
                }

                // 收集组内消息，保持原始顺序
                indices.sort(Integer::compareTo);
                List<AgentMessage> groupMessages = new ArrayList<>();
                for (int idx : indices) {
                    groupMessages.add(messages.get(idx));
                }

                groups.add(new ContextMessageGroup(
                        groupMessages, indices.get(0), indices.get(indices.size() - 1),
                        ContextMessageGroupType.TOOL_INTERACTION, true));
            } else {
                // 独立消息（User、无 ToolCall 的 Assistant 等）
                assigned[i] = true;
                groups.add(new ContextMessageGroup(
                        List.of(msg), i, i,
                        ContextMessageGroupType.NORMAL, false));
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
