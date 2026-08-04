package com.ksyun.agent.infrastructure.config;

import com.ksyun.agent.application.supervisor.AuthenticatedSupervisorApplicationService;
import com.ksyun.agent.application.conversation.ConversationHistoryApplicationService;
import com.ksyun.agent.application.supervisor.SupervisorDevApplicationService;
import com.ksyun.agent.core.run.ThreadIdGenerator;
import com.ksyun.agent.core.store.CheckpointIdGenerator;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.infrastructure.supervisor.JacksonSupervisorDecisionParser;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadConversationCheckpointService;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadExecutionCoordinator;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadIdValidator;
import com.ksyun.agent.runtime.memory.LongTermMemoryContextProvider;
import com.ksyun.agent.runtime.model.ModelInvocationGateway;
import com.ksyun.agent.runtime.react.ReactAgentEngine;
import com.ksyun.agent.runtime.react.checkpoint.validator.CheckpointValidator;
import com.ksyun.agent.runtime.registry.AgentRegistry;
import com.ksyun.agent.runtime.registry.SupervisorRegistry;
import com.ksyun.agent.runtime.run.RunIdGenerator;
import com.ksyun.agent.runtime.supervisor.*;
import com.ksyun.agent.runtime.supervisor.checkpoint.SupervisorCheckpointService;
import com.ksyun.agent.runtime.supervisor.checkpoint.SupervisorCheckpointStateMapper;
import com.ksyun.agent.runtime.supervisor.checkpoint.SupervisorCheckpointValidator;
import com.ksyun.agent.runtime.supervisor.checkpoint.SupervisorChildRunLinkResolver;
import com.ksyun.agent.runtime.supervisor.node.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Supervisor 引擎 Bean 装配。
 * <p>
 * 条件：
 * - 所有 Supervisor 引擎相关 Bean 仅在 ModelInvocationGateway 和 ReactAgentEngine 存在时创建。
 * - 无模型配置时应用仍能正常启动，不注册 Supervisor Bean。
 * - 不创建 Fake ModelClient 或 Fake ReactAgentEngine。
 * - 不产生 Bean 循环依赖。
 * - 本批不注册 Sample Supervisor。
 * - runtime 类不得添加 Spring 注解。
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

    // --- Phase9 Batch3 新增：Supervisor Checkpoint Bean ---

    @Bean
    public SupervisorCheckpointStateMapper supervisorCheckpointStateMapper(
            SupervisorPromptBuilder promptBuilder
    ) {
        return new SupervisorCheckpointStateMapper(promptBuilder);
    }

    @Bean
    public SupervisorCheckpointValidator supervisorCheckpointValidator(CheckpointValidator checkpointValidator) {
        return new SupervisorCheckpointValidator(checkpointValidator);
    }

    @Bean
    public SupervisorCheckpointService supervisorCheckpointService(
            CheckpointStore checkpointStore,
            CheckpointIdGenerator checkpointIdGenerator,
            SupervisorCheckpointValidator supervisorCheckpointValidator,
            SupervisorCheckpointStateMapper supervisorCheckpointStateMapper,
            Clock clock) {
        return new SupervisorCheckpointService(
                checkpointStore, checkpointIdGenerator,
                supervisorCheckpointValidator, supervisorCheckpointStateMapper, clock);
    }

    @Bean
    public SupervisorChildRunLinkResolver supervisorChildRunLinkResolver() {
        return new SupervisorChildRunLinkResolver();
    }

    @Bean
    public SupervisorResumeEngine supervisorResumeEngine(
            SupervisorCheckpointService checkpointService,
            SupervisorCheckpointStateMapper stateMapper,
            SupervisorGraphFactory graphFactory,
            SupervisorThreadConversationStateMapper threadStateMapper,
            SupervisorThreadPersistencePolicy persistencePolicy,
            com.ksyun.agent.runtime.react.ReactResumeEngine reactResumeEngine,
            SupervisorRegistry supervisorRegistry,
            Clock clock) {
        return new SupervisorResumeEngine(
                checkpointService, stateMapper, graphFactory,
                threadStateMapper, persistencePolicy, reactResumeEngine,
                supervisorRegistry, clock);
    }

    // --- Supervisor Nodes ---

    @Bean
    public SupervisorReasonNode supervisorReasonNode(
            ModelInvocationGateway modelInvocationGateway,
            SupervisorDecisionParser decisionParser,
            AgentRegistry agentRegistry,
            RunIdGenerator runIdGenerator,
            com.ksyun.agent.runtime.context.ContextWindowManager contextWindowManager,
            ObjectProvider<LongTermMemoryContextProvider> memoryContextProviderProvider) {
        LongTermMemoryContextProvider memoryContextProvider = memoryContextProviderProvider.getIfAvailable();
        return new DefaultSupervisorReasonNode(modelInvocationGateway, decisionParser,
                agentRegistry, runIdGenerator, contextWindowManager, memoryContextProvider);
    }

    @Bean
    public SupervisorDispatchNode supervisorDispatchNode(
            AgentRegistry agentRegistry,
            ReactAgentEngine reactAgentEngine,
            RunIdGenerator runIdGenerator,
            SupervisorCheckpointService checkpointService) {
        return new DefaultSupervisorDispatchNode(agentRegistry, reactAgentEngine, runIdGenerator, checkpointService);
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

    @Bean
    public SupervisorSuspendNode supervisorSuspendNode() {
        return new DefaultSupervisorSuspendNode();
    }

    // --- SupervisorDispatchRouter ---

    @Bean
    public SupervisorDispatchRouter supervisorDispatchRouter() {
        return new SupervisorDispatchRouter();
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
            SupervisorSuspendNode suspendNode,
            SupervisorRouter router,
            SupervisorDispatchRouter dispatchRouter
    ) {
        return new SupervisorGraphFactory(
                reasonNode, dispatchNode, aggregateNode,
                completeNode, maxIterationsNode, failureNode, suspendNode,
                router, dispatchRouter
        );
    }

    // --- SupervisorExecutionValidator (已在 AgentFrameworkAutoConfiguration 中注册) ---
    // 此处不再重复注册

    // --- Phase8 Batch4 新增：Supervisor 线程 Mapper 和持久化策略 ---

    @Bean
    public SupervisorThreadConversationStateMapper supervisorThreadConversationStateMapper(
            SupervisorPromptBuilder promptBuilder,
            Clock clock) {
        return new SupervisorThreadConversationStateMapper(promptBuilder, clock);
    }

    @Bean
    public SupervisorThreadPersistencePolicy supervisorThreadPersistencePolicy() {
        return new SupervisorThreadPersistencePolicy();
    }

    // --- DefaultSupervisorEngine ---

    @Bean
    public SupervisorEngine supervisorEngine(
            SupervisorExecutionValidator validator,
            SupervisorPromptBuilder promptBuilder,
            SupervisorGraphFactory graphFactory,
            SupervisorThreadConversationStateMapper stateMapper,
            SupervisorThreadPersistencePolicy persistencePolicy,
            Clock clock) {
        return new DefaultSupervisorEngine(validator, promptBuilder, graphFactory, stateMapper, persistencePolicy, clock);
    }

    // --- AuthenticatedSupervisorApplicationService ---

    @Bean
    public AuthenticatedSupervisorApplicationService authenticatedSupervisorApplicationService(
            SupervisorRegistry supervisorRegistry,
            SupervisorEngine supervisorEngine,
            RunIdGenerator runIdGenerator,
            ThreadIdGenerator threadIdGenerator,
              ThreadIdValidator threadIdValidator,
              ThreadConversationCheckpointService threadConversationCheckpointService,
              ThreadExecutionCoordinator threadExecutionCoordinator,
              ConversationHistoryApplicationService conversationHistoryApplicationService) {
        return new AuthenticatedSupervisorApplicationService(
                supervisorRegistry, supervisorEngine, runIdGenerator,
                  threadIdGenerator, threadIdValidator,
                  threadConversationCheckpointService, threadExecutionCoordinator,
                  conversationHistoryApplicationService);
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
