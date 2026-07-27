package com.ksyun.agent.infrastructure.config;

import com.ksyun.agent.application.approval.ApprovalDecisionService;
import com.ksyun.agent.application.approval.ApprovalResumeApplicationService;
import com.ksyun.agent.application.react.ReactDevApplicationService;
import com.ksyun.agent.core.approval.ApprovalIdGenerator;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
 * 仅当 ModelInvocationGateway Bean 存在时才装配（需要模型配置）。
 */
@AutoConfiguration(after = SpringAiModelConfiguration.class)
@ConditionalOnBean(ModelInvocationGateway.class)
public class ReactEngineConfiguration {

    @Bean
    public ReactExecutionValidator reactExecutionValidator(AgentRegistry agentRegistry) {
        return new ReactExecutionValidator(agentRegistry);
    }

    // ---- ReAct 节点 ----

    @Bean
    public ReactReasonNode reactReasonNode(ModelInvocationGateway modelGateway, ToolRegistry toolRegistry) {
        return new com.ksyun.agent.runtime.react.node.DefaultReactReasonNode(modelGateway, toolRegistry);
    }

    @Bean
    public ReactToolExecutionNode reactToolExecutionNode(
            ToolInvocationGateway toolGateway,
            ReactCheckpointService checkpointService,
            Clock clock) {
        return new com.ksyun.agent.runtime.react.node.DefaultReactToolExecutionNode(toolGateway, checkpointService, clock);
    }

    @Bean
    public ReactObserveNode reactObserveNode() {
        return new com.ksyun.agent.runtime.react.node.DefaultReactObserveNode();
    }

    @Bean
    public ReactCompleteNode reactCompleteNode() {
        return new com.ksyun.agent.runtime.react.node.DefaultReactCompleteNode();
    }

    @Bean
    public ReactMaxIterationsNode reactMaxIterationsNode() {
        return new com.ksyun.agent.runtime.react.node.DefaultReactMaxIterationsNode();
    }

    @Bean
    public ReactFailureNode reactFailureNode() {
        return new com.ksyun.agent.runtime.react.node.DefaultReactFailureNode();
    }

    @Bean
    public CheckpointValidator checkpointValidator() {
        return new CheckpointValidator();
    }

    @Bean
    public ReactCheckpointService reactCheckpointService(
            CheckpointStore checkpointStore,
            CheckpointIdGenerator checkpointIdGenerator,
            CheckpointValidator checkpointValidator,
            Clock clock) {
        return new ReactCheckpointService(checkpointStore, checkpointIdGenerator, checkpointValidator, clock);
    }

    @Bean
    public NodeAction<ReactAgentState> reactSuspendNode() {
        return new DefaultReactSuspendNode();
    }

    @Bean
    public ReactRouter reactRouter() {
        return new ReactRouter();
    }

    @Bean
    public ReactToolExecutionRouter reactToolExecutionRouter() {
        return new ReactToolExecutionRouter();
    }

    // ---- 图工厂和引擎 ----

    @Bean
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
    public ReactAgentEngine reactAgentEngine(ReactExecutionValidator validator, ReactAgentGraphFactory graphFactory) {
        return new DefaultReactAgentEngine(validator, graphFactory);
    }

    // ---- Phase6 Batch3 恢复链 ----

    @Bean
    public ReactResumeValidator reactResumeValidator() {
        return new ReactResumeValidator();
    }

    @Bean
    public ReactCheckpointStateMapper reactCheckpointStateMapper() {
        return new ReactCheckpointStateMapper();
    }

    @Bean
    public CheckpointResumeCoordinator checkpointResumeCoordinator(
            CheckpointStore checkpointStore,
            ReactResumeValidator resumeValidator,
            Clock clock) {
        return new CheckpointResumeCoordinator(checkpointStore, resumeValidator, clock);
    }

    @Bean
    public ReactResumeEngine reactResumeEngine(
            CheckpointResumeCoordinator resumeCoordinator,
            ReactCheckpointStateMapper stateMapper,
            ReactCheckpointService checkpointService,
            CheckpointStore checkpointStore,
            ReactAgentGraphFactory graphFactory) {
        return new ReactResumeEngine(resumeCoordinator, stateMapper, checkpointService, checkpointStore, graphFactory);
    }

    @Bean
    public ApprovalDecisionService approvalDecisionService(CheckpointStore checkpointStore, Clock clock) {
        return new ApprovalDecisionService(checkpointStore, clock);
    }

    @Bean
    public ApprovalResumeApplicationService approvalResumeApplicationService(
            ApprovalDecisionService decisionService,
            ReactResumeEngine resumeEngine) {
        return new ApprovalResumeApplicationService(decisionService, resumeEngine);
    }

    // ---- Dev Application Service ----

    @Bean
    public ReactDevApplicationService reactDevApplicationService(
            AgentRegistry agentRegistry,
            ReactAgentEngine reactAgentEngine,
            RunIdGenerator runIdGenerator) {
        return new ReactDevApplicationService(agentRegistry, reactAgentEngine, runIdGenerator);
    }
}
