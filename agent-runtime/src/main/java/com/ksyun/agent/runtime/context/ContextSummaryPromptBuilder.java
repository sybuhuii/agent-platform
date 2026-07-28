package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.context.TokenCounter;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.AssistantAgentMessage;
import com.ksyun.agent.core.message.ToolAgentMessage;
import com.ksyun.agent.core.message.UserAgentMessage;
import com.ksyun.agent.core.sanitizer.SensitiveValueSanitizer;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 摘要 Prompt 构建器，纯 Java 实现。
 * <p>
 * 依赖：
 * - 现有敏感值脱敏能力（SensitiveValueSanitizer）
 * - 不访问 SessionStore 或 MemoryStore
 * <p>
 * 约束：
 * - 不得把历史消息拼进 System 指令本身
 * - 历史消息作为单独 User 消息传入摘要模型
 * - 每条消息使用稳定角色标签
 * - ToolCall 只保存工具名和必要业务语义
 * - 不得包含 RunContext
 * - 不得包含 roles 和 permissions
 * - 不得包含 Spring Bean 信息
 * - 不得在日志打印完整摘要 Prompt
 * - 不得要求模型返回 JSON（本批默认纯文本输出）
 * - 线程安全、无状态
 */
public class ContextSummaryPromptBuilder {

    private static final String SUMMARY_SYSTEM_PROMPT = """
            You are a conversation summarizer. Your task is to compress conversation history \
            into a concise summary. Follow these rules strictly:

            1. Only summarize the provided history. Do not add any facts not present in the history.
            2. Preserve the user's goals and objectives.
            3. Preserve explicit user preferences stated in the conversation.
            4. Preserve decisions that have been made.
            5. Preserve important constraints mentioned.
            6. Preserve tasks that are not yet completed.
            7. Preserve key conclusions from tool executions.
            8. Preserve approval results (approved or rejected) from human reviews.
            9. Remove greetings, repetitive expressions, and irrelevant details.
            10. Do not output a detailed chain of thought.
            11. Do not output any passwords, tokens, API keys, or session IDs.
            12. Mark uncertain information as "unconfirmed".
            13. Output only the summary text itself.
            14. Do not request or invoke any tools.
            """;

    private static final String HISTORY_START_TAG = "<conversation_history>";
    private static final String HISTORY_END_TAG = "</conversation_history>";

    private final SensitiveValueSanitizer sanitizer;

    public ContextSummaryPromptBuilder(SensitiveValueSanitizer sanitizer) {
        this.sanitizer = Objects.requireNonNull(sanitizer);
    }

    /**
     * 获取摘要系统指令。
     * <p>
     * 不得把历史消息拼进此指令。
     *
     * @return 系统指令文本
     */
    public String getSystemPrompt() {
        return SUMMARY_SYSTEM_PROMPT;
    }

    /**
     * 将历史消息构建为 User 消息内容（包含分隔符）。
     * <p>
     * 历史消息作为不可信数据放在明确分隔符中。
     *
     * @param sourceMessages 源消息列表
     * @return 包含历史消息的 User 消息文本
     */
    public String buildHistoryUserContent(List<AgentMessage> sourceMessages) {
        Objects.requireNonNull(sourceMessages, "sourceMessages must not be null");

        StringBuilder sb = new StringBuilder();
        sb.append("Please summarize the following conversation history:\n\n");
        sb.append(HISTORY_START_TAG).append("\n");

        for (AgentMessage msg : sourceMessages) {
            sb.append(formatMessage(msg)).append("\n");
        }

        sb.append(HISTORY_END_TAG).append("\n");

        return sb.toString();
    }

    /**
     * 将旧摘要信息附加到历史消息内容中。
     *
     * @param historyContent  历史消息文本
     * @param existingSummary 旧摘要（如果存在）
     * @return 包含旧摘要的完整 User 消息文本
     */
    public String appendExistingSummary(String historyContent, String existingSummary) {
        if (existingSummary == null || existingSummary.isBlank()) {
            return historyContent;
        }
        return historyContent + "\nPrevious summary:\n<previous_summary>\n"
                + existingSummary + "\n</previous_summary>\n";
    }

    private String formatMessage(AgentMessage msg) {
        if (msg instanceof UserAgentMessage user) {
            return "[User]: " + truncateContent(user.content());
        } else if (msg instanceof AssistantAgentMessage assistant) {
            StringBuilder sb = new StringBuilder();
            sb.append("[Assistant]: ").append(truncateContent(assistant.content()));
            if (!assistant.toolCalls().isEmpty()) {
                sb.append(" [Called tools: ");
                for (var tc : assistant.toolCalls()) {
                    sb.append(tc.name()).append("(");
                    // ToolCall 只保存工具名，不暴露完整参数
                    sb.append("...");
                    sb.append(")");
                }
                sb.append("]");
            }
            return sb.toString();
        } else if (msg instanceof ToolAgentMessage tool) {
            // ToolResult 使用脱敏器处理内容
            String safeContent = sanitizeContent(tool.content());
            return "[ToolResult(" + tool.toolName() + "): "
                    + truncateContent(safeContent) + "]";
        } else if (msg instanceof com.ksyun.agent.core.message.SummaryAgentMessage summary) {
            return "[PreviousSummary]: " + truncateContent(summary.content());
        } else if (msg instanceof com.ksyun.agent.core.message.SystemAgentMessage system) {
            // 系统消息不应出现在源消息中，但安全处理
            return "[System]: " + truncateContent(system.content());
        } else {
            return "[Unknown]: (unsupported message type)";
        }
    }

    private String truncateContent(String content) {
        if (content == null) {
            return "<empty>";
        }
        // 截断过长内容，避免发送过多文本给摘要模型
        int maxLength = 500;
        if (content.length() > maxLength) {
            return content.substring(0, maxLength) + "...(truncated)";
        }
        return content;
    }

    /**
     * 对消息内容进行脱敏处理。
     * <p>
     * 使用注入的 SensitiveValueSanitizer 对可能包含敏感值的文本进行脱敏。
     * 由于 sanitizer 接口设计为处理 Map<String, Object> 参数，
     * 此处对纯文本内容做简单脱敏标记。
     */
    private String sanitizeContent(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        // 使用脱敏器对文本中的敏感值进行替换
        // 将文本放入 Map，通过 sanitizer 处理后提取脱敏结果
        Map<String, Object> contentMap = java.util.Map.of("content", content);
        Map<String, Object> sanitized = sanitizer.sanitize(contentMap);
        Object result = sanitized.get("content");
        return result instanceof String s ? s : content;
    }
}
