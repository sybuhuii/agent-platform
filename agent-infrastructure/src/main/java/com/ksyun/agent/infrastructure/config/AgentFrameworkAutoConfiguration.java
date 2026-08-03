package com.ksyun.agent.infrastructure.config;

import com.ksyun.agent.application.framework.FrameworkQueryService;
import com.ksyun.agent.application.memory.LongTermMemoryApplicationService;
import com.ksyun.agent.application.memory.MemoryEntryValidator;
import com.ksyun.agent.core.agent.AgentProvider;
import com.ksyun.agent.core.approval.ApprovalIdGenerator;
import com.ksyun.agent.core.context.ContextSummarizer;
import com.ksyun.agent.core.context.ContextSummaryOptions;
import com.ksyun.agent.core.context.TokenCounter;
import com.ksyun.agent.core.memory.MemoryContextOptions;
import com.ksyun.agent.core.memory.MemoryIdGenerator;
import com.ksyun.agent.core.store.CheckpointIdGenerator;
import com.ksyun.agent.core.run.ThreadIdGenerator;
import com.ksyun.agent.core.sanitizer.SensitiveValueSanitizer;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.core.store.MemoryStore;
import com.ksyun.agent.core.tool.ToolProvider;
import com.ksyun.agent.core.supervisor.SupervisorProvider;
import com.ksyun.agent.infrastructure.approval.UuidApprovalIdGenerator;
import com.ksyun.agent.infrastructure.config.MemoryProperties;
import com.ksyun.agent.infrastructure.sanitizer.DefaultSensitiveValueSanitizer;
import com.ksyun.agent.infrastructure.store.InMemoryCheckpointStore;
import com.ksyun.agent.infrastructure.store.InMemoryMemoryStore;
import com.ksyun.agent.infrastructure.store.UuidCheckpointIdGenerator;
import com.ksyun.agent.infrastructure.store.UuidThreadIdGenerator;
import com.ksyun.agent.infrastructure.store.UuidMemoryIdGenerator;
import com.ksyun.agent.infrastructure.tool.builtin.BuiltinToolProvider;
import com.ksyun.agent.runtime.context.ContextMessageGrouper;
import com.ksyun.agent.runtime.context.ContextMessageHistoryValidator;
import com.ksyun.agent.runtime.context.ContextProcessingPipeline;
import com.ksyun.agent.runtime.context.ContextSummaryMerger;
import com.ksyun.agent.runtime.context.ContextSummaryPromptBuilder;
import com.ksyun.agent.runtime.context.ContextSummarySelector;
import com.ksyun.agent.runtime.context.ContextSummaryTrigger;
import com.ksyun.agent.runtime.context.HeuristicTokenCounter;
import com.ksyun.agent.runtime.context.LlmContextSummarizer;
import com.ksyun.agent.runtime.context.MessageCountContextTrimmer;
import com.ksyun.agent.runtime.context.TokenCountContextTrimmer;
import com.ksyun.agent.runtime.context.ContextTokenBudgetCalculator;
import com.ksyun.agent.runtime.memory.LongTermMemoryContextProvider;
import com.ksyun.agent.runtime.memory.MemoryContextRenderer;
import com.ksyun.agent.infrastructure.memory.RememberUserMemoryTool;
import com.ksyun.agent.runtime.model.ModelInvocationGateway;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import com.ksyun.agent.core.approval.HumanApprovalGateway;
import com.ksyun.agent.runtime.hitl.LangChain4jHumanApprovalGateway;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

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
@EnableConfigurationProperties({ContextProperties.class, MemoryProperties.class, PersistenceProperties.class})
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
                                                         SupervisorRegistry supervisorRegistry,
                                                         com.ksyun.agent.core.context.ContextTrimmer contextTrimmer,
                                                         TokenCounter tokenCounter,
                                                         ContextProperties contextProperties,
                                                         ObjectProvider<ContextSummarizer> summarizerProvider,
                                                         ObjectProvider<com.ksyun.agent.runtime.context.ContextWindowManager> contextWindowManagerProvider,
                                                         ObjectProvider<com.ksyun.agent.application.context.ContextDemoApplicationService> demoServiceProvider,
                                                         MemoryProperties memoryProperties,
                                                         ObjectProvider<MemoryStore> memoryStoreProvider,
                                                         ObjectProvider<LongTermMemoryContextProvider> memoryContextProvider,
                                                         ObjectProvider<RememberUserMemoryTool> rememberToolProvider) {
        ContextProperties.Summary summaryProps = contextProperties.getSummary();
        ContextSummaryOptions summaryOptions = new ContextSummaryOptions(
                summaryProps.isEnabled(),
                summaryProps.getTriggerRatio(),
                summaryProps.getMinSourceTokens(),
                summaryProps.getMaxSummaryTokens(),
                summaryProps.getRecentGroupsToPreserve()
        );

        boolean summaryAvailable = summarizerProvider.getIfAvailable() != null;
        boolean contextEnabled = contextProperties.isEnabled();

        // Phase7 Batch4 新增运行时集成状态
        boolean hasContextWindowManager = contextWindowManagerProvider.getIfAvailable() != null;
        boolean hasDemoService = demoServiceProvider.getIfAvailable() != null;

        FrameworkQueryService.ContextConfig contextConfig = new FrameworkQueryService.ContextConfig(
                contextEnabled,
                contextProperties.isMessageCountTrimmingEnabled(),
                contextProperties.getMaxMessages(),
                contextProperties.isTokenTrimmingEnabled(),
                contextProperties.getMaxContextTokens(),
                contextProperties.getReservedOutputTokens(),
                contextProperties.getReservedProtocolTokens(),
                contextProperties.getSafetyMarginTokens(),
                summaryOptions,
                summaryAvailable,
                // Phase7 Batch4 新增
                contextEnabled && hasContextWindowManager, // runtimeIntegrationEnabled
                contextEnabled && hasContextWindowManager, // reactIntegrated
                contextEnabled && hasContextWindowManager, // supervisorIntegrated
                contextEnabled,                            // contextWindowSnapshotEnabled
                true,                                      // fullHistoryPreserved
                contextEnabled ? "SYSTEM_MESSAGE_WITH_WRAPPER" : "NONE", // summaryMessageMapping
                contextEnabled,                            // resultMetadataEnabled
                hasDemoService                             // demoAvailable
        );

        // 长期记忆配置
        boolean memoryEnabled = memoryProperties.isEnabled();
        boolean memoryStoreAvailable = memoryStoreProvider.getIfAvailable() != null;
        boolean memoryCrudAvailable =
                memoryEnabled && memoryStoreAvailable;

        boolean memoryContextAvailable =
                memoryCrudAvailable
                        && memoryProperties.getContext().isEnabled()
                        && memoryContextProvider.getIfAvailable() != null;

        boolean rememberToolAvailable =
                memoryCrudAvailable
                        && memoryProperties.getTools().isRememberEnabled()
                        && rememberToolProvider.getIfAvailable() != null;
        FrameworkQueryService.MemoryConfig memoryConfig = new FrameworkQueryService.MemoryConfig(
                memoryEnabled,
                memoryStoreAvailable,
                memoryProperties.getBackend(),
                memoryProperties.getDefaultNamespace(),
                "userId",
                memoryCrudAvailable, // supportsPut
                memoryCrudAvailable, // supportsGet
                memoryCrudAvailable, // supportsList
                memoryCrudAvailable, // supportsDelete
                "CheckpointStore",
                "MemoryStore",
                true,   // storesSeparated
                // Phase8 Batch3 线程续接能力
                true,   // reactThreadContinuationSupported
                true,   // agentApiAcceptsThreadId
                true,   // serverGeneratesThreadId
                true,   // newRunPerInvocation
                true,   // completeHistoryPreserved
                contextEnabled && hasContextWindowManager, // contextWindowSnapshotContinued
                true,   // sameThreadExecutionSerialized
                true,   // failedRunDoesNotOverwriteStableState
                true,   // supervisorThreadContinuationSupported
                true,   // supervisorApiAcceptsThreadId
                true,   // subAgentsUseFreshContext
                true,   // hitlResumeThreadSyncSupported
                "SAVE_THREAD_MEMORY_THEN_DELETE_HITL",  // hitlThreadSyncOrder
                // Phase8 Batch5 长期记忆上下文
                memoryContextAvailable, // longTermContextInjectionEnabled
                memoryContextAvailable, // longTermContextAutoRead
                memoryContextAvailable, // longTermContextEphemeral
                false,                  // memoryContextStoredInThreadCheckpoint
                rememberToolAvailable,  // rememberToolEnabled
                rememberToolAvailable,  // rememberToolUsesAuthenticatedUser
                "remember_user_memory", // rememberToolName
                memoryCrudAvailable,    // crossThreadMemorySupported
                "userId", // crossUserIsolation
                memoryProperties.getContext().getNamespaces(), // memoryContextNamespaces
                memoryProperties.getContext().getMaxEntries(), // memoryContextMaxEntries
                memoryProperties.getContext().getMaxInjectedTokens() // memoryContextMaxInjectedTokens
        );

        return new FrameworkQueryService(agentRegistry, toolRegistry, supervisorRegistry,
                contextTrimmer, tokenCounter, contextConfig, memoryConfig);
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

    @Bean(name = "humanApprovalExecutor", destroyMethod = "shutdownNow")
    @ConditionalOnMissingBean(name = "humanApprovalExecutor")
    public ExecutorService humanApprovalExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable,
                    "langchain4j-hitl-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    @ConditionalOnMissingBean(HumanApprovalGateway.class)
    public HumanApprovalGateway humanApprovalGateway(
            @Qualifier("humanApprovalExecutor") ExecutorService executor) {
        return new LangChain4jHumanApprovalGateway(executor);
    }

    @Bean
    public ToolApprovalInterceptor toolApprovalInterceptor(
            ToolRegistry toolRegistry,
            ToolApprovalPolicy approvalPolicy,
            ApprovalIdGenerator approvalIdGenerator,
            SensitiveValueSanitizer sanitizer,
            ToolOperationFingerprint fingerprint,
            HumanApprovalGateway humanApprovalGateway,
            Clock clock) {
        return new ToolApprovalInterceptor(
                toolRegistry,
                approvalPolicy,
                approvalIdGenerator,
                sanitizer,
                fingerprint,
                humanApprovalGateway,
                clock);
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

    // --- Phase7 Batch1 Context Management Beans ---

    @Bean
    @ConditionalOnMissingBean
    public ContextMessageHistoryValidator contextMessageHistoryValidator() {
        return new ContextMessageHistoryValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextMessageGrouper contextMessageGrouper() {
        return new ContextMessageGrouper();
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenCounter tokenCounter() {
        return new HeuristicTokenCounter();
    }

    @Bean
    @ConditionalOnMissingBean
    public com.ksyun.agent.core.context.ContextTrimmer contextTrimmer(
            ContextMessageHistoryValidator historyValidator,
            ContextMessageGrouper grouper,
            TokenCounter tokenCounter) {
        return new MessageCountContextTrimmer(
                historyValidator, grouper, tokenCounter);
    }

    // --- Phase7 Batch2 Token Budget & Pipeline Beans ---

    @Bean
    @ConditionalOnMissingBean
    public ContextTokenBudgetCalculator contextTokenBudgetCalculator() {
        return new ContextTokenBudgetCalculator();
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenCountContextTrimmer tokenCountContextTrimmer(
            ContextMessageHistoryValidator historyValidator,
            ContextMessageGrouper grouper,
            TokenCounter tokenCounter) {
        return new TokenCountContextTrimmer(
                historyValidator, grouper, tokenCounter);
    }

    // --- Phase7 Batch3 Summary Beans ---

    @Bean
    @ConditionalOnMissingBean
    public ContextSummaryTrigger contextSummaryTrigger() {
        return new ContextSummaryTrigger();
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextSummarySelector contextSummarySelector(
            ContextMessageGrouper grouper,
            TokenCounter tokenCounter) {
        return new ContextSummarySelector(grouper, tokenCounter);
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextSummaryMerger contextSummaryMerger(
            ContextMessageHistoryValidator historyValidator) {
        return new ContextSummaryMerger(historyValidator);
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextSummaryPromptBuilder contextSummaryPromptBuilder(
            SensitiveValueSanitizer sanitizer,
            TokenCounter tokenCounter) {
        return new ContextSummaryPromptBuilder(sanitizer, tokenCounter);
    }

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    @ConditionalOnMissingBean
    public ContextSummarizer contextSummarizer(
            ModelInvocationGateway modelInvocationGateway,
            ContextSummaryPromptBuilder promptBuilder,
            TokenCounter tokenCounter,
            Clock clock) {
        return new LlmContextSummarizer(modelInvocationGateway, promptBuilder, tokenCounter, clock);
    }

    // --- Pipeline Bean（需要在摘要 Bean 之后装配） ---

    @Bean
    @ConditionalOnMissingBean
    public ContextProcessingPipeline contextProcessingPipeline(
            com.ksyun.agent.core.context.ContextTrimmer contextTrimmer,
            TokenCountContextTrimmer tokenCountTrimmer,
            TokenCounter tokenCounter,
            ContextSummaryTrigger summaryTrigger,
            ContextSummarySelector summarySelector,
            ContextSummaryMerger summaryMerger,
            ObjectProvider<ContextSummarizer> summarizerProvider) {
        ContextSummarizer summarizer = summarizerProvider.getIfAvailable();
        return new ContextProcessingPipeline(
                contextTrimmer, tokenCountTrimmer, tokenCounter,
                summaryTrigger, summarySelector, summaryMerger,
                Optional.ofNullable(summarizer));
    }

    // --- Phase7 Batch4 Context Window Manager Beans ---

    @Bean
    @ConditionalOnMissingBean
    public com.ksyun.agent.runtime.context.ContextProcessingRequestFactory contextProcessingRequestFactory(
            ContextProperties contextProperties) {
        ContextProperties.Summary summaryProps = contextProperties.getSummary();
        ContextSummaryOptions summaryOptions = new ContextSummaryOptions(
                summaryProps.isEnabled(),
                summaryProps.getTriggerRatio(),
                summaryProps.getMinSourceTokens(),
                summaryProps.getMaxSummaryTokens(),
                summaryProps.getRecentGroupsToPreserve()
        );

        com.ksyun.agent.core.context.ContextTokenBudget tokenBudget = null;
        if (contextProperties.isTokenTrimmingEnabled()) {
            tokenBudget = com.ksyun.agent.core.context.ContextTokenBudget.calculate(
                    contextProperties.getMaxContextTokens(),
                    contextProperties.getReservedOutputTokens(),
                    contextProperties.getReservedProtocolTokens(),
                    contextProperties.getSafetyMarginTokens());
        }

        int additionalReservedTokens = 0;

        return new com.ksyun.agent.runtime.context.ContextProcessingRequestFactory(
                contextProperties.isMessageCountTrimmingEnabled(),
                contextProperties.getMaxMessages(),
                contextProperties.isTokenTrimmingEnabled(),
                tokenBudget,
                additionalReservedTokens,
                summaryOptions);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.ksyun.agent.runtime.context.ContextWindowManager contextWindowManager(
            ContextProcessingPipeline pipeline,
            com.ksyun.agent.runtime.context.ContextProcessingRequestFactory requestFactory,
            Clock clock,
            ContextProperties contextProperties,
            TokenCounter tokenCounter) {
        return new com.ksyun.agent.runtime.context.ContextWindowManager(
                pipeline, requestFactory, clock, contextProperties.isEnabled(), tokenCounter);
    }

    // --- Phase7 Batch4 Context Demo Beans ---

    @Bean
    @ConditionalOnMissingBean
    public com.ksyun.agent.application.context.ContextDemoHistoryFactory contextDemoHistoryFactory() {
        return new com.ksyun.agent.application.context.ContextDemoHistoryFactory();
    }

    @Bean
    @ConditionalOnBean(ModelInvocationGateway.class)
    @ConditionalOnMissingBean
    public com.ksyun.agent.application.context.ContextDemoApplicationService contextDemoApplicationService(
            com.ksyun.agent.application.context.ContextDemoHistoryFactory historyFactory,
            ContextProcessingPipeline pipeline,
            com.ksyun.agent.runtime.context.ContextProcessingRequestFactory requestFactory,
            ModelInvocationGateway modelGateway,
            RunIdGenerator runIdGenerator,
            Clock clock) {
        return new com.ksyun.agent.application.context.ContextDemoApplicationService(
                historyFactory, pipeline, requestFactory, modelGateway, runIdGenerator, clock);
    }

    // --- Phase8 Batch1 长期记忆 Beans ---
    // 受 agent.memory.enabled=true 控制
    // MemoryStore 自定义 Bean 存在时允许替换内存实现
    // 无模型配置时 Memory Bean 可装配
    // 不注入 ModelInvocationGateway 和 CheckpointStore

    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "agent.memory.enabled", havingValue = "true", matchIfMissing = true)
    public MemoryStore memoryStore() {
        return new InMemoryMemoryStore();
    }

    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "agent.memory.enabled", havingValue = "true", matchIfMissing = true)
    public MemoryIdGenerator memoryIdGenerator() {
        return new UuidMemoryIdGenerator();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "agent.memory.enabled", havingValue = "true", matchIfMissing = true)
    public MemoryEntryValidator memoryEntryValidator(MemoryProperties memoryProperties) {
        return new MemoryEntryValidator(
                memoryProperties.getMaxNamespaceLength(),
                memoryProperties.getMaxKeyLength(),
                memoryProperties.getMaxValueLength(),
                memoryProperties.getMaxMetadataEntries(),
                memoryProperties.getMaxMetadataKeyLength(),
                memoryProperties.getMaxMetadataValueLength());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "agent.memory.enabled", havingValue = "true", matchIfMissing = true)
    public LongTermMemoryApplicationService longTermMemoryApplicationService(
            MemoryStore memoryStore,
            MemoryIdGenerator memoryIdGenerator,
            Clock clock,
            MemoryEntryValidator validator) {
        return new LongTermMemoryApplicationService(
                memoryStore, memoryIdGenerator, clock, validator);
    }

    // --- Phase8 Batch5 记忆上下文 Bean ---
    // 受 agent.memory.enabled=true 和 agent.memory.context.enabled=true 控制
    // MemoryStore 不存在或 memory.enabled=false 时，Provider 返回空上下文

    @Bean
    @ConditionalOnProperty(
            name = "agent.memory.enabled",
            havingValue = "true",
            matchIfMissing = true)
    public MemoryContextOptions memoryContextOptions(
            MemoryProperties memoryProperties,
            ContextProperties contextProperties
    ) {
        MemoryProperties.Context memoryContext =
                memoryProperties.getContext();

        if (memoryContext.isEnabled()) {
            if (!contextProperties.isEnabled()
                    || !contextProperties.isTokenTrimmingEnabled()) {
                throw new IllegalArgumentException(
                        "Long-term memory context requires "
                                + "agent.context.enabled=true and "
                                + "agent.context.token-trimming-enabled=true");
            }

            int availableMessageBudget =
                    contextProperties.getMaxContextTokens()
                            - contextProperties.getReservedOutputTokens()
                            - contextProperties.getReservedProtocolTokens()
                            - contextProperties.getSafetyMarginTokens();

            if (availableMessageBudget <= 0) {
                throw new IllegalArgumentException(
                        "Effective context message budget must be > 0");
            }

            if (memoryContext.getMaxInjectedTokens()
                    >= availableMessageBudget) {
                throw new IllegalArgumentException(
                        "agent.memory.context.max-injected-tokens must be "
                                + "smaller than the effective context message budget");
            }
        }

        return new MemoryContextOptions(
                memoryContext.isEnabled(),
                memoryContext.getNamespaces(),
                memoryContext.getMaxEntries(),
                memoryContext.getMaxInjectedTokens());
    }

    @Bean
    @ConditionalOnProperty(
            name = "agent.memory.enabled", havingValue = "true", matchIfMissing = true)
    public MemoryContextRenderer memoryContextRenderer() {
        return new MemoryContextRenderer();
    }

    @Bean
    @ConditionalOnProperty(
            name = "agent.memory.enabled", havingValue = "true", matchIfMissing = true)
    public LongTermMemoryContextProvider longTermMemoryContextProvider(
            MemoryStore memoryStore,
            MemoryContextRenderer renderer,
            TokenCounter tokenCounter,
            MemoryContextOptions options,
            Clock clock) {
        return new LongTermMemoryContextProvider(
                memoryStore, renderer, tokenCounter, options, clock);
    }

    @Bean
    @ConditionalOnProperty(
            name = {"agent.memory.enabled", "agent.memory.tools.remember-enabled"},
            havingValue = "true", matchIfMissing = true)
    public RememberUserMemoryTool rememberUserMemoryTool(
            LongTermMemoryApplicationService memoryService) {
        return new RememberUserMemoryTool(memoryService);
    }

    @Bean
    @ConditionalOnProperty(
            name = {"agent.memory.enabled", "agent.memory.tools.remember-enabled"},
            havingValue = "true", matchIfMissing = true)
    public ToolProvider memoryToolProvider(RememberUserMemoryTool rememberTool) {
        return new BuiltinToolProvider(List.of(rememberTool));
    }

    // --- Phase8 Batch2 线程短期记忆 Beans ---
    // 无模型配置时这些 Bean 仍能装配
    // runtime 类不添加 Spring 注解
    // 不得注入 ReactAgentEngine、SupervisorEngine、ModelInvocationGateway
    // 不得注入 MemoryStore 到 ThreadConversationCheckpointService
    // 不得创建第二个 CheckpointStore 或 InMemoryCheckpointStore

    @Bean
    @ConditionalOnMissingBean
    public ThreadIdGenerator threadIdGenerator() {
        return new UuidThreadIdGenerator();
    }

    @Bean
    public com.ksyun.agent.runtime.checkpoint.thread.ThreadCheckpointStateMapper threadCheckpointStateMapper() {
        return new com.ksyun.agent.runtime.checkpoint.thread.ThreadCheckpointStateMapper();
    }

    @Bean
    public com.ksyun.agent.runtime.checkpoint.thread.ThreadIdValidator threadIdValidator() {
        return new com.ksyun.agent.runtime.checkpoint.thread.ThreadIdValidator();
    }

    @Bean
    public com.ksyun.agent.runtime.checkpoint.thread.ThreadExecutionCoordinator threadExecutionCoordinator() {
        return new com.ksyun.agent.runtime.checkpoint.thread.ThreadExecutionCoordinator();
    }

    @Bean
    public com.ksyun.agent.runtime.checkpoint.thread.ThreadConversationCheckpointService threadConversationCheckpointService(
            CheckpointStore checkpointStore,
            com.ksyun.agent.runtime.react.checkpoint.validator.CheckpointValidator checkpointValidator,
            com.ksyun.agent.runtime.checkpoint.thread.ThreadCheckpointStateMapper stateMapper,
            CheckpointIdGenerator checkpointIdGenerator,
            Clock clock) {
        return new com.ksyun.agent.runtime.checkpoint.thread.ThreadConversationCheckpointService(
                checkpointStore, checkpointValidator, stateMapper, checkpointIdGenerator, clock);
    }
}
