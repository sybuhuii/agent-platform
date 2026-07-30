package com.ksyun.agent.infrastructure.tool.builtin;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

/**
 * 内置工具参数读取辅助。
 *
 * 只接受明确、安全的参数类型，不执行任意 toString、
 * 字符串转布尔或可能溢出的 Number.intValue 转换。
 */
final class ToolArgs {

    private ToolArgs() {
    }

    static String getString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof String stringValue
                ? stringValue
                : null;
    }

    /**
     * 参数不存在时返回默认值；参数存在但类型错误时返回 null。
     */
    static String getString(
            Map<String, Object> args,
            String key,
            String defaultValue
    ) {
        if (!args.containsKey(key)) {
            return defaultValue;
        }
        return getString(args, key);
    }

    /**
     * 参数不存在或类型错误时返回 null，由调用工具区分处理。
     */
    static Boolean getBoolean(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof Boolean booleanValue
                ? booleanValue
                : null;
    }

    /**
     * 安全读取32位整数，不允许截断、舍入或溢出。
     */
    static Integer getInteger(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }

        if (value instanceof Integer integerValue) {
            return integerValue;
        }
        if (value instanceof Byte byteValue) {
            return byteValue.intValue();
        }
        if (value instanceof Short shortValue) {
            return shortValue.intValue();
        }
        if (value instanceof Long longValue) {
            try {
                return Math.toIntExact(longValue);
            } catch (ArithmeticException ignored) {
                return null;
            }
        }
        if (value instanceof BigInteger bigIntegerValue) {
            try {
                return bigIntegerValue.intValueExact();
            } catch (ArithmeticException ignored) {
                return null;
            }
        }
        if (value instanceof BigDecimal bigDecimalValue) {
            try {
                return bigDecimalValue.intValueExact();
            } catch (ArithmeticException ignored) {
                return null;
            }
        }

        return null;
    }
}