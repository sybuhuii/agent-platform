package com.ksyun.agent.core.memory;

/**
 * 长期记忆上下文结果 metadata 稳定常量。
 * <p>
 * 只写安全统计，不写记忆正文、namespace、key、userId、Session ID、MemoryEntry。
 * 不存在 Trace 时不得伪造统计。
 */
public final class MemoryResultMetadataKeys {

    private MemoryResultMetadataKeys() {
    }

    public static final String AVAILABLE = "memory.available";
    public static final String TOTAL_ENTRY_COUNT = "memory.totalEntryCount";
    public static final String INJECTED_ENTRY_COUNT = "memory.injectedEntryCount";
    public static final String INJECTED_TOKEN_COUNT = "memory.injectedTokenCount";
    public static final String TRUNCATED = "memory.truncated";
}
