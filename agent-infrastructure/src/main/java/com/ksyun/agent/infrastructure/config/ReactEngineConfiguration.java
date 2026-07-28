package com.ksyun.agent.infrastructure.config;

import com.ksyun.agent.application.approval.ApprovalDecisionService;
import com.ksyun.agent.application.approval.ApprovalResumeApplicationService;
import com.ksyun.agent.application.approval.PendingApprovalQueryService;
import com.ksyun.agent.application.react.ReactDevApplicationService;
import com.ksyun.agent.core.approval.ApprovalIdGenerator;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.sanitizer.SensitiveValueSanitizer;
import com.ksyun.agent.core.store.CheckpointIdGenerator;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.runtime.model.ModelInvocationGateway;
import com.ksyun.agent.runtime.react.DefaultReactAgentEngine;
import com.ksyun.agent.runtime.react.ReactAgentEngine;
import com.ksyun.agent.runtime.react.ReactAgentGraphFactory;
import com.ksyun.agent.runtime.react.ReactExecutionValidator;
import com.ksyun.agent.runtime.react.ReactResumeEngine;
import com.ksyun.agent.runtime.react.ReactRouter;
import com.ksyun.agent.runtime.react.ReactToolExecutionRouter;
import com.ksyun.agent.runtime.react.checkpoint.CheckpointResumeCoordinator;
import com.ksyun.agent.runtime.react.checkpoint.ReactCheckpointService;
import com.ksyun.agent.runtime.react.checkpoint.ReactCheckpointStateMapper;
import com.ksyun.agent.runtime.react.checkpoint.ReactResumeValidator;
import com.ksyun.agent.runtime.react.checkpoint.validator.CheckpointValidator;
import com.ksyun.agent.runtime.react.node.DefaultReactSuspendNode;
import com.ksyun.agent.runtime.run.RunIdGenerator;
import com.ksyun.agent.runtime.registry.AgentRegistry;
import com.ksyun.agent.runtime.registry.ToolRegistry;
import com.ksyun.agent.runtime.tool.ToolInvocationGateway;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import com.ksyun.agent.runtime.react.node.ReactCompleteNode;
import com.ksyun.agent.runtime.react.node.ReactFailureNode;
import com.ksyun.agent.runtime.react.node.ReactMaxIterationsNode;
import com.ksyun.agent.runtime.react.node.ReactObserveNode;
import com.ksyun.agent.runtime.react.node.ReactReasonNode;
import com.ksyun.agent.runtime.react.node.ReactToolExecutionNode;
import com.ksyun.agent.runtime.react.ReactAgentState;

import java.time.Clock;

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
    public ApprovalDecisionService approvalDecisionService(CheckpointStore checkpointStore, Clock clock) {
        return new ApprovalDecisionService(checkpointStore, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactResumeValidator reactResumeValidator() {
        return new ReactResumeValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactCheckpointStateMapper reactCheckpointStateMapper() {
        return new ReactCheckpointStateMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public CheckpointValidator checkpointValidator() {
        return new CheckpointValidator();
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
    public CheckpointResumeCoordinator checkpointResumeCoordinator(
            CheckpointStore checkpointStore,
            ReactResumeValidator resumeValidator,
            Clock clock) {
        return new CheckpointResumeCoordinator(checkpointStore, resumeValidator, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public PendingApprovalQueryService pendingApprovalQueryService(CheckpointStore checkpointStore) {
        return new PendingApprovalQueryService(checkpointStore);
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
    public ReactReasonNode reactReasonNode(ModelInvocationGateway modelGateway, ToolRegistry toolRegistry) {
        return new com.ksyun.agent.runtime.react.node.DefaultReactReasonNode(modelGateway, toolRegistry);
    }

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ReactToolExecutionNode reactToolExecutionNode(
            ToolInvocationGateway toolGateway,
            ReactCheckpointService checkpointService,
            Clock clock) {
        return new com.ksyun.agent.runtime.react.node.DefaultReactToolExecutionNode(toolGateway, checkpointService, clock);
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

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ReactAgentGraphFactory reactAgentGraphFactory(
            ReactReasonNode reasonNode,
            ReactToolExecutionNode toolExecutionNode,
            ReactObserveNode observeNode,
            ReactCompleteNode completeNode,
            ReactMaxIterationsNode maxIterationsNode,
            ReactFailureNode failureNode,
            @Qualifier("reactSuspendNode") NodeAction<ReactAgentState> suspendNode,
            ReactRouter router,
            ReactToolExecutionRouter toolExecutionRouter
    ) {
        return new ReactAgentGraphFactory(
                reasonNode, toolExecutionNode, observeNode,
                completeNode, maxIterationsNode, failureNode,
                suspendNode, router, toolExecutionRouter
        );
    }

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ReactAgentEngine reactAgentEngine(ReactExecutionValidator validator, ReactAgentGraphFactory graphFactory) {
        return new DefaultReactAgentEngine(validator, graphFactory);
    }

    // ---- Phase6 Batch3 恢复链（需要模型）----

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ReactResumeEngine reactResumeEngine(
            CheckpointResumeCoordinator resumeCoordinator,
            ReactCheckpointStateMapper stateMapper,
            ReactCheckpointService checkpointService,
            CheckpointStore checkpointStore,
            ReactAgentGraphFactory graphFactory,
            Clock clock) {
        return new ReactResumeEngine(resumeCoordinator, stateMapper, checkpointService, checkpointStore, graphFactory, clock);
    }

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    public ApprovalResumeApplicationService approvalResumeApplicationService(
            ApprovalDecisionService decisionService,
            ReactResumeEngine resumeEngine) {
        return new ApprovalResumeApplicationService(decisionService, resumeEngine);
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
