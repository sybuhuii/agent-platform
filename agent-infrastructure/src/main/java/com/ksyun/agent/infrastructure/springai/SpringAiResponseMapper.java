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

    /**
     * 将 Spring AI ChatResponse 转换为框架 ModelResponse。
     */
    public ModelResponse map(ChatResponse chatResponse) {
        if (chatResponse == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MODEL_INVOCATION_FAILED,
                    "ChatResponse is null");
        }

        Generation generation = chatResponse.getResult();

        if (generation == null
                || generation.getOutput() == null) {
            return new ModelResponse(
                    new AssistantAgentMessage("", List.of()),
                    buildSafeTokenUsage(null),
                    buildMetadata(chatResponse, false));
        }

        AssistantMessage assistantMessage =
                generation.getOutput();

        String content =
                assistantMessage.getText() != null
                        ? assistantMessage.getText()
                        : "";

        ToolCallMapping toolCallMapping =
                mapToolCalls(assistantMessage);

        AssistantAgentMessage frameworkMessage =
                new AssistantAgentMessage(
                        content,
                        toolCallMapping.toolCalls());

        Usage usage =
                chatResponse.getMetadata() != null
                        ? chatResponse.getMetadata().getUsage()
                        : null;

        TokenUsage tokenUsage =
                buildSafeTokenUsage(usage);

        Map<String, Object> metadata =
                buildMetadata(
                        chatResponse,
                        toolCallMapping.fallbackIdGenerated());

        return new ModelResponse(
                frameworkMessage,
                tokenUsage,
                metadata);
    }
    private ToolCallMapping mapToolCalls(
            AssistantMessage assistantMessage
    ) {
        List<AssistantMessage.ToolCall> springAiToolCalls =
                assistantMessage.getToolCalls();

        if (springAiToolCalls == null
                || springAiToolCalls.isEmpty()) {
            return new ToolCallMapping(List.of(), false);
        }

        List<ToolCall> toolCalls =
                new java.util.ArrayList<>();

        boolean fallbackIdGenerated = false;

        for (int index = 0;
             index < springAiToolCalls.size();
             index++) {

            AssistantMessage.ToolCall source =
                    springAiToolCalls.get(index);

            MappedToolCall mapped =
                    mapToolCall(source, index);

            toolCalls.add(mapped.toolCall());

            if (mapped.fallbackIdGenerated()) {
                fallbackIdGenerated = true;
            }
        }

        return new ToolCallMapping(
                List.copyOf(toolCalls),
                fallbackIdGenerated);
    }

    private MappedToolCall mapToolCall(
            AssistantMessage.ToolCall springAiToolCall,
            int index
    ) {
        String id = springAiToolCall.id();
        boolean fallbackIdGenerated =
                id == null || id.isBlank();

        if (fallbackIdGenerated) {
            /*
             * 使用调用名称、参数和序号生成确定性 ID。
             * 同一个响应被重复映射时仍会得到相同 ID。
             */
            String seed =
                    String.valueOf(springAiToolCall.name())
                            + "\u0000"
                            + String.valueOf(
                            springAiToolCall.arguments())
                            + "\u0000"
                            + index;

            id = "tc_fallback_"
                    + UUID.nameUUIDFromBytes(
                            seed.getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8))
                    .toString()
                    .replace("-", "")
                    .substring(0, 16);
        }

        Map<String, Object> arguments =
                parseArguments(
                        springAiToolCall.arguments());

        return new MappedToolCall(
                new ToolCall(
                        id,
                        springAiToolCall.name(),
                        arguments),
                fallbackIdGenerated);
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
            /*
             * 不记录原始参数和 Jackson 错误正文，
             * 因为错误正文可能包含模型返回的参数片段。
             */
            log.warn(
                    "Failed to parse tool call arguments: errorType={}",
                    e.getClass().getSimpleName());

            throw new AgentFrameworkException(
                    AgentErrorCode.MODEL_INVOCATION_FAILED,
                    "Failed to parse tool call arguments",
                    e);
        }
    }

    /**
     * 构建 TokenUsage。供应商未返回 Token 用量时使用安全默认值。
     */
    private Map<String, Object> buildMetadata(
            ChatResponse chatResponse,
            boolean fallbackToolCallIdGenerated
    ) {
        Map<String, Object> metadata =
                new HashMap<>();

        if (chatResponse.getMetadata() != null) {
            if (chatResponse.getMetadata().getModel()
                    != null) {
                metadata.put(
                        "model",
                        chatResponse.getMetadata().getModel());
            }

            if (chatResponse.getMetadata().getId()
                    != null) {
                metadata.put(
                        "responseId",
                        chatResponse.getMetadata().getId());
            }
        }

        /*
         * metadata 整体为空，或者其中没有 usage，
         * 都必须标记 usageUnavailable。
         */
        if (chatResponse.getMetadata() == null
                || chatResponse.getMetadata().getUsage()
                == null) {
            metadata.put("usageUnavailable", true);
        }

        if (fallbackToolCallIdGenerated) {
            metadata.put(
                    "fallbackToolCallIdGenerated",
                    true);
        }

        Generation generation =
                chatResponse.getResult();

        if (generation != null
                && generation.getMetadata() != null
                && generation.getMetadata()
                .getFinishReason() != null) {
            metadata.put(
                    "finishReason",
                    generation.getMetadata()
                            .getFinishReason());
        }

        return Collections.unmodifiableMap(
                new HashMap<>(metadata));
    }

    /**
     * 构建 TokenUsage。
     * 供应商未返回用量时使用安全的 0 默认值，
     * 并由 metadata 中的 usageUnavailable 说明其不可用。
     */
    private TokenUsage buildSafeTokenUsage(Usage usage) {
        if (usage == null) {
            return new TokenUsage(0, 0, 0);
        }

        long inputTokens =
                safeLong(usage.getPromptTokens());

        long outputTokens =
                safeLong(usage.getCompletionTokens());

        long totalTokens =
                safeLong(usage.getTotalTokens());

        return new TokenUsage(
                inputTokens,
                outputTokens,
                totalTokens);
    }

    private long safeLong(Integer value) {
        return value != null ? value : 0;
    }

    private record MappedToolCall(
            ToolCall toolCall,
            boolean fallbackIdGenerated
    ) {
    }

    private record ToolCallMapping(
            List<ToolCall> toolCalls,
            boolean fallbackIdGenerated
    ) {
    }
}
