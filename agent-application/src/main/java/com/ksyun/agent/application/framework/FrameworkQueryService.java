package com.ksyun.agent.application.framework;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.context.ContextSummaryOptions;
import com.ksyun.agent.core.context.ContextTrimmer;
import com.ksyun.agent.core.context.TokenCounter;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.core.tool.ToolDefinition;
import com.ksyun.agent.runtime.registry.AgentRegistry;
import com.ksyun.agent.runtime.registry.SupervisorRegistry;
import com.ksyun.agent.runtime.registry.ToolRegistry;

import java.util.Collection;

/**
 * 框架查询服务，提供已注册 Agent、Tool 和 Supervisor 的查询能力。
 * <p>
 * 只依赖 runtime/core 的接口，不直接访问 Spring 容器，不实现注册逻辑。
 */
public class FrameworkQueryService {

    private final AgentRegistry agentRegistry;
    private final ToolRegistry toolRegistry;
    private final SupervisorRegistry supervisorRegistry;
    private final ContextTrimmer contextTrimmer;
    private final TokenCounter tokenCounter;
    private final ContextConfig contextConfig;

    public FrameworkQueryService(AgentRegistry agentRegistry,
                                  ToolRegistry toolRegistry,
                                  SupervisorRegistry supervisorRegistry) {
        this(agentRegistry, toolRegistry, supervisorRegistry, null, null, null);
    }

    public FrameworkQueryService(AgentRegistry agentRegistry,
                                  ToolRegistry toolRegistry,
                                  SupervisorRegistry supervisorRegistry,
                                  ContextTrimmer contextTrimmer,
                                  TokenCounter tokenCounter,
                                  ContextConfig contextConfig) {
        this.agentRegistry = agentRegistry;
        this.toolRegistry = toolRegistry;
        this.supervisorRegistry = supervisorRegistry;
        this.contextTrimmer = contextTrimmer;
        this.tokenCounter = tokenCounter;
        this.contextConfig = contextConfig;
    }

    /**
     * 查询所有已注册的 Agent 定义。
     */
    public Collection<AgentDefinition> listAgents() {
        return agentRegistry.list();
    }

    /**
     * 查询所有已注册的 Tool 定义。
     */
    public Collection<ToolDefinition> listTools() {
        return toolRegistry.list().stream()
                .map(tool -> tool.definition())
                .toList();
    }

    /**
     * 查询所有已注册的 Supervisor 定义。
     */
    public Collection<SupervisorDefinition> listSupervisors() {
        return supervisorRegistry.list();
    }

