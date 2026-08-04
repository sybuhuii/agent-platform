package com.ksyun.agent.infrastructure.config;

import com.ksyun.agent.application.approval.ApprovalDecisionService;
import com.ksyun.agent.application.approval.ApprovalResumeApplicationService;
import com.ksyun.agent.application.approval.PendingApprovalQueryService;
import com.ksyun.agent.application.conversation.ConversationHistoryApplicationService;
import com.ksyun.agent.application.react.ReactDevApplicationService;
import com.ksyun.agent.core.approval.ApprovalIdGenerator;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.sanitizer.SensitiveValueSanitizer;
import com.ksyun.agent.core.store.CheckpointIdGenerator;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadConversationCheckpointService;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadExecutionCoordinator;
import com.ksyun.agent.runtime.model.ModelInvocationGateway;
import com.ksyun.agent.runtime.memory.LongTermMemoryContextProvider;
import com.ksyun.agent.runtime.react.DefaultReactAgentEngine;
import com.ksyun.agent.runtime.react.ReactAgentEngine;
import com.ksyun.agent.runtime.react.ReactAgentGraphFactory;
import com.ksyun.agent.runtime.react.ReactPreExecutionRouter;
import com.ksyun.agent.runtime.react.ReactExecutionValidator;
import com.ksyun.agent.runtime.react.ReactResumeEngine;
import com.ksyun.agent.runtime.react.ReactRouter;
import com.ksyun.agent.runtime.react.ReactThreadConversationStateMapper;
import com.ksyun.agent.runtime.react.ReactThreadPersistencePolicy;
import com.ksyun.agent.runtime.react.ReactToolExecutionRouter;
import com.ksyun.agent.runtime.react.checkpoint.CheckpointResumeCoordinator;
import com.ksyun.agent.runtime.react.checkpoint.ReactCheckpointLifecycleService;
import com.ksyun.agent.runtime.react.checkpoint.ReactCheckpointService;
import com.ksyun.agent.runtime.react.checkpoint.ReactCheckpointStateMapper;
import com.ksyun.agent.runtime.react.checkpoint.ReactResumeValidator;
import com.ksyun.agent.runtime.react.checkpoint.validator.CheckpointValidator;
import com.ksyun.agent.runtime.react.node.*;
import com.ksyun.agent.runtime.hitl.node.NodeHitlInterruptService;
import com.ksyun.agent.runtime.hitl.node.NodeResumeHandler;
import com.ksyun.agent.runtime.hitl.node.NodeResumeHandlerRegistry;
import com.ksyun.agent.runtime.hitl.node.NodeResumeValidator;
import com.ksyun.agent.runtime.react.checkpoint.NodeCheckpointService;
import com.ksyun.agent.runtime.tool.approval.ToolOperationFingerprint;
import com.ksyun.agent.runtime.run.RunIdGenerator;
import com.ksyun.agent.runtime.registry.AgentRegistry;
import com.ksyun.agent.runtime.registry.ToolRegistry;
import com.ksyun.agent.runtime.tool.ToolInvocationGateway;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import com.ksyun.agent.core.approval.HumanApprovalGateway;
import com.ksyun.agent.runtime.react.ReactAgentState;

import java.time.Clock;
import java.util.List;

/**
 * ReAct 引擎 Spring 装配配置。
 * <p>
 * 策略：
 * - ApprovalDecisionService 无模型也可装配（审批决定不依赖模型）
 * - ReactResumeEngine 和依赖链需要模型（恢复需要执行图）
 * - ApprovalResumeApplicationService 需要模型（调用 ResumeEngine）
 * - 无模型能力时审批决定服务正常工作，实际 resume 返回 MODEL_NOT_AVAILABLE
 * - 不创建 Fake 模型
 */
@AutoConfiguration(after = SpringAiModelConfiguration.class)
public class ReactEngineConfiguration {

    // ---- 审批决定服务（无模型也可装配）----

