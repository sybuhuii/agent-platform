package com.ksyun.agent.infrastructure.tool.builtin;

import java.util.Map;

/**
 * 内置工具参数读取辅助。
 * <p>
 * 统一从 ToolCall.arguments (Map&lt;String, Object&gt;) 中安全读取参数，
 * 处理 JSON 反序列化后常见 Java 类型，不暴露底层 ClassCastException。
 */
final class ToolArgs {

    private ToolArgs() {
    }

    /**
     * 读取必填字符串参数。
     *
     * @return 参数值；缺失或类型不匹配时返回 null
     */
    static String getString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        return value.toString();
    }

    /**
     * 读取可选字符串参数，缺失时返回默认值。
     */
    static String getString(Map<String, Object> args, String key, String defaultValue) {
        String value = getString(args, key);
        return value != null ? value : defaultValue;
    }

    /**
     * 读取布尔参数。
     * <p>
     * JSON 反序列化后常见 Boolean 实例；缺失时返回默认值。
     */
    static boolean getBoolean(Map<String, Object> args, String key, boolean defaultValue) {
        Object value = args.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return defaultValue;
    }

    /**
     * 读取整数参数。
     * <p>
     * JSON 反序列化后可能是 Integer 或 Long；缺失或类型不匹配时返回 null。
     */
    static Integer getInteger(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return null;
    }
}
