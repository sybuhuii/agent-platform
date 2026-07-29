package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.context.TokenCounter;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.AssistantAgentMessage;
import com.ksyun.agent.core.message.SummaryAgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import com.ksyun.agent.core.message.ToolAgentMessage;
import com.ksyun.agent.core.message.UserAgentMessage;
import com.ksyun.agent.core.tool.ToolCall;

import java.util.Collection;

/**
 * 启发式 Token 计数器默认实现。
 * <p>
 * 估算规则：
 * - 英文约 4 字符 = 1 Token
 * - 中文约 1 字符 = 1 Token（CJK 字符接近每字符 1 token）
 * - 混合文本按字符比例加权
 * - 每条消息额外加 4 Token 开销（角色标记、格式分隔符）
 * - 每个 ToolCall 额外加 10 Token（函数名 + 参数结构）
 * - ToolAgentMessage 的 toolCallId 加 3 Token
 * - ToolAgentMessage 错误标记加 3 Token
 * - 不依赖 tiktoken、huggingface-tokenizers 或其他第三方库
 * - 不缓存结果
 * - 不调用模型
 * - 线程安全、无状态
 * <p>
 * 本实现是模型无关估算器，不宣称精确。不得用于精确计费。仅用于上下文裁剪判断。
 */
public class HeuristicTokenCounter implements TokenCounter {

    /** 英文每 Token 字符数 */
    private static final double EN_CHARS_PER_TOKEN = 4.0;
    /** CJK 每 Token 字符数 */
    private static final double CJK_CHARS_PER_TOKEN = 1.0;
    /** 每条消息固定开销 */
    private static final int MESSAGE_OVERHEAD = 4;
    /** 每个 ToolCall 额外开销 */
    private static final int TOOL_CALL_OVERHEAD = 10;
    /** ToolCall ID 开销 */
    private static final int TOOL_CALL_ID_OVERHEAD = 3;
    /** 错误标记额外开销 */
    private static final int ERROR_OVERHEAD = 3;

    @Override
    public int count(Collection<? extends AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (AgentMessage msg : messages) {
            total += count(msg);
        }
        return total;
    }

    @Override
    public int count(AgentMessage message) {
        if (message == null) {
            return 0;
        }

        int tokens = MESSAGE_OVERHEAD;

        if (message instanceof SystemAgentMessage sys) {
            tokens += estimateTextTokens(sys.content());
        } else if (message instanceof UserAgentMessage user) {
            tokens += estimateTextTokens(user.content());
        } else if (message instanceof AssistantAgentMessage assistant) {
            tokens += estimateTextTokens(assistant.content());
            if (assistant.toolCalls() != null && !assistant.toolCalls().isEmpty()) {
                tokens += assistant.toolCalls().size() * TOOL_CALL_OVERHEAD;
                for (ToolCall tc : assistant.toolCalls()) {
                    tokens += estimateTextTokens(tc.id());
                    tokens += estimateTextTokens(tc.name());
                    tokens += estimateMapTokens(tc.arguments());
                }
            }
        } else if (message instanceof ToolAgentMessage tool) {
            tokens += estimateTextTokens(tool.toolCallId());
            tokens += TOOL_CALL_ID_OVERHEAD;
            tokens += estimateTextTokens(tool.toolName());
            tokens += estimateTextTokens(tool.content());
            if (tool.error()) {
                tokens += ERROR_OVERHEAD;
            }
        } else if (message instanceof SummaryAgentMessage summary) {
            // SummaryAgentMessage 使用与其他文本消息相同的文本 Token 估算方式
            // 不得使用反射、Object 分派或把 Summary 强制转换为 System
            tokens += estimateTextTokens(summary.content());
        }

        return tokens;
    }

    /**
     * 估算文本的 Token 数。
     * <p>
     * 区分中英文字符，加权计算：
     * - CJK 字符约 1 字符 = 1 Token
     * - 其他字符约 4 字符 = 1 Token
     */
    private int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int asciiCount = 0;
        int cjkCount = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCJK(c)) {
                cjkCount++;
            } else {
                asciiCount++;
            }
        }

        double tokens = (asciiCount / EN_CHARS_PER_TOKEN) + (cjkCount / CJK_CHARS_PER_TOKEN);
        return Math.max(1, (int) Math.ceil(tokens));
    }

    /**
     * 估算 Map 参数的 Token 数。
     */
    private int estimateMapTokens(java.util.Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        for (java.util.Map.Entry<String, Object> entry : map.entrySet()) {
            tokens += estimateTextTokens(entry.getKey());
            tokens += estimateTextTokens(String.valueOf(entry.getValue()));
        }
        return tokens;
    }

    /**
     * 判断字符是否为 CJK（中日韩）字符。
     */
    private boolean isCJK(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }
}
