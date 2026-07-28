package com.ksyun.agent.infrastructure.config;

import com.ksyun.agent.application.supervisor.SupervisorDevApplicationService;
import com.ksyun.agent.infrastructure.supervisor.JacksonSupervisorDecisionParser;
import com.ksyun.agent.runtime.model.ModelInvocationGateway;
import com.ksyun.agent.runtime.react.ReactAgentEngine;
import com.ksyun.agent.runtime.registry.AgentRegistry;
import com.ksyun.agent.runtime.registry.SupervisorRegistry;
import com.ksyun.agent.runtime.run.RunIdGenerator;
import com.ksyun.agent.runtime.supervisor.*;
import com.ksyun.agent.runtime.supervisor.node.*;
import com.ksyun.agent.runtime.tool.ToolInvocationGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/**
 * Supervisor 引擎 Bean 装配。
 * <p>
 * 条件：
 * - 所有 Supervisor 引擎相关 Bean 仅在 ModelInvocationGateway 和 ReactAgentEngine 存在时创建。
 * - 无模型配置时应用仍能正常启动，不注册 Supervisor Bean。
 * - 不创建 Fake ModelClient 或 Fake ReactAgentEngine。
 * - 不产生 Bean 循环依赖。
 * - 本批不注册 Sample Supervisor。
 * - 本批不创建 ApplicationService 或 Controller。
 */
@AutoConfiguration(after = ReactEngineConfiguration.class)
@ConditionalOnBean({ModelInvocationGateway.class, ReactAgentEngine.class})
public class SupervisorEngineConfiguration {

    // --- JacksonSupervisorDecisionParser ---

    @Bean
    public SupervisorDecisionParser supervisorDecisionParser(ObjectMapper objectMapper) {
        return new JacksonSupervisorDecisionParser(objectMapper);
    }

    // --- SupervisorPromptBuilder ---

    @Bean
    public SupervisorPromptBuilder supervisorPromptBuilder(AgentRegistry agentRegistry) {
        return new SupervisorPromptBuilder(agentRegistry);
    }

    // --- Supervisor Nodes ---

    @Bean
    public SupervisorReasonNode supervisorReasonNode(
            ModelInvocationGateway modelInvocationGateway,
            SupervisorDecisionParser decisionParser,
            AgentRegistry agentRegistry,
            RunIdGenerator runIdGenerator,
            com.ksyun.agent.runtime.context.ContextWindowManager contextWindowManager) {
        return new DefaultSupervisorReasonNode(modelInvocationGateway, decisionParser,
                agentRegistry, runIdGenerator, contextWindowManager);
    }

    @Bean
    public SupervisorDispatchNode supervisorDispatchNode(
            AgentRegistry agentRegistry,
            ReactAgentEngine reactAgentEngine,
            RunIdGenerator runIdGenerator) {
        return new DefaultSupervisorDispatchNode(agentRegistry, reactAgentEngine, runIdGenerator);
    }

    @Bean
    public SupervisorObservationFormatter supervisorObservationFormatter() {
        return new SupervisorObservationFormatter();
    }

    @Bean
    public SupervisorAggregateNode supervisorAggregateNode(
            SupervisorObservationFormatter observationFormatter) {
        return new DefaultSupervisorAggregateNode(observationFormatter);
    }

    @Bean
    public SupervisorCompleteNode supervisorCompleteNode() {
        return new DefaultSupervisorCompleteNode();
    }

    @Bean
    public SupervisorMaxIterationsNode supervisorMaxIterationsNode() {
        return new DefaultSupervisorMaxIterationsNode();
    }

    @Bean
    public SupervisorFailureNode supervisorFailureNode() {
        return new DefaultSupervisorFailureNode();
    }

    // --- SupervisorRouter ---

    @Bean
    public SupervisorRouter supervisorRouter() {
        return new SupervisorRouter();
    }

    // --- SupervisorGraphFactory ---

    @Bean
    public SupervisorGraphFactory supervisorGraphFactory(
            SupervisorReasonNode reasonNode,
            SupervisorDispatchNode dispatchNode,
            SupervisorAggregateNode aggregateNode,
            SupervisorCompleteNode completeNode,
            SupervisorMaxIterationsNode maxIterationsNode,
            SupervisorFailureNode failureNode,
            SupervisorRouter router
    ) {
        return new SupervisorGraphFactory(
                reasonNode, dispatchNode, aggregateNode,
                completeNode, maxIterationsNode, failureNode, router
        );
    }

    // --- SupervisorExecutionValidator (已在 AgentFrameworkAutoConfiguration 中注册) ---
    // 此处不再重复注册

    // --- DefaultSupervisorEngine ---

    @Bean
    public SupervisorEngine supervisorEngine(
            SupervisorExecutionValidator validator,
            SupervisorPromptBuilder promptBuilder,
            SupervisorGraphFactory graphFactory) {
        return new DefaultSupervisorEngine(validator, promptBuilder, graphFactory);
    }

    // --- SupervisorDevApplicationService ---

    @Bean
    public SupervisorDevApplicationService supervisorDevApplicationService(
            SupervisorRegistry supervisorRegistry,
            SupervisorEngine supervisorEngine,
            RunIdGenerator runIdGenerator) {
        return new SupervisorDevApplicationService(supervisorRegistry, supervisorEngine, runIdGenerator);
    }
}
