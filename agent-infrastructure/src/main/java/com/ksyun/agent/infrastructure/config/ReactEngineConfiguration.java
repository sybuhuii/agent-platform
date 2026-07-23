package com.ksyun.agent.infrastructure.config;

import com.ksyun.agent.application.react.ReactDevApplicationService;
import com.ksyun.agent.runtime.model.ModelInvocationGateway;
import com.ksyun.agent.runtime.react.DefaultReactAgentEngine;
import com.ksyun.agent.runtime.react.ReactAgentEngine;
import com.ksyun.agent.runtime.react.ReactAgentGraphFactory;
import com.ksyun.agent.runtime.react.ReactExecutionValidator;
import com.ksyun.agent.runtime.react.ReactRouter;
import com.ksyun.agent.runtime.react.node.DefaultReactCompleteNode;
import com.ksyun.agent.runtime.react.node.DefaultReactFailureNode;
import com.ksyun.agent.runtime.react.node.DefaultReactMaxIterationsNode;
import com.ksyun.agent.runtime.react.node.DefaultReactObserveNode;
import com.ksyun.agent.runtime.react.node.DefaultReactReasonNode;
import com.ksyun.agent.runtime.react.node.DefaultReactToolExecutionNode;
import com.ksyun.agent.runtime.react.node.ReactCompleteNode;
import com.ksyun.agent.runtime.react.node.ReactFailureNode;
import com.ksyun.agent.runtime.react.node.ReactMaxIterationsNode;
import com.ksyun.agent.runtime.react.node.ReactObserveNode;
import com.ksyun.agent.runtime.react.node.ReactReasonNode;
import com.ksyun.agent.runtime.react.node.ReactToolExecutionNode;
import com.ksyun.agent.runtime.registry.AgentRegistry;
import com.ksyun.agent.runtime.registry.ToolRegistry;
import com.ksyun.agent.runtime.run.RunIdGenerator;
import com.ksyun.agent.runtime.tool.ToolInvocationGateway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/**
 * ReAct 引擎 Bean 装配。
 * <p>
 * 条件：
 * - 所有 ReAct 引擎相关 Bean 仅在 ModelInvocationGateway 存在时创建。
 * - 无模型配置时应用仍能正常启动，不注册 ReAct Bean。
 * - 不创建 Fake ModelClient 或伪造 ModelInvocationGateway。
 * - 不在 bootstrap 启动类中手工 new 组件。
 * - 不产生 Bean 循环依赖。
 */
@AutoConfiguration(after = SpringAiModelConfiguration.class)
@ConditionalOnBean(ModelInvocationGateway.class)
public class ReactEngineConfiguration {

    @Bean
    public ReactReasonNode reactReasonNode(ModelInvocationGateway modelInvocationGateway,
                                             ToolRegistry toolRegistry) {
        return new DefaultReactReasonNode(modelInvocationGateway, toolRegistry);
    }

    @Bean
    public ReactToolExecutionNode reactToolExecutionNode(ToolInvocationGateway toolInvocationGateway) {
        return new DefaultReactToolExecutionNode(toolInvocationGateway);
    }

    @Bean
    public ReactObserveNode reactObserveNode() {
        return new DefaultReactObserveNode();
    }

    @Bean
    public ReactCompleteNode reactCompleteNode() {
        return new DefaultReactCompleteNode();
    }

    @Bean
    public ReactMaxIterationsNode reactMaxIterationsNode() {
        return new DefaultReactMaxIterationsNode();
    }

    @Bean
    public ReactFailureNode reactFailureNode() {
        return new DefaultReactFailureNode();
    }

    @Bean
    public ReactRouter reactRouter() {
        return new ReactRouter();
    }

    @Bean
    public ReactExecutionValidator reactExecutionValidator() {
        return new ReactExecutionValidator();
    }

    @Bean
    public ReactAgentGraphFactory reactAgentGraphFactory(
            ReactReasonNode reasonNode,
            ReactToolExecutionNode toolExecutionNode,
            ReactObserveNode observeNode,
            ReactCompleteNode completeNode,
            ReactMaxIterationsNode maxIterationsNode,
            ReactFailureNode failureNode,
            ReactRouter router
    ) {
        return new ReactAgentGraphFactory(
                reasonNode, toolExecutionNode, observeNode,
                completeNode, maxIterationsNode, failureNode, router
        );
    }

    @Bean
    public ReactAgentEngine reactAgentEngine(ReactExecutionValidator validator,
                                              ReactAgentGraphFactory graphFactory) {
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
