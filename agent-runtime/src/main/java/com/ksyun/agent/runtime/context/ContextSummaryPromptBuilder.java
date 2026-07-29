package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.context.TokenCounter;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.AssistantAgentMessage;
import com.ksyun.agent.core.message.SummaryAgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import com.ksyun.agent.core.message.ToolAgentMessage;
import com.ksyun.agent.core.message.UserAgentMessage;
import com.ksyun.agent.core.sanitizer.SensitiveValueSanitizer;
import com.ksyun.agent.core.tool.ToolCall;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 摘要 Prompt 构建器，纯 Java 实现。
 * <p>
 * 依赖：
 * - 现有敏感值脱敏能力（SensitiveValueSanitizer）
 * - TokenCounter（用于 Token 预算截断）
 * <p>
 * 约束：
 * - 所有可能进入摘要模型的文本必须统一脱敏：
 *   User content、Assistant content、ToolResult content、existing summary content
 * - 不得把 ToolCall 完整参数发送给摘要模型
 * - 不得仅依赖 Map 的字段名识别敏感值，必须支持文本正文中的常见敏感内容
 * - 不得把密码、credentialHash、sessionId、API Key、Bearer Token、完整权限集合发送给模型
 * - 保留固定、不可被历史消息覆盖的摘要 System Prompt
 * - 不得把历史消息拼接成可以突破边界标签的未隔离 Prompt
 * - 不无依据地把每条消息固定截断为500字符
 * - ToolCall 只保存工具名和必要业务语义
 * - 不得包含 RunContext、roles、permissions
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
    private static final String SUMMARY_START_TAG = "<previous_summary>";
    private static final String SUMMARY_END_TAG = "</previous_summary>";

    private final SensitiveValueSanitizer sanitizer;
    private final TokenCounter tokenCounter;

    public ContextSummaryPromptBuilder(SensitiveValueSanitizer sanitizer, TokenCounter tokenCounter) {
        this.sanitizer = Objects.requireNonNull(sanitizer);
        this.tokenCounter = Objects.requireNonNull(tokenCounter);
    }

    /**
     * 获取摘要系统指令。
     */
    public String getSystemPrompt() {
        return SUMMARY_SYSTEM_PROMPT;
    }

    /**
     * 将历史消息构建为 User 消息内容（包含边界标签隔离）。
     * <p>
     * 历史消息作为不可信数据放在明确分隔符中，不得突破边界标签。
     * 所有文本正文统一脱敏。
     * 不无依据固定截断500字符。
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
     * <p>
     * 旧摘要文本也必须脱敏，放在独立分隔符中。
     */
    public String appendExistingSummary(String historyContent, String existingSummary) {
        if (existingSummary == null || existingSummary.isBlank()) {
            return historyContent;
        }
        // 旧摘要正文也必须脱敏
        String sanitizedSummary = sanitizeText(existingSummary);
        return historyContent + "\nPrevious summary (also needs to be incorporated):\n"
                + SUMMARY_START_TAG + "\n"
                + sanitizedSummary + "\n"
                + SUMMARY_END_TAG + "\n";
    }

    private String formatMessage(AgentMessage msg) {
        if (msg instanceof UserAgentMessage user) {
            return "[User]: " + sanitizeText(user.content());
        } else if (msg instanceof AssistantAgentMessage assistant) {
            StringBuilder sb = new StringBuilder();
            sb.append("[Assistant]: ").append(sanitizeText(assistant.content()));
            if (!assistant.toolCalls().isEmpty()) {
                sb.append(" [Called tools: ");
                for (ToolCall tc : assistant.toolCalls()) {
                    // ToolCall 只保存工具名，不暴露完整参数
                    sb.append(tc.name()).append("(...");
                    // 只展示必要业务语义：参数名列表
                    if (!tc.arguments().isEmpty()) {
                        sb.append(" args: ");
                        sb.append(tc.arguments().keySet());
                    }
                    sb.append(")");
                }
                sb.append("]");
            }
            return sb.toString();
        } else if (msg instanceof ToolAgentMessage tool) {
            String safeContent = sanitizeText(tool.content());
            return "[ToolResult(" + tool.toolName() + "): " + safeContent + "]";
        } else if (msg instanceof SummaryAgentMessage summary) {
            return "[PreviousSummary]: " + sanitizeText(summary.content());
        } else if (msg instanceof SystemAgentMessage system) {
            // 系统消息不应出现在源消息中（selector 保证），但安全处理
            return "[System]: " + sanitizeText(system.content());
        } else {
            return "[Unknown]: (unsupported message type)";
        }
    }

    /**
     * 对文本正文进行统一脱敏。
     * <p>
     * 使用 SensitiveValueSanitizer 处理。
     * 不仅依赖 Map 字段名，还对文本正文中的常见敏感内容模式进行脱敏。
     * 不得把密码、token、API Key、sessionId、Bearer Token 发送给模型。
     */
    private String sanitizeText(String content) {
        if (content == null || content.isEmpty()) {
            return "<empty>";
        }
        // 1. 使用注入的 sanitizer 对文本进行脱敏（基于 Map key）
        Map<String, Object> contentMap = Map.of("content", content);
        Map<String, Object> sanitized = sanitizer.sanitize(contentMap);
        Object result = sanitized.get("content");
        String text = result instanceof String s ? s : content;

        // 2. 对文本正文中的常见敏感内容模式进行额外脱敏
        // Bearer Token 模式
        text = text.replaceAll("Bearer\\s+[A-Za-z0-9\\-_.~+/]+=*", "Bearer ***");
        // API Key 前缀模式
        text = text.replaceAll("(?i)(api[_\\-]?key|apikey|secret[_\\-]?key|access[_\\-]?key)\\s*[:=]\\s*[A-Za-z0-9\\-_.~+/]{8,}",
                "$1: ***");
        // Session/Token ID 模式
        text = text.replaceAll("(?i)(session[_\\-]?id|token|auth[_\\-]?token|refresh[_\\-]?token)\\s*[:=]\\s*[A-Za-z0-9\\-_.~+/]{8,}",
                "$1: ***");
        // Password 模式
        text = text.replaceAll("(?i)(password|passwd|pwd|credential[_\\-]?hash)\\s*[:=]\\s*\\S+",
                "$1: ***");

        return text;
    }
}