    /**
     * 查询框架上下文管理能力信息。
     *
     * @return 上下文能力信息
     */
    public ContextCapabilityInfo getContextCapability() {
        boolean enabled = contextConfig != null && contextConfig.enabled();
        boolean messageCountTrimmingEnabled = contextConfig != null
                && contextConfig.messageCountTrimmingEnabled();
        int maxMessages = contextConfig != null ? contextConfig.maxMessages() : 20;
        boolean tokenTrimmingEnabled = contextConfig != null
                && contextConfig.tokenTrimmingEnabled();
        int maxContextTokens = contextConfig != null ? contextConfig.maxContextTokens() : 0;
        int reservedOutputTokens = contextConfig != null ? contextConfig.reservedOutputTokens() : 0;
        int reservedProtocolTokens = contextConfig != null ? contextConfig.reservedProtocolTokens() : 0;
        int safetyMarginTokens = contextConfig != null ? contextConfig.safetyMarginTokens() : 0;

        // 计算 availableMessageTokens
        int availableMessageTokens = 0;
        if (tokenTrimmingEnabled && maxContextTokens > 0) {
            long available = (long) maxContextTokens - reservedOutputTokens
                    - reservedProtocolTokens - safetyMarginTokens;
            if (available > 0 && available <= Integer.MAX_VALUE) {
                availableMessageTokens = (int) available;
            }
        }

        String tokenCounterType = tokenCounter != null
                ? tokenCounter.getClass().getSimpleName() : "NONE";
        boolean exactTokenCount = false;

        // 摘要信息
        boolean summaryEnabled = false;
        boolean summaryAvailable = false;
        double summaryTriggerRatio = 0.0;
        int summaryTriggerTokens = 0;
        int summaryMinSourceTokens = 0;
        int summaryMaxTokens = 0;
        int summaryRecentGroupsToPreserve = 0;

        if (contextConfig != null && contextConfig.summaryOptions() != null) {
            ContextSummaryOptions opts = contextConfig.summaryOptions();
            summaryEnabled = opts.summaryEnabled();
            summaryTriggerRatio = opts.summaryTriggerRatio();
            summaryMinSourceTokens = opts.summaryMinSourceTokens();
            summaryMaxTokens = opts.summaryMaxTokens();
            summaryRecentGroupsToPreserve = opts.summaryRecentGroupsToPreserve();

            // 计算 summaryTriggerTokens
            if (tokenTrimmingEnabled && availableMessageTokens > 0) {
                summaryTriggerTokens = (int) Math.ceil(availableMessageTokens * summaryTriggerRatio);
            }
        }

        if (contextConfig != null) {
            summaryAvailable = contextConfig.summaryAvailable();
        }

        // 流水线顺序
        String pipelineOrder = "SUMMARY,MESSAGE_COUNT,TOKEN_COUNT";

        return new ContextCapabilityInfo(
                enabled,
                messageCountTrimmingEnabled,
                maxMessages,
                tokenTrimmingEnabled,
                maxContextTokens,
                reservedOutputTokens,
                reservedProtocolTokens,
                safetyMarginTokens,
                availableMessageTokens,
                tokenCounterType,
                exactTokenCount,
                pipelineOrder,
                summaryEnabled,
                summaryAvailable,
                summaryTriggerRatio,
                summaryTriggerTokens,
                summaryMinSourceTokens,
                summaryMaxTokens,
                summaryRecentGroupsToPreserve,
                true, // summaryUsesLlm: 摘要使用 LLM 生成
                "TRIM_WITHOUT_SUMMARY", // summaryFailureFallback: 摘要失败降级策略
                // Phase7 Batch4 新增
                contextConfig != null && contextConfig.runtimeIntegrationEnabled(),
                contextConfig != null && contextConfig.reactIntegrated(),
                contextConfig != null && contextConfig.supervisorIntegrated(),
                contextConfig != null && contextConfig.contextWindowSnapshotEnabled(),
                contextConfig != null && contextConfig.fullHistoryPreserved(),
                contextConfig != null ? contextConfig.summaryMessageMapping() : "NONE",
                contextConfig != null && contextConfig.resultMetadataEnabled(),
                contextConfig != null && contextConfig.demoAvailable()
        );
    }

    /**
     * 上下文配置值对象，由基础设施层从 ContextProperties 构造后传入。
     * <p>
     * 纯值对象，不依赖 Spring 配置注解。
     */
    public record ContextConfig(
            boolean enabled,
            boolean messageCountTrimmingEnabled,
            int maxMessages,
            boolean tokenTrimmingEnabled,
            int maxContextTokens,
            int reservedOutputTokens,
            int reservedProtocolTokens,
            int safetyMarginTokens,
            ContextSummaryOptions summaryOptions,
            boolean summaryAvailable,
            // Phase7 Batch4 新增
            boolean runtimeIntegrationEnabled,
            boolean reactIntegrated,
            boolean supervisorIntegrated,
            boolean contextWindowSnapshotEnabled,
            boolean fullHistoryPreserved,
            String summaryMessageMapping,
            boolean resultMetadataEnabled,
            boolean demoAvailable
    ) {}

    /**
     * 上下文能力信息。
     */
    public record ContextCapabilityInfo(
            boolean enabled,
            boolean messageCountTrimmingEnabled,
            int maxMessages,
            boolean tokenTrimmingEnabled,
            int maxContextTokens,
            int reservedOutputTokens,
            int reservedProtocolTokens,
            int safetyMarginTokens,
            int availableMessageTokens,
            String tokenCounterType,
            boolean exactTokenCount,
            String pipelineOrder,
            boolean summaryEnabled,
            boolean summaryAvailable,
            double summaryTriggerRatio,
            int summaryTriggerTokens,
            int summaryMinSourceTokens,
            int summaryMaxTokens,
            int summaryRecentGroupsToPreserve,
            boolean summaryUsesLlm,
            String summaryFailureFallback,
            // Phase7 Batch4 新增
            boolean runtimeIntegrationEnabled,
            boolean reactIntegrated,
            boolean supervisorIntegrated,
            boolean contextWindowSnapshotEnabled,
            boolean fullHistoryPreserved,
            String summaryMessageMapping,
            boolean resultMetadataEnabled,
            boolean demoAvailable
    ) {}
}
