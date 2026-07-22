package com.ksyun.agent.infrastructure.springai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.AssistantAgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import com.ksyun.agent.core.message.ToolAgentMessage;
import com.ksyun.agent.core.message.UserAgentMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

/**
 * 将框架 AgentMessage 转换为 Spring AI Message。
 * <p>
 * 无状态、线程安全。不把 RunContext 转换为 LLM 消息。
 */
public class SpringAiMessageMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 将框架消息转换为 Spring AI 消息。
     */
    public Message map(AgentMessage message) {
        if (message instanceof SystemAgentMessage sam) {
            return new SystemMessage(sam.content());
        } else if (message instanceof UserAgentMessage uam) {
            return new UserMessage(uam.content());
        } else if (message instanceof AssistantAgentMessage aam) {
            return mapAssistantMessage(aam);
        } else if (message instanceof ToolAgentMessage tam) {
            return mapToolMessage(tam);
        }
        throw new AgentFrameworkException(
                AgentErrorCode.INVALID_ARGUMENT,
                "Unsupported message type: " + message.getClass().getName()
        );
    }

    private AssistantMessage mapAssistantMessage(AssistantAgentMessage aam) {
        String content = aam.content() != null ? aam.content() : "";

        if (aam.toolCalls() == null || aam.toolCalls().isEmpty()) {
            return new AssistantMessage(content);
        }

        List<AssistantMessage.ToolCall> toolCalls = aam.toolCalls().stream()
                .map(tc -> new AssistantMessage.ToolCall(
                        tc.id(),
                        "function",
                        tc.name(),
                        serializeArguments(tc.arguments())
                ))
                .toList();

        return AssistantMessage.builder()
                .content(content)
                .toolCalls(toolCalls)
                .build();
    }

    private ToolResponseMessage mapToolMessage(ToolAgentMessage tam) {
        String responseContent = tam.content() != null ? tam.content() : "";
        if (tam.error()) {
            responseContent = "Error: " + responseContent;
        }

        ToolResponseMessage.ToolResponse response = new ToolResponseMessage.ToolResponse(
                tam.toolCallId(),
                tam.toolName(),
                responseContent
        );

        return ToolResponseMessage.builder()
                .responses(List.of(response))
                .build();
    }

    private String serializeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(arguments);
        } catch (JsonProcessingException e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MODEL_INVOCATION_FAILED,
                    "Failed to serialize tool call arguments"
            );
        }
    }
}
