package com.ksyun.agent.infrastructure.springai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AssistantAgentMessage;
import com.ksyun.agent.core.model.ModelResponse;
import com.ksyun.agent.core.model.TokenUsage;
import com.ksyun.agent.core.tool.ToolCall;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 将 Spring AI ChatResponse 转换为框架 ModelResponse。
 * <p>
 * 无状态、线程安全。
 * 不把底层 Spring AI 对象直接放进 metadata。
 * 不把完整响应、Prompt 或密钥写入 metadata。
 */
public class SpringAiResponseMapper {

    private static final Logger log = LoggerFactory.getLogger(SpringAiResponseMapper.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Map<String, Object> EMPTY_METADATA = Map.of();

    /**
     * 将 Spring AI ChatResponse 转换为框架 ModelResponse。
     */
    public ModelResponse map(ChatResponse chatResponse) {
        if (chatResponse == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MODEL_INVOCATION_FAILED,
                    "ChatResponse is null"
            );
        }

        Generation generation = chatResponse.getResult();
        if (generation == null || generation.getOutput() == null) {
            // 既无文本也无 ToolCall，返回安全的空 Assistant 消息
            return new ModelResponse(
                    new AssistantAgentMessage("", List.of()),
                    buildSafeTokenUsage(null),
                    buildMetadata(chatResponse, false)
            );
        }

        AssistantMessage assistantMessage = generation.getOutput();
        String content = assistantMessage.getText() != null ? assistantMessage.getText() : "";

        // 提取全部 ToolCall，不只取第一个
        List<ToolCall> toolCalls = mapToolCalls(assistantMessage);

        // 没有文本但存在 ToolCall 属于合法响应
        AssistantAgentMessage frameworkMessage = new AssistantAgentMessage(content, toolCalls);

        TokenUsage tokenUsage = buildSafeTokenUsage(chatResponse.getMetadata() != null
                ? chatResponse.getMetadata().getUsage() : null);

        Map<String, Object> metadata = buildMetadata(chatResponse, true);

        return new ModelResponse(frameworkMessage, tokenUsage, metadata);
    }

    private List<ToolCall> mapToolCalls(AssistantMessage assistantMessage) {
        List<AssistantMessage.ToolCall> springAiToolCalls = assistantMessage.getToolCalls();
        if (springAiToolCalls == null || springAiToolCalls.isEmpty()) {
            return List.of();
        }

        return springAiToolCalls.stream()
                .map(this::mapToolCall)
                .toList();
    }

    private ToolCall mapToolCall(AssistantMessage.ToolCall springAiToolCall) {
        String id = springAiToolCall.id();
        // 若供应商未返回 ID，生成当前响应内唯一且稳定的替代 ID
        if (id == null || id.isBlank()) {
            id = "tc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        Map<String, Object> arguments = parseArguments(springAiToolCall.arguments());
        return new ToolCall(id, springAiToolCall.name(), arguments);
    }

    /**
     * 将 ToolCall arguments 解析成 Map<String, Object>，
     * 不能把 JSON 参数字符串原样塞进错误类型字段。
     */
    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank() || "{}".equals(argumentsJson.trim())) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(
                    argumentsJson, new TypeReference<Map<String, Object>>() {}
            );
            return parsed != null ? Collections.unmodifiableMap(parsed) : Map.of();
        } catch (Exception e) {
            // 供应商返回无法解析的 ToolCall 参数
            log.warn("Failed to parse tool call arguments, marking as parse failure: {}", e.getMessage());
            throw new AgentFrameworkException(
                    AgentErrorCode.MODEL_INVOCATION_FAILED,
                    "Failed to parse tool call arguments: " + e.getMessage()
            );
        }
    }

    /**
     * 构建 TokenUsage。供应商未返回 Token 用量时使用安全默认值。
     */
    private TokenUsage buildSafeTokenUsage(Usage usage) {
        if (usage == null) {
            return new TokenUsage(0, 0, 0);
        }

        long inputTokens = safeLong(usage.getPromptTokens());
        long outputTokens = safeLong(usage.getCompletionTokens());
        long totalTokens = safeLong(usage.getTotalTokens());

        return new TokenUsage(inputTokens, outputTokens, totalTokens);
    }

    private long safeLong(Integer value) {
        return value != null ? value : 0;
    }

    /**
     * 构建 metadata：只保存非敏感信息，使用不可变 Map。
     */
    private Map<String, Object> buildMetadata(ChatResponse chatResponse, boolean hasContent) {
        Map<String, Object> meta = new HashMap<>();

        if (chatResponse.getMetadata() != null) {
            // model
            if (chatResponse.getMetadata().getModel() != null) {
                meta.put("model", chatResponse.getMetadata().getModel());
            }

            // responseId
            if (chatResponse.getMetadata().getId() != null) {
                meta.put("responseId", chatResponse.getMetadata().getId());
            }

            // usageUnavailable 标记
            if (chatResponse.getMetadata().getUsage() == null) {
                meta.put("usageUnavailable", true);
            }
        }

        // finishReason
        Generation generation = chatResponse.getResult();
        if (generation != null && generation.getMetadata() != null
                && generation.getMetadata().getFinishReason() != null) {
            meta.put("finishReason", generation.getMetadata().getFinishReason());
        }

        return Collections.unmodifiableMap(meta);
    }
}
