package com.ksyun.agent.infrastructure.config;

import com.ksyun.agent.application.react.ReactDevApplicationService;
import com.ksyun.agent.core.tool.AgentTool;
import com.ksyun.agent.runtime.model.ModelInvocationGateway;
import com.ksyun.agent.runtime.react.DefaultReactAgentEngine;
import com.ksyun.agent.runtime.react.ReactAgentEngine;
import com.ksyun.agent.runtime.react.ReactAgentGraphFactory;
import com.ksyun.agent.runtime.react.ReactExecutionValidator;
import com.ksyun.agent.runtime.react.ReactRouter;
import com.ksyun.agent.runtime.react.checkpoint.ReactCheckpointService;
import com.ksyun.agent.runtime.react.node.DefaultReactSuspendNode;
import com.ksyun.agent.runtime.run.RunIdGenerator;
import com.ksyun.agent.runtime.registry.AgentRegistry;
import com.ksyun.agent.runtime.registry.ToolRegistry;
import com.ksyun.agent.core.store.CheckpointStore;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.beans.factory.ObjectProvider;
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
    public ReactToolExecutionNode reactToolExecutionNode(com.ksyun.agent.runtime.tool.ToolInvocationGateway toolGateway) {
        return new com.ksyun.agent.runtime.react.node.DefaultReactToolExecutionNode(toolGateway);
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
    public ReactCheckpointService reactCheckpointService(CheckpointStore checkpointStore) {
        return new ReactCheckpointService(checkpointStore);
    }

    @Bean
    public NodeAction<ReactAgentState> reactSuspendNode(ReactCheckpointService checkpointService) {
        return new DefaultReactSuspendNode(checkpointService);
    }

    @Bean
    public ReactRouter reactRouter() {
        return new ReactRouter();
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
            NodeAction<ReactAgentState> suspendNode,
            ReactRouter router
    ) {
        return new ReactAgentGraphFactory(
                reasonNode, toolExecutionNode, observeNode,
                completeNode, maxIterationsNode, failureNode,
                suspendNode, router
        );
    }

    @Bean
    public ReactAgentEngine reactAgentEngine(ReactExecutionValidator validator, ReactAgentGraphFactory graphFactory) {
        return new DefaultReactAgentEngine(validator, graphFactory);
    }

    @Bean
    public ReactDevApplicationService reactDevApplicationService(
            AgentRegistry agentRegistry,
            ReactAgentEngine reactAgentEngine,
            RunIdGenerator runIdGenerator) {
        return new ReactDevApplicationService(agentRegistry, reactAgentEngine, runIdGenerator);
    }
}
