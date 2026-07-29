package com.ksyun.agent.core.memory;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 长期记忆上下文选项，不可变。
 * <p>
 * 配置关闭时 Provider 返回空上下文。
 * 不得包含 userId、模型名称或 API Key。
 * 不得使用 Map 替代固定字段。
 */
public record MemoryContextOptions(
        boolean enabled,
        List<String> namespaces,
        int maxEntries,
        int maxInjectedTokens
) {

    public MemoryContextOptions {
        Objects.requireNonNull(namespaces, "namespaces must not be null");
        if (namespaces.isEmpty()) {
            throw new IllegalArgumentException("namespaces must not be empty");
        }
        namespaces = List.copyOf(namespaces);
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be > 0, got: " + maxEntries);
        }
        if (maxInjectedTokens <= 0) {
            throw new IllegalArgumentException("maxInjectedTokens must be > 0, got: " + maxInjectedTokens);
        }
    }
}
