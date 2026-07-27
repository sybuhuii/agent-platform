package com.ksyun.agent.core.sanitizer;

import java.util.Map;

/**
 * 敏感值脱敏器接口。
 * <p>
 * 位于 agent-core，实现位于 agent-infrastructure。
 * <p>
 * 规则：
 * - key 忽略大小写
 * - 至少屏蔽 password、credential、token、apiKey、secret、authorization、sessionId
 * - 敏感值替换为 ***
 * - 限制单个值长度和整体 payload 长度
 * - 不实现复杂反射或通用递归序列化框架
 * - 不记录完整工具参数
 */
public interface SensitiveValueSanitizer {

    /**
     * 对参数 Map 进行脱敏处理。
     * <p>
     * 返回新的不可变 Map，不修改原始输入。
     *
     * @param arguments 原始参数
     * @return 脱敏后的参数
     */
    Map<String, Object> sanitize(Map<String, Object> arguments);
}
