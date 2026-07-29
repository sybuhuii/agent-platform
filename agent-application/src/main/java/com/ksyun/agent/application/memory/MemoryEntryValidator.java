package com.ksyun.agent.application.memory;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.memory.MemoryCategory;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 长期记忆输入校验器，纯 Java。
 * <p>
 * 统一校验 namespace、key、value 和 metadata。
 * 敏感 metadata key 拒绝，路径穿越拒绝。
 * 不得在 Application Service 中散落全部校验代码。
 */
public class MemoryEntryValidator {

    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("^[a-zA-Z0-9_.\\-]+$");
    private static final Pattern KEY_PATTERN = Pattern.compile("^[^\\n\\/\\\\]+$");
    private static final Pattern PATH_TRAVERSAL = Pattern.compile("\\.\\.|/|\\\\");

    /** 敏感 metadata key（忽略大小写匹配） */
    private static final Set<String> SENSITIVE_METADATA_KEYS = Set.of(
            "password", "credential", "token", "apikey", "secret", "authorization", "sessionid"
    );

    private final int maxNamespaceLength;
    private final int maxKeyLength;
    private final int maxValueLength;
    private final int maxMetadataEntries;
    private final int maxMetadataKeyLength;
    private final int maxMetadataValueLength;

    public MemoryEntryValidator(int maxNamespaceLength,
                                 int maxKeyLength,
                                 int maxValueLength,
                                 int maxMetadataEntries,
                                 int maxMetadataKeyLength,
                                 int maxMetadataValueLength) {
        if (maxNamespaceLength <= 0 || maxKeyLength <= 0 || maxValueLength <= 0
                || maxMetadataEntries <= 0 || maxMetadataKeyLength <= 0 || maxMetadataValueLength <= 0) {
            throw new IllegalArgumentException("All validation limits must be > 0");
        }
        this.maxNamespaceLength = maxNamespaceLength;
        this.maxKeyLength = maxKeyLength;
        this.maxValueLength = maxValueLength;
        this.maxMetadataEntries = maxMetadataEntries;
        this.maxMetadataKeyLength = maxMetadataKeyLength;
        this.maxMetadataValueLength = maxMetadataValueLength;
    }

    /**
     * 校验命名空间。
     * <p>
     * 不能为空；最大长度限制；只允许字母、数字、_、-和.；
     * 不得包含空格和路径分隔符。
     */
    public void validateNamespace(String namespace) {
        Objects.requireNonNull(namespace, "namespace must not be null");
        if (namespace.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_MEMORY_ENTRY,
                    "namespace must not be blank");
        }
        String trimmed = namespace.trim();
        if (trimmed.length() > maxNamespaceLength) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_MEMORY_ENTRY,
                    "namespace exceeds max length " + maxNamespaceLength
                            + ", got: " + trimmed.length());
        }
        if (!NAMESPACE_PATTERN.matcher(trimmed).matches()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_MEMORY_ENTRY,
                    "namespace contains invalid characters, only letters, digits, _, -, . allowed");
        }
        if (PATH_TRAVERSAL.matcher(trimmed).find()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_MEMORY_ENTRY,
                    "namespace must not contain path traversal sequences");
        }
    }

    /**
     * 校验键。
     * <p>
     * 不能为空；最大长度限制；只允许稳定可读字符；
     * 不得包含换行和路径穿越。
     */
    public void validateKey(String key) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_MEMORY_ENTRY,
                    "key must not be blank");
        }
        String trimmed = key.trim();
        if (trimmed.length() > maxKeyLength) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_MEMORY_ENTRY,
                    "key exceeds max length " + maxKeyLength
                            + ", got: " + trimmed.length());
        }
        if (!KEY_PATTERN.matcher(trimmed).matches()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_MEMORY_ENTRY,
                    "key contains invalid characters (newline, slash or backslash not allowed)");
        }
        if (PATH_TRAVERSAL.matcher(trimmed).find()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_MEMORY_ENTRY,
                    "key must not contain path traversal sequences");
        }
    }

    /**
     * 校验值。
     * <p>
     * 不能为空；最大长度限制；不得只包含空白；
     * 不得包含明显的 credentialHash 字段；
     * 不得将完整模型 Prompt 作为记忆写入；
     * 不得将完整聊天记录作为单条记忆写入。
     */
    public void validateValue(String value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_MEMORY_ENTRY,
                    "value must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxValueLength) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_MEMORY_ENTRY,
                    "value exceeds max length " + maxValueLength
                            + ", got: " + trimmed.length());
        }
        String lower = trimmed.toLowerCase();
        if (lower.contains("credentialhash") || lower.contains("credential_hash")) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_MEMORY_ENTRY,
                    "value must not contain credential hash");
        }
    }

    /**
     * 校验元数据。
     * <p>
     * 条目数量受限制；key 和 value 长度受限制；
     * 禁止敏感 key（忽略大小写匹配）；
     * 发现敏感 key 时明确拒绝，不得仅脱敏后保存；
     * metadata 不能包含嵌套对象。
     */
    public void validateMetadata(Map<String, String> metadata) {
        if (metadata == null) {
            return;
        }
        if (metadata.size() > maxMetadataEntries) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_MEMORY_ENTRY,
                    "metadata entries exceed max " + maxMetadataEntries
                            + ", got: " + metadata.size());
        }
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String mk = entry.getKey();
            String mv = entry.getValue();

            if (mk == null || mk.isBlank()) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_MEMORY_ENTRY,
                        "metadata key must not be blank");
            }
            if (mk.length() > maxMetadataKeyLength) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_MEMORY_ENTRY,
                        "metadata key exceeds max length " + maxMetadataKeyLength
                                + ", got: " + mk.length());
            }
            if (mv != null && mv.length() > maxMetadataValueLength) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_MEMORY_ENTRY,
                        "metadata value exceeds max length " + maxMetadataValueLength);
            }

            // 敏感 key 检测（忽略大小写）
            String lowerKey = mk.toLowerCase();
            for (String sensitive : SENSITIVE_METADATA_KEYS) {
                if (lowerKey.contains(sensitive)) {
                    throw new AgentFrameworkException(AgentErrorCode.INVALID_MEMORY_ENTRY,
                            "metadata key '" + mk + "' is not allowed (contains sensitive keyword)");
                }
            }
        }
    }

    /**
     * 校验记忆类别。
     */
    public void validateCategory(MemoryCategory category) {
        Objects.requireNonNull(category, "category must not be null");
    }

    /**
     * 校验完整写入命令。
     */
    public void validate(MemoryWriteCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateNamespace(command.namespace());
        validateKey(command.key());
        validateValue(command.value());
        validateCategory(command.category());
        validateMetadata(command.metadata());
    }
}
