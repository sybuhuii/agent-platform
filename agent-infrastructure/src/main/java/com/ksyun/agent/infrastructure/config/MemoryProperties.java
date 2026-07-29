package com.ksyun.agent.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆配置属性。
 * <p>
 * 约束：
 * - 所有限制必须大于 0
 * - default-namespace 必须符合 namespace 规则
 * - backend 本批只支持 in-memory
 * - 不得自动创建数据库配置
 * - 不得包含 API Key
 * - 配置非法时启动失败并给出明确原因
 */
@ConfigurationProperties(prefix = "agent.memory")
public class MemoryProperties {

    /** 长期记忆是否启用 */
    private boolean enabled = true;

    /** 存储后端，本批只支持 in-memory */
    private String backend = "in-memory";

    /** 默认命名空间 */
    private String defaultNamespace = "profile";

    /** 命名空间最大长度，必须 > 0 */
    private int maxNamespaceLength = 64;

    /** 键最大长度，必须 > 0 */
    private int maxKeyLength = 128;

    /** 值最大长度，必须 > 0 */
    private int maxValueLength = 4096;

    /** 元数据最大条目数，必须 > 0 */
    private int maxMetadataEntries = 16;

    /** 元数据键最大长度，必须 > 0 */
    private int maxMetadataKeyLength = 64;

    /** 元数据值最大长度，必须 > 0 */
    private int maxMetadataValueLength = 256;

    /** 记忆上下文配置 */
    private Context context = new Context();

    /** 记忆工具配置 */
    private Tools tools = new Tools();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        if (backend == null || backend.isBlank()) {
            throw new IllegalArgumentException(
                    "agent.memory.backend must not be blank");
        }
        if (!"in-memory".equals(backend.trim())) {
            throw new IllegalArgumentException(
                    "agent.memory.backend only supports 'in-memory', got: " + backend);
        }
        this.backend = backend.trim();
    }

    public String getDefaultNamespace() {
        return defaultNamespace;
    }

    public void setDefaultNamespace(String defaultNamespace) {
        if (defaultNamespace == null || defaultNamespace.isBlank()) {
            throw new IllegalArgumentException(
                    "agent.memory.default-namespace must not be blank");
        }
        String trimmed = defaultNamespace.trim();
        if (!trimmed.matches("^[a-zA-Z0-9_.\\-]+$")) {
            throw new IllegalArgumentException(
                    "agent.memory.default-namespace must match namespace rules "
                            + "(letters, digits, _, -, .), got: " + trimmed);
        }
        this.defaultNamespace = trimmed;
    }

    public int getMaxNamespaceLength() {
        return maxNamespaceLength;
    }

    public void setMaxNamespaceLength(int maxNamespaceLength) {
        if (maxNamespaceLength <= 0) {
            throw new IllegalArgumentException(
                    "agent.memory.max-namespace-length must be > 0, got: " + maxNamespaceLength);
        }
        this.maxNamespaceLength = maxNamespaceLength;
    }

    public int getMaxKeyLength() {
        return maxKeyLength;
    }

    public void setMaxKeyLength(int maxKeyLength) {
        if (maxKeyLength <= 0) {
            throw new IllegalArgumentException(
                    "agent.memory.max-key-length must be > 0, got: " + maxKeyLength);
        }
        this.maxKeyLength = maxKeyLength;
    }

    public int getMaxValueLength() {
        return maxValueLength;
    }

    public void setMaxValueLength(int maxValueLength) {
        if (maxValueLength <= 0) {
            throw new IllegalArgumentException(
                    "agent.memory.max-value-length must be > 0, got: " + maxValueLength);
        }
        this.maxValueLength = maxValueLength;
    }

    public int getMaxMetadataEntries() {
        return maxMetadataEntries;
    }

    public void setMaxMetadataEntries(int maxMetadataEntries) {
        if (maxMetadataEntries <= 0) {
            throw new IllegalArgumentException(
                    "agent.memory.max-metadata-entries must be > 0, got: " + maxMetadataEntries);
        }
        this.maxMetadataEntries = maxMetadataEntries;
    }

    public int getMaxMetadataKeyLength() {
        return maxMetadataKeyLength;
    }

    public void setMaxMetadataKeyLength(int maxMetadataKeyLength) {
        if (maxMetadataKeyLength <= 0) {
            throw new IllegalArgumentException(
                    "agent.memory.max-metadata-key-length must be > 0, got: " + maxMetadataKeyLength);
        }
        this.maxMetadataKeyLength = maxMetadataKeyLength;
    }

    public int getMaxMetadataValueLength() {
        return maxMetadataValueLength;
    }

    public void setMaxMetadataValueLength(int maxMetadataValueLength) {
        if (maxMetadataValueLength <= 0) {
            throw new IllegalArgumentException(
                    "agent.memory.max-metadata-value-length must be > 0, got: " + maxMetadataValueLength);
        }
        this.maxMetadataValueLength = maxMetadataValueLength;
    }

    public Context getContext() {
        return context;
    }

    public void setContext(Context context) {
        this.context = context != null ? context : new Context();
    }

    public Tools getTools() {
        return tools;
    }

    public void setTools(Tools tools) {
        this.tools = tools != null ? tools : new Tools();
    }

    /**
     * 记忆上下文配置。
     */
    public static class Context {

        /** 是否启用长期记忆上下文注入 */
        private boolean enabled = true;

        /** 注入的命名空间列表 */
        private List<String> namespaces = new ArrayList<>(List.of("rules", "preferences", "profile", "facts"));

        /** 最大注入条目数，必须 > 0 */
        private int maxEntries = 20;

        /** 最大注入 Token 数，必须 > 0 */
        private int maxInjectedTokens = 1024;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getNamespaces() {
            return namespaces;
        }

        public void setNamespaces(List<String> namespaces) {
            if (namespaces == null || namespaces.isEmpty()) {
                throw new IllegalArgumentException(
                        "agent.memory.context.namespaces must not be empty");
            }
            for (String ns : namespaces) {
                if (ns == null || ns.isBlank()) {
                    throw new IllegalArgumentException(
                            "agent.memory.context.namespaces must not contain blank values");
                }
                if (!ns.trim().matches("^[a-zA-Z0-9_.\\-]+$")) {
                    throw new IllegalArgumentException(
                            "agent.memory.context.namespaces must match namespace rules, got: " + ns);
                }
            }
            this.namespaces = new ArrayList<>(namespaces);
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            if (maxEntries <= 0) {
                throw new IllegalArgumentException(
                        "agent.memory.context.max-entries must be > 0, got: " + maxEntries);
            }
            this.maxEntries = maxEntries;
        }

        public int getMaxInjectedTokens() {
            return maxInjectedTokens;
        }

        public void setMaxInjectedTokens(int maxInjectedTokens) {
            if (maxInjectedTokens <= 0) {
                throw new IllegalArgumentException(
                        "agent.memory.context.max-injected-tokens must be > 0, got: " + maxInjectedTokens);
            }
            this.maxInjectedTokens = maxInjectedTokens;
        }
    }

    /**
     * 记忆工具配置。
     */
    public static class Tools {

        /** 是否启用 remember_user_memory 工具 */
        private boolean rememberEnabled = true;

        public boolean isRememberEnabled() {
            return rememberEnabled;
        }

        public void setRememberEnabled(boolean rememberEnabled) {
            this.rememberEnabled = rememberEnabled;
        }
    }
}
