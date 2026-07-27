package com.ksyun.agent.infrastructure.sanitizer;

import com.ksyun.agent.core.sanitizer.SensitiveValueSanitizer;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 默认敏感值脱敏器实现。
 * <p>
 * 规则：
 * - key 忽略大小写
 * - 至少屏蔽 password、credential、token、apiKey、secret、authorization、sessionId
 * - 敏感值替换为 ***
 * - 限制单个值长度（256字符）和整体 payload 长度（4096字符）
 * - 不实现复杂反射或通用递归序列化框架
 * - 不记录完整工具参数
 */
public class DefaultSensitiveValueSanitizer implements SensitiveValueSanitizer {

    private static final String MASK = "***";
    private static final int MAX_VALUE_LENGTH = 256;
    private static final int MAX_PAYLOAD_SIZE = 4096;

    /** 忽略大小写的敏感 key 前缀/包含匹配 */
    private static final Set<String> SENSITIVE_KEY_PATTERNS = Set.of(
            "password", "credential", "token", "apikey", "secret", "authorization", "sessionid"
    );

    @Override
    public Map<String, Object> sanitize(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> result = new HashMap<>();
        int totalSize = 0;

        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // 检查是否为敏感 key
            if (isSensitiveKey(key)) {
                result.put(key, MASK);
            } else {
                // 截断过长值
                String strValue = truncateValue(value);
                result.put(key, strValue);
                totalSize += strValue.length();
            }

            // 整体 payload 长度限制
            if (totalSize > MAX_PAYLOAD_SIZE) {
                break;
            }
        }

        return Collections.unmodifiableMap(result);
    }

    private boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String lowerKey = key.toLowerCase();
        return SENSITIVE_KEY_PATTERNS.stream().anyMatch(lowerKey::contains);
    }

    private String truncateValue(Object value) {
        if (value == null) {
            return "";
        }
        String str = String.valueOf(value);
        if (str.length() > MAX_VALUE_LENGTH) {
            return str.substring(0, MAX_VALUE_LENGTH) + "...(truncated)";
        }
        return str;
    }
}
