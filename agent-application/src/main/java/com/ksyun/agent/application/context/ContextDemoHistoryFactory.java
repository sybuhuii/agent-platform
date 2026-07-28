package com.ksyun.agent.application.context;

import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.AssistantAgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import com.ksyun.agent.core.message.ToolAgentMessage;
import com.ksyun.agent.core.message.UserAgentMessage;
import com.ksyun.agent.core.tool.ToolCall;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 根据 ContextDemoCommand 生成安全的多轮演示历史，纯 Java 实现。
 * <p>
 * 约束：
 * - 只生成 Synthetic 演示内容
 * - 不得读取真实用户历史
 * - 不得读取 MemoryStore
 * - 不得包含密码、Token 和 Session
 * - 不得生成非法工具配对
 * - includeToolInteractions=true 时：Assistant 消息包含 ToolCall，
 *   后续必须包含对应 ToolAgentMessage，ToolCall ID 唯一，不调用真实工具
 * - 工具名使用固定安全演示名称 context_demo_lookup
 * - 工具参数只包含无敏感合成数据
 * - 消息长度接近 charactersPerMessage
 * - 不得构造超过合理内存限制的历史
 * - 相同输入可以生成确定性历史
 * - 不得使用随机大字符串
 * - 生成结果为不可变列表
 */
public class ContextDemoHistoryFactory {

    private static final String DEMO_SYSTEM_PROMPT = "你是一个上下文管理演示助手。你的任务是展示框架如何在长对话中管理上下文窗口。";
    private static final String DEMO_TOOL_NAME = "context_demo_lookup";

    /**
     * 根据命令生成演示历史。
     *
     * @param command 演示命令
     * @return 不可变消息列表
     */
    public List<AgentMessage> generate(ContextDemoCommand command) {
        List<AgentMessage> messages = new ArrayList<>();
        AtomicInteger toolCallIdCounter = new AtomicInteger(1);

        // 1. 固定安全 System 消息
        messages.add(new SystemAgentMessage(DEMO_SYSTEM_PROMPT));

        // 2. 指定轮数的 User/Assistant 消息
        for (int i = 1; i <= command.rounds(); i++) {
            // User 消息
            String userContent = buildUserMessage(i, command.rounds(), command.charactersPerMessage());
            messages.add(new UserAgentMessage(userContent));

            // Assistant 消息（可能包含 ToolCall）
            if (command.includeToolInteractions() && shouldIncludeToolCall(i, command.rounds())) {
                String toolCallId = "demo-tc-" + toolCallIdCounter.getAndIncrement();
                String queryParam = "round_" + i + "_data";
                ToolCall toolCall = new ToolCall(toolCallId, DEMO_TOOL_NAME,
                        Map.of("query", queryParam));

                String assistantContent = buildAssistantMessage(i, command.charactersPerMessage());
                messages.add(new AssistantAgentMessage(assistantContent, List.of(toolCall)));

                // 对应的 ToolResult
                String toolContent = "查找到第" + i + "轮演示数据：这是一条合成的工具查询结果，长度约"
                        + (command.charactersPerMessage() / 2) + "字符。数据标识为" + queryParam + "。";
                messages.add(new ToolAgentMessage(toolCallId, DEMO_TOOL_NAME, toolContent, false));
            } else {
                String assistantContent = buildAssistantMessage(i, command.charactersPerMessage());
                messages.add(new AssistantAgentMessage(assistantContent, List.of()));
            }
        }

        // 3. 最后一条真实 User 消息使用 finalQuestion
        messages.add(new UserAgentMessage(command.finalQuestion()));

        return List.copyOf(messages);
    }

    private boolean shouldIncludeToolCall(int round, int totalRounds) {
        // 每隔3轮包含一次工具交互
        return round % 3 == 0;
    }

    private String buildUserMessage(int round, int totalRounds, int targetLength) {
        StringBuilder sb = new StringBuilder();
        sb.append("这是第").append(round).append("轮对话（共").append(totalRounds).append("轮）。");
        sb.append("我需要了解关于演示主题的一些信息。");

        // 填充到接近目标长度
        while (sb.length() < targetLength - 20) {
            sb.append("这是为了增加消息长度而添加的填充文本，模拟真实用户消息。");
        }

        return sb.toString();
    }

    private String buildAssistantMessage(int round, int targetLength) {
        StringBuilder sb = new StringBuilder();
        sb.append("第").append(round).append("轮回复：根据您的描述，我来帮您分析相关信息。");

        // 填充到接近目标长度
        while (sb.length() < targetLength - 20) {
            sb.append("这是助手回复中的补充内容，用于模拟较长的模型输出。");
        }

        return sb.toString();
    }
}
