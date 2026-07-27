package com.ksyun.agent.runtime.tool.approval;

import com.ksyun.agent.core.tool.ToolCall;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 工具操作指纹计算。
 * <p>
 * 根据固定顺序计算 SHA-256：
 * - runId
 * - toolCallId
 * - toolName
 * - 原始 arguments 的确定性表示
 * <p>
 * 要求：
 * - 同一 ToolCall 稳定产生相同 fingerprint
 * - 名称、ID 或参数改变时 fingerprint 改变
 * - Map key 顺序必须确定
 * - 不引入额外加密依赖
 * - 不记录原始参数
 * - fingerprint 只用于绑定验证，不是身份凭证
 * <p>
 * 纯 Java 实现，不添加 Spring 注解。
 */
public class ToolOperationFingerprint {

    /**
     * 计算工具操作指纹。
     *
     * @param runId     运行 ID
     * @param toolCall  工具调用
     * @return SHA-256 指纹的十六进制字符串
     */
    public String compute(String runId, ToolCall toolCall) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // 固定顺序：runId -> toolCallId -> toolName -> arguments
            updateString(digest, runId);
            updateString(digest, toolCall.id());
            updateString(digest, toolCall.name());

            // arguments 使用 TreeMap 确定顺序
            Map<String, Object> args = toolCall.arguments();
            if (args != null && !args.isEmpty()) {
                TreeMap<String, Object> sortedArgs = new TreeMap<>(args);
                for (Map.Entry<String, Object> entry : sortedArgs.entrySet()) {
                    updateString(digest, entry.getKey());
                    updateString(digest, String.valueOf(entry.getValue()));
                }
            }

            byte[] hash = digest.digest();
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 在所有 JDK 中都可用
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void updateString(MessageDigest digest, String value) {
        if (value != null) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
        }
        // 分隔符防止拼接歧义
        digest.update((byte) 0x00);
    }
}