    @Bean
    @ConditionalOnMissingBean
    public ApprovalDecisionService approvalDecisionService(
            CheckpointStore checkpointStore,
            HumanApprovalGateway humanApprovalGateway,
            Clock clock) {
        return new ApprovalDecisionService(checkpointStore, humanApprovalGateway, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactResumeValidator reactResumeValidator(ToolOperationFingerprint fingerprintCalculator) {
        return new ReactResumeValidator(fingerprintCalculator);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactCheckpointStateMapper reactCheckpointStateMapper() {
        return new ReactCheckpointStateMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public CheckpointValidator checkpointValidator(
            com.ksyun.agent.runtime.checkpoint.thread.ThreadCheckpointStateMapper threadStateMapper
    ) {
        return new CheckpointValidator(threadStateMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactCheckpointService reactCheckpointService(
            CheckpointStore checkpointStore,
            CheckpointIdGenerator checkpointIdGenerator,
            CheckpointValidator checkpointValidator,
            Clock clock) {
        return new ReactCheckpointService(checkpointStore, checkpointIdGenerator, checkpointValidator, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public NodeCheckpointService nodeCheckpointService(
            CheckpointStore checkpointStore,
            CheckpointIdGenerator checkpointIdGenerator,
            CheckpointValidator checkpointValidator,
            Clock clock) {
        return new NodeCheckpointService(
                checkpointStore, checkpointIdGenerator, checkpointValidator, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public NodeHitlInterruptService nodeHitlInterruptService(
            ApprovalIdGenerator approvalIdGenerator,
            HumanApprovalGateway humanApprovalGateway,
            NodeCheckpointService checkpointService,
            SensitiveValueSanitizer sanitizer,
            Clock clock) {
        return new NodeHitlInterruptService(
                approvalIdGenerator, humanApprovalGateway, checkpointService, sanitizer, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public NodeResumeValidator nodeResumeValidator() {
        return new NodeResumeValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public NodeResumeHandlerRegistry nodeResumeHandlerRegistry(
            List<NodeResumeHandler<? extends com.ksyun.agent.core.approval.NodeResumeData>> handlers) {
        return new NodeResumeHandlerRegistry(handlers);
    }

    @Bean
    @ConditionalOnMissingBean
    public CheckpointResumeCoordinator checkpointResumeCoordinator(
            CheckpointStore checkpointStore,
            Clock clock) {
        return new CheckpointResumeCoordinator(checkpointStore, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public PendingApprovalQueryService pendingApprovalQueryService(CheckpointStore checkpointStore) {
        return new PendingApprovalQueryService(checkpointStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactCheckpointLifecycleService reactCheckpointLifecycleService(
            CheckpointStore checkpointStore, Clock clock) {
        return new ReactCheckpointLifecycleService(checkpointStore, clock);
    }

    // ---- 模型依赖 Bean ----

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ReactExecutionValidator reactExecutionValidator(AgentRegistry agentRegistry) {
        return new ReactExecutionValidator(agentRegistry);
    }

    // ---- ReAct 节点 ----

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ReactReasonNode reactReasonNode(ModelInvocationGateway modelGateway,
                                            ToolRegistry toolRegistry,
                                            com.ksyun.agent.runtime.context.ContextWindowManager contextWindowManager,
                                            ObjectProvider<LongTermMemoryContextProvider> memoryContextProviderProvider) {
        LongTermMemoryContextProvider memoryContextProvider = memoryContextProviderProvider.getIfAvailable();
        return new com.ksyun.agent.runtime.react.node.DefaultReactReasonNode(
                modelGateway, toolRegistry, contextWindowManager, memoryContextProvider);
    }

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    @ConditionalOnMissingBean(ReactPreExecutionNode.class)
    public ReactPreExecutionNode reactPreExecutionNode() {
        return new DefaultReactPreExecutionNode();
    }

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ReactPreExecutionRouter reactPreExecutionRouter() {
        return new ReactPreExecutionRouter();
    }

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ReactToolExecutionNode reactToolExecutionNode(
            ToolInvocationGateway toolGateway,
            ReactCheckpointService checkpointService,
            HumanApprovalGateway humanApprovalGateway,
            Clock clock) {
        return new DefaultReactToolExecutionNode(
                toolGateway,
                checkpointService,
                humanApprovalGateway,
                clock);
    }

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ReactObserveNode reactObserveNode() {
        return new com.ksyun.agent.runtime.react.node.DefaultReactObserveNode();
    }

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ReactCompleteNode reactCompleteNode() {
        return new com.ksyun.agent.runtime.react.node.DefaultReactCompleteNode();
    }

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ReactMaxIterationsNode reactMaxIterationsNode() {
        return new com.ksyun.agent.runtime.react.node.DefaultReactMaxIterationsNode();
    }

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ReactFailureNode reactFailureNode() {
        return new com.ksyun.agent.runtime.react.node.DefaultReactFailureNode();
    }

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public NodeAction<ReactAgentState> reactSuspendNode() {
        return new DefaultReactSuspendNode();
    }

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ReactRouter reactRouter() {
        return new ReactRouter();
    }

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ReactToolExecutionRouter reactToolExecutionRouter() {
        return new ReactToolExecutionRouter();
    }

    // ---- 图工厂和引擎 ----

    // Phase8 Batch3 新增：线程续接 Mapper 和持久化策略
    // 无模型配置时这些 Bean 仍可装配

    @Bean
    @ConditionalOnMissingBean
    public ReactThreadConversationStateMapper reactThreadConversationStateMapper(Clock clock) {
        return new ReactThreadConversationStateMapper(clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactThreadPersistencePolicy reactThreadPersistencePolicy() {
        return new ReactThreadPersistencePolicy();
    }

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ReactAgentGraphFactory reactAgentGraphFactory(
            ReactPreExecutionNode preExecutionNode,
            ReactReasonNode reasonNode,
            ReactToolExecutionNode toolExecutionNode,
            ReactObserveNode observeNode,
            ReactCompleteNode completeNode,
            ReactMaxIterationsNode maxIterationsNode,
            ReactFailureNode failureNode,
            @Qualifier("reactSuspendNode") NodeAction<ReactAgentState> suspendNode,
            ReactRouter router,
            ReactToolExecutionRouter toolExecutionRouter,
            ReactPreExecutionRouter preExecutionRouter
    ) {
        return new ReactAgentGraphFactory(
                preExecutionNode, reasonNode, toolExecutionNode, observeNode,
                completeNode, maxIterationsNode, failureNode,
                suspendNode, router, toolExecutionRouter, preExecutionRouter
        );
    }

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ReactAgentEngine reactAgentEngine(
            ReactExecutionValidator validator,
            ReactAgentGraphFactory graphFactory,
            ReactThreadConversationStateMapper stateMapper,
            ReactThreadPersistencePolicy persistencePolicy,
            Clock clock) {
        return new DefaultReactAgentEngine(validator, graphFactory, stateMapper, persistencePolicy, clock);
    }

    // ---- Phase6 Batch3 恢复链（需要模型）----

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ReactResumeEngine reactResumeEngine(
            CheckpointResumeCoordinator resumeCoordinator,
            ReactCheckpointStateMapper stateMapper,
            ReactResumeValidator resumeValidator,
            NodeResumeValidator nodeResumeValidator,
            NodeResumeHandlerRegistry nodeResumeHandlerRegistry,
            ReactCheckpointLifecycleService lifecycleService,
            ReactAgentGraphFactory graphFactory,
            ReactThreadConversationStateMapper threadStateMapper,
            ReactThreadPersistencePolicy persistencePolicy,
            AgentRegistry agentRegistry,
            Clock clock) {
        return new ReactResumeEngine(resumeCoordinator, stateMapper, resumeValidator,
                nodeResumeValidator, nodeResumeHandlerRegistry,
                lifecycleService, graphFactory, threadStateMapper, persistencePolicy,
                agentRegistry, clock);
    }

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ApprovalResumeApplicationService approvalResumeApplicationService(
            ApprovalDecisionService decisionService,
            ReactResumeEngine resumeEngine,
            ObjectProvider<com.ksyun.agent.runtime.supervisor.SupervisorResumeEngine> supervisorResumeEngineProvider,
            CheckpointStore checkpointStore,
            ThreadExecutionCoordinator threadExecutionCoordinator,
            ThreadConversationCheckpointService threadConversationCheckpointService,
            com.ksyun.agent.runtime.supervisor.checkpoint.SupervisorChildRunLinkResolver linkResolver,
            HumanApprovalGateway humanApprovalGateway,
            ConversationHistoryApplicationService conversationHistoryApplicationService
    ) {
        com.ksyun.agent.runtime.supervisor.SupervisorResumeEngine supervisorResumeEngine =
                supervisorResumeEngineProvider.getIfAvailable();
        return new ApprovalResumeApplicationService(
                decisionService,
                resumeEngine,
                supervisorResumeEngine,
                checkpointStore,
                threadExecutionCoordinator,
                threadConversationCheckpointService,
                linkResolver,
                humanApprovalGateway,
                conversationHistoryApplicationService);
    }

    // ---- Dev Application Service ----

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ReactDevApplicationService reactDevApplicationService(
            AgentRegistry agentRegistry,
            ReactAgentEngine reactAgentEngine,
            RunIdGenerator runIdGenerator) {
        return new ReactDevApplicationService(agentRegistry, reactAgentEngine, runIdGenerator);
    }
}
