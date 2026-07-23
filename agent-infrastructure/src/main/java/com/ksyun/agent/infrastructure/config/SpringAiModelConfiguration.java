package com.ksyun.agent.infrastructure.config;

import com.ksyun.agent.application.model.ModelDevApplicationService;
import com.ksyun.agent.core.model.ModelClient;
import com.ksyun.agent.runtime.model.DefaultModelInvocationGateway;
import com.ksyun.agent.runtime.model.ModelInvocationGateway;
import com.ksyun.agent.runtime.registry.ToolRegistry;
import com.ksyun.agent.runtime.run.RunIdGenerator;
import com.ksyun.agent.infrastructure.config.AgentFrameworkAutoConfiguration;
import com.ksyun.agent.infrastructure.springai.SpringAiMessageMapper;
import com.ksyun.agent.infrastructure.springai.SpringAiModelClient;
import com.ksyun.agent.infrastructure.springai.SpringAiResponseMapper;
import com.ksyun.agent.infrastructure.springai.SpringAiToolMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Spring AI 模型相关 Bean 装配。
 * <p>
 * 负责：
 * - 创建 SpringAiMessageMapper、SpringAiToolMapper、SpringAiResponseMapper
 * - 创建 SpringAiModelClient
 * - 创建 DefaultModelInvocationGateway
 * - 创建 ModelDevApplicationService
 * <p>
 * 条件：
 * - 所有模型相关 Bean 仅在 ChatModel 存在且 agent.model.enabled=true 时创建
 * - ChatModel 不存在时，应用仍应能够启动
 * - 不启用 Spring AI 自动工具执行
 * - 缺少模型配置时，开发调用接口返回明确的"模型未配置/不可用"
 */
@AutoConfiguration(after = AgentFrameworkAutoConfiguration.class,
        afterName = "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration")
@ConditionalOnBean(ChatModel.class)
@ConditionalOnProperty(name = "agent.model.enabled", havingValue = "true", matchIfMissing = true)
public class SpringAiModelConfiguration {

    // --- Mapper Bean（无状态、线程安全，不需要条件控制） ---

    @Bean
    public SpringAiMessageMapper springAiMessageMapper() {
        return new SpringAiMessageMapper();
    }

    @Bean
    public SpringAiToolMapper springAiToolMapper() {
        return new SpringAiToolMapper();
    }

    @Bean
    public SpringAiResponseMapper springAiResponseMapper() {
        return new SpringAiResponseMapper();
    }

    // --- ModelClient Bean ---

    @Bean
    public ModelClient modelClient(ChatModel chatModel,
                                   SpringAiMessageMapper messageMapper,
                                   SpringAiToolMapper toolMapper,
                                   SpringAiResponseMapper responseMapper) {
        return new SpringAiModelClient(chatModel, messageMapper, toolMapper, responseMapper);
    }

    // --- ModelInvocationGateway Bean ---

    @Bean
    public ModelInvocationGateway modelInvocationGateway(ModelClient modelClient) {
        return new DefaultModelInvocationGateway(modelClient);
    }

    // --- Application Service Bean ---

    @Bean
    public ModelDevApplicationService modelDevApplicationService(
            ModelInvocationGateway modelInvocationGateway,
            ToolRegistry toolRegistry,
            RunIdGenerator runIdGenerator) {
        return new ModelDevApplicationService(modelInvocationGateway, toolRegistry, runIdGenerator);
    }
}
