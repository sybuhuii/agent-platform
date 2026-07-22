package com.ksyun.agent.infrastructure.springai;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.model.ModelClient;
import com.ksyun.agent.core.model.ModelRequest;
import com.ksyun.agent.core.model.ModelResponse;
import com.ksyun.agent.core.tool.ToolDefinition;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 Spring AI ChatModel 的 ModelClient 实现。
 * <p>
 * 职责：
 * 1. 接收框架 ModelRequest
 * 2. 转换消息
 * 3. 转换工具定义
 * 4. 应用模型参数
 * 5. 构造 Spring AI Prompt
 * 6. 明确关闭内部工具自动执行
 * 7. 调用 ChatModel
 * 8. 将结果转换为框架 ModelResponse
 * <p>
 * 禁止：
 * - 不得直接使用 ToolRegistry
 * - 不得执行 AgentTool
 * - 不得包含 ReAct 循环
 * - 不得硬编码模型供应商、model 名称、temperature 或 API Key
 * <p>
 * 本类保持普通 Java 类，不添加 @Component；由 infrastructure 配置类创建 Bean。
 */
public class SpringAiModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiModelClient.class);

    /** 允许的 options 键 */
    private static final String OPTION_MODEL = "model";
    private static final String OPTION_TEMPERATURE = "temperature";
    private static final String OPTION_MAX_TOKENS = "maxTokens";

    /** temperature 合理范围 */
    private static final double TEMPERATURE_MIN = 0.0;
    private static final double TEMPERATURE_MAX = 2.0;

    /** maxTokens 合理上限 */
    private static final int MAX_TOKENS_UPPER_LIMIT = 128_000;

    private final ChatModel chatModel;
    private final SpringAiMessageMapper messageMapper;
    private final SpringAiToolMapper toolMapper;
    private final SpringAiResponseMapper responseMapper;

    public SpringAiModelClient(ChatModel chatModel,
                               SpringAiMessageMapper messageMapper,
                               SpringAiToolMapper toolMapper,
                               SpringAiResponseMapper responseMapper) {
        if (chatModel == null) {
            throw new IllegalArgumentException("ChatModel must not be null");
        }
        if (messageMapper == null) {
            throw new IllegalArgumentException("SpringAiMessageMapper must not be null");
        }
        if (toolMapper == null) {
            throw new IllegalArgumentException("SpringAiToolMapper must not be null");
        }
        if (responseMapper == null) {
            throw new IllegalArgumentException("SpringAiResponseMapper must not be null");
        }
        this.chatModel = chatModel;
        this.messageMapper = messageMapper;
        this.toolMapper = toolMapper;
        this.responseMapper = responseMapper;
    }

    @Override
    public ModelResponse generate(ModelRequest request) {
        if (request == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT, "ModelRequest must not be null"
            );
        }

        try {
            // 1. 转换消息
            List<Message> messages = convertMessages(request);

            // 2. 转换工具定义
            List<ToolCallback> toolCallbacks = toolMapper.map(request.tools());

            // 3. 构造 ChatOptions（含安全参数校验和内部工具执行关闭）
            ChatOptions chatOptions = buildChatOptions(request.options(), toolCallbacks);

            // 4. 构造 Prompt
            Prompt prompt = new Prompt(messages, chatOptions);

            // 5. 调用 ChatModel（一次调用，不循环）
            ChatResponse chatResponse = chatModel.call(prompt);

            // 6. 转换响应
            return responseMapper.map(chatResponse);

        } catch (AgentFrameworkException e) {
            throw e;
        } catch (Exception e) {
            log.error("Model invocation failed: {}", e.getMessage());
            throw new AgentFrameworkException(
                    AgentErrorCode.MODEL_INVOCATION_FAILED,
                    "Model invocation failed due to an internal error"
            );
        }
    }

    private List<Message> convertMessages(ModelRequest request) {
        if (request.messages() == null || request.messages().isEmpty()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT, "Messages must not be empty"
            );
        }
        List<Message> messages = new ArrayList<>();
        for (var agentMessage : request.messages()) {
            messages.add(messageMapper.map(agentMessage));
        }
        return messages;
    }

    /**
     * 构建 ChatOptions，包含：
     * - model/temperature/maxTokens 安全参数校验
     * - toolCallbacks（如果有）
     * - internalToolExecutionEnabled = false（最重要的架构要求）
     */
    private ChatOptions buildChatOptions(Map<String, Object> options,
                                          List<ToolCallback> toolCallbacks) {
        // 使用 OpenAiChatOptions.Builder 以支持 OpenAI 兼容接口的所有参数
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder();

        // 明确关闭内部工具自动执行 —— 这是本批最重要的架构要求
        optionsBuilder.internalToolExecutionEnabled(false);

        // 添加工具回调（仅 Schema 声明，SafeToolCallback 禁止执行）
        if (!toolCallbacks.isEmpty()) {
            optionsBuilder.toolCallbacks(toolCallbacks);
        }

        if (options != null && !options.isEmpty()) {
            applySafeOptions(optionsBuilder, options);
        }

        return optionsBuilder.build();
    }

    /**
     * 应用安全的模型选项。
     * <p>
     * 只允许 model、temperature、maxTokens。
     * 类型错误返回 INVALID_ARGUMENT。
     * 忽略未知选项并记录日志。
     * 不得允许客户端通过 options 传入 apiKey、baseUrl、proxy、任意 Java 类名、
     * ToolCallback 或内部工具执行开关。
     */
    private void applySafeOptions(OpenAiChatOptions.Builder builder, Map<String, Object> options) {
        for (Map.Entry<String, Object> entry : options.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value == null) {
                continue;
            }

            switch (key) {
                case OPTION_MODEL -> {
                    if (value instanceof String modelValue && !modelValue.isBlank()) {
                        builder.model(modelValue);
                    } else {
                        throw new AgentFrameworkException(
                                AgentErrorCode.INVALID_ARGUMENT,
                                "Option 'model' must be a non-blank string"
                        );
                    }
                }
                case OPTION_TEMPERATURE -> {
                    double temperature = validateTemperature(value);
                    builder.temperature(temperature);
                }
                case OPTION_MAX_TOKENS -> {
                    int maxTokens = validateMaxTokens(value);
                    builder.maxTokens(maxTokens);
                }
                default -> {
                    // 忽略或拒绝未知选项，行为明确且有日志
                    log.info("Ignoring unknown model option: '{}' (not supported for security reasons)", key);
                }
            }
        }
    }

    private double validateTemperature(Object value) {
        double temperature;
        if (value instanceof Number num) {
            temperature = num.doubleValue();
        } else if (value instanceof String str) {
            try {
                temperature = Double.parseDouble(str);
            } catch (NumberFormatException e) {
                throw new AgentFrameworkException(
                        AgentErrorCode.INVALID_ARGUMENT,
                        "Option 'temperature' must be a number"
                );
            }
        } else {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Option 'temperature' must be a number"
            );
        }

        if (temperature < TEMPERATURE_MIN || temperature > TEMPERATURE_MAX) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Option 'temperature' must be between " + TEMPERATURE_MIN + " and " + TEMPERATURE_MAX
            );
        }
        return temperature;
    }

    private int validateMaxTokens(Object value) {
        int maxTokens;
        if (value instanceof Number num) {
            maxTokens = num.intValue();
        } else if (value instanceof String str) {
            try {
                maxTokens = Integer.parseInt(str);
            } catch (NumberFormatException e) {
                throw new AgentFrameworkException(
                        AgentErrorCode.INVALID_ARGUMENT,
                        "Option 'maxTokens' must be a positive integer"
                );
            }
        } else {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Option 'maxTokens' must be a positive integer"
            );
        }

        if (maxTokens <= 0) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Option 'maxTokens' must be a positive integer"
            );
        }
        if (maxTokens > MAX_TOKENS_UPPER_LIMIT) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Option 'maxTokens' exceeds the upper limit of " + MAX_TOKENS_UPPER_LIMIT
            );
        }
        return maxTokens;
    }
}
