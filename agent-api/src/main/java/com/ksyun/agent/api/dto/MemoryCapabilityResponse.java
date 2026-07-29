package com.ksyun.agent.api.dto;

import com.ksyun.agent.application.framework.FrameworkQueryService.MemoryCapabilityInfo;

import java.util.List;

/**
 * 长期记忆能力响应 DTO。
 * <p>
 * 只读，不暴露用户记忆内容、MemoryStore 内部 Map、Bean 完整类名、
 * 用户数量、记忆数量或 API Key。
 * 不暴露用户线程列表、消息内容、Checkpoint 数量、实现类完整包名。
 */
public record MemoryCapabilityResponse(
        boolean enabled,
        boolean longTermMemoryAvailable,
        String backend,
        String defaultNamespace,
        String namespaceIsolation,
        boolean supportsPut,
        boolean supportsGet,
        boolean supportsList,
        boolean supportsDelete,
        String shortTermStore,
        String longTermStore,
        boolean storesSeparated,
        // Phase8 Batch3 线程续接能力
        boolean reactThreadContinuationSupported,
        boolean agentApiAcceptsThreadId,
        boolean serverGeneratesThreadId,
        boolean newRunPerInvocation,
        boolean completeHistoryPreserved,
        boolean contextWindowSnapshotContinued,
        boolean sameThreadExecutionSerialized,
        boolean failedRunDoesNotOverwriteStableState,
        boolean supervisorThreadContinuationSupported,
        boolean supervisorApiAcceptsThreadId,
        boolean subAgentsUseFreshContext,
        boolean hitlResumeThreadSyncSupported,
        String hitlThreadSyncOrder,
        // Phase8 Batch5 长期记忆上下文
        boolean longTermContextInjectionEnabled,
        boolean longTermContextAutoRead,
        boolean longTermContextEphemeral,
        boolean memoryContextStoredInThreadCheckpoint,
        boolean rememberToolEnabled,
        boolean rememberToolUsesAuthenticatedUser,
        String rememberToolName,
        boolean crossThreadMemorySupported,
        String crossUserIsolation,
        List<String> memoryContextNamespaces,
        int memoryContextMaxEntries,
        int memoryContextMaxInjectedTokens
) {

    /**
     * 从 FrameworkQueryService 的 MemoryCapabilityInfo 构造响应。
     */
    public static MemoryCapabilityResponse from(MemoryCapabilityInfo info) {
        return new MemoryCapabilityResponse(
                info.enabled(),
                info.longTermMemoryAvailable(),
                info.backend(),
                info.defaultNamespace(),
                info.namespaceIsolation(),
                info.supportsPut(),
                info.supportsGet(),
                info.supportsList(),
                info.supportsDelete(),
                info.shortTermStore(),
                info.longTermStore(),
                info.storesSeparated(),
                // Phase8 Batch3 线程续接能力
                info.reactThreadContinuationSupported(),
                info.agentApiAcceptsThreadId(),
                info.serverGeneratesThreadId(),
                info.newRunPerInvocation(),
                info.completeHistoryPreserved(),
                info.contextWindowSnapshotContinued(),
                info.sameThreadExecutionSerialized(),
                info.failedRunDoesNotOverwriteStableState(),
                info.supervisorThreadContinuationSupported(),
                info.supervisorApiAcceptsThreadId(),
                info.subAgentsUseFreshContext(),
                info.hitlResumeThreadSyncSupported(),
                info.hitlThreadSyncOrder(),
                // Phase8 Batch5 长期记忆上下文
                info.longTermContextInjectionEnabled(),
                info.longTermContextAutoRead(),
                info.longTermContextEphemeral(),
                info.memoryContextStoredInThreadCheckpoint(),
                info.rememberToolEnabled(),
                info.rememberToolUsesAuthenticatedUser(),
                info.rememberToolName(),
                info.crossThreadMemorySupported(),
                info.crossUserIsolation(),
                info.memoryContextNamespaces(),
                info.memoryContextMaxEntries(),
                info.memoryContextMaxInjectedTokens()
        );
    }
}
