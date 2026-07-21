package com.ksyun.agent.infrastructure.config;

import com.ksyun.agent.application.framework.FrameworkQueryService;
import com.ksyun.agent.core.agent.AgentProvider;
import com.ksyun.agent.core.tool.ToolProvider;
import com.ksyun.agent.runtime.registry.AgentProviderRegistrar;
import com.ksyun.agent.runtime.registry.AgentRegistry;
import com.ksyun.agent.runtime.registry.DefaultAgentRegistry;
import com.ksyun.agent.runtime.registry.DefaultToolRegistry;
import com.ksyun.agent.runtime.registry.ToolProviderRegistrar;
import com.ksyun.agent.runtime.registry.ToolRegistry;
import com.ksyun.agent.runtime.run.RunIdGenerator;
import com.ksyun.agent.runtime.run.UuidRunIdGenerator;
import com.ksyun.agent.runtime.tool.DefaultToolInvocationGateway;
import com.ksyun.agent.runtime.tool.TerminalToolExecutor;
import com.ksyun.agent.runtime.tool.ToolArgumentValidationInterceptor;
import com.ksyun.agent.runtime.tool.ToolAuditInterceptor;
import com.ksyun.agent.runtime.tool.ToolExceptionHandlingInterceptor;
import com.ksyun.agent.runtime.tool.ToolExecutionChain;
import com.ksyun.agent.runtime.tool.ToolInterceptor;
import com.ksyun.agent.runtime.tool.ToolInvocationGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Agent 框架自动装配配置。
 * <p>
 * 注册默认实现并扫描所有 Provider 进行自动注册。
 * 没有 Provider 时应用仍然可以正常启动。
 */
@Configuration
public class AgentFrameworkAutoConfiguration {

    // --- 第一阶段已有 Bean ---

    @Bean
    @ConditionalOnMissingBean
    public AgentRegistry agentRegistry() {
        return new DefaultAgentRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry() {
        return new DefaultToolRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public RunIdGenerator runIdGenerator() {
        return new UuidRunIdGenerator();
    }

    @Bean
    public FrameworkQueryService frameworkQueryService(AgentRegistry agentRegistry, ToolRegistry toolRegistry) {
        return new FrameworkQueryService(agentRegistry, toolRegistry);
    }

    @Bean
    public AgentProviderRegistrar agentProviderRegistrar(
            AgentRegistry agentRegistry,
            List<AgentProvider> providers
    ) {
        return new AgentProviderRegistrar(agentRegistry, providers);
    }

    @Bean
    public ToolProviderRegistrar toolProviderRegistrar(
            ToolRegistry toolRegistry,
            List<ToolProvider> providers
    ) {
        return new ToolProviderRegistrar(toolRegistry, providers);
    }

    // --- 第二阶段第1批新增 Bean ---

    @Bean
    public TerminalToolExecutor terminalToolExecutor(ToolRegistry toolRegistry) {
        return new TerminalToolExecutor(toolRegistry);
    }

    @Bean
    public ToolExceptionHandlingInterceptor toolExceptionHandlingInterceptor() {
        return new ToolExceptionHandlingInterceptor();
    }

    @Bean
    public ToolAuditInterceptor toolAuditInterceptor() {
        return new ToolAuditInterceptor();
    }

    @Bean
    public ToolArgumentValidationInterceptor toolArgumentValidationInterceptor(ToolRegistry toolRegistry) {
        return new ToolArgumentValidationInterceptor(toolRegistry);
    }

    @Bean
    public ToolInvocationGateway toolInvocationGateway(
            List<ToolInterceptor> interceptors,
            TerminalToolExecutor terminalToolExecutor
    ) {
        return new DefaultToolInvocationGateway(interceptors, terminalToolExecutor);
    }
}
