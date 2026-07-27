package com.ksyun.agent.infrastructure.config;

import com.ksyun.agent.application.framework.FrameworkQueryService;
import com.ksyun.agent.core.agent.AgentProvider;
import com.ksyun.agent.core.approval.ApprovalIdGenerator;
import com.ksyun.agent.core.sanitizer.SensitiveValueSanitizer;
import com.ksyun.agent.core.store.CheckpointIdGenerator;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.core.tool.ToolProvider;
import com.ksyun.agent.core.supervisor.SupervisorProvider;
import com.ksyun.agent.infrastructure.approval.UuidApprovalIdGenerator;
import com.ksyun.agent.infrastructure.sanitizer.DefaultSensitiveValueSanitizer;
import com.ksyun.agent.infrastructure.store.InMemoryCheckpointStore;
import com.ksyun.agent.infrastructure.store.UuidCheckpointIdGenerator;
import com.ksyun.agent.runtime.registry.AgentProviderRegistrar;
import com.ksyun.agent.runtime.registry.AgentRegistry;
import com.ksyun.agent.runtime.registry.DefaultAgentRegistry;
import com.ksyun.agent.runtime.registry.DefaultSupervisorRegistry;
import com.ksyun.agent.runtime.registry.DefaultToolRegistry;
import com.ksyun.agent.runtime.registry.SupervisorProviderRegistrar;
import com.ksyun.agent.runtime.registry.SupervisorRegistry;
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
import com.ksyun.agent.runtime.tool.ToolAccessControlInterceptor;
import com.ksyun.agent.runtime.tool.approval.DefaultToolApprovalPolicy;
import com.ksyun.agent.runtime.tool.approval.ToolApprovalInterceptor;
import com.ksyun.agent.runtime.tool.approval.ToolApprovalPolicy;
import com.ksyun.agent.runtime.tool.approval.ToolOperationFingerprint;
import com.ksyun.agent.runtime.tool.authorization.DefaultToolPermissionEvaluator;
import com.ksyun.agent.runtime.tool.authorization.ToolPermissionEvaluator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.util.List;

/**
 * Agent 框架自动装配配置。
 * <p>
 * 注册默认实现并扫描所有 Provider 进行自动注册。
 * 没有 Provider 时应用仍然可以正常启动。
 * <p>
 * 拦截器实际顺序（按 order 升序排列）：
 * - Exception: -1000
 * - Audit: -500
 * - ACL: -200
 * - ArgumentValidation: -100
 * - Approval: 0
 * - Terminal: （最终执行）
 */
@AutoConfiguration
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
    public FrameworkQueryService frameworkQueryService(AgentRegistry agentRegistry,
                                                         ToolRegistry toolRegistry,
                                                         SupervisorRegistry supervisorRegistry) {
        return new FrameworkQueryService(agentRegistry, toolRegistry, supervisorRegistry);
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
    @ConditionalOnMissingBean
    public ToolPermissionEvaluator toolPermissionEvaluator() {
        return new DefaultToolPermissionEvaluator();
    }

    @Bean
    public ToolAccessControlInterceptor toolAccessControlInterceptor(ToolPermissionEvaluator permissionEvaluator) {
        return new ToolAccessControlInterceptor(permissionEvaluator);
    }

    // --- Phase6 Batch2 工具审批链 Bean ---

    @Bean
    @ConditionalOnMissingBean
    public ToolApprovalPolicy toolApprovalPolicy() {
        return new DefaultToolApprovalPolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    public ApprovalIdGenerator approvalIdGenerator() {
        return new UuidApprovalIdGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    public SensitiveValueSanitizer sensitiveValueSanitizer() {
        return new DefaultSensitiveValueSanitizer();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolOperationFingerprint toolOperationFingerprint() {
        return new ToolOperationFingerprint();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ToolApprovalInterceptor toolApprovalInterceptor(
            ToolRegistry toolRegistry,
            ToolApprovalPolicy approvalPolicy,
            ApprovalIdGenerator approvalIdGenerator,
            SensitiveValueSanitizer sanitizer,
            ToolOperationFingerprint fingerprint,
            Clock clock) {
        return new ToolApprovalInterceptor(toolRegistry, approvalPolicy, approvalIdGenerator, sanitizer, fingerprint, clock);
    }

    @Bean
    public ToolInvocationGateway toolInvocationGateway(
            List<ToolInterceptor> interceptors,
            TerminalToolExecutor terminalToolExecutor
    ) {
        return new DefaultToolInvocationGateway(interceptors, terminalToolExecutor);
    }

    // --- 第四阶段第1批新增 Bean ---

    @Bean
    @ConditionalOnMissingBean
    public SupervisorRegistry supervisorRegistry() {
        return new DefaultSupervisorRegistry();
    }

    @Bean
    public SupervisorProviderRegistrar supervisorProviderRegistrar(
            SupervisorRegistry supervisorRegistry,
            List<SupervisorProvider> providers
    ) {
        return new SupervisorProviderRegistrar(supervisorRegistry, providers);
    }

    @Bean
    public com.ksyun.agent.runtime.supervisor.SupervisorExecutionValidator supervisorExecutionValidator(
            AgentRegistry agentRegistry) {
        return new com.ksyun.agent.runtime.supervisor.SupervisorExecutionValidator(agentRegistry);
    }

    // --- Phase6 Batch1/2 Checkpoint Bean ---

    @Bean
    @ConditionalOnMissingBean
    public CheckpointStore checkpointStore() {
        return new InMemoryCheckpointStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public CheckpointIdGenerator checkpointIdGenerator() {
        return new UuidCheckpointIdGenerator();
    }
}
