package com.ksyun.agent.core.tool;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

/**
 * 工具执行结果。
 *
 * @param success   是否成功
 * @param content   结果内容
 * @param errorCode 错误码
 * @param metadata  元数据，不可变
 */
public record ToolResult(
        boolean success,
        String content,
        String errorCode,
        Map<String, Object> metadata
) implements Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public ToolResult {
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(metadata);
    }

    /**
     * 创建成功结果。
     */
    public static ToolResult success(String content) {
        return new ToolResult(true, content, null, Map.of());
    }

    /**
     * 创建带元数据的成功结果。
     */
    public static ToolResult success(String content, Map<String, Object> metadata) {
        return new ToolResult(true, content, null, metadata);
    }

    /**
     * 创建失败结果。
     */
    public static ToolResult failure(String errorCode, String content) {
        return new ToolResult(false, content, errorCode, Map.of());
    }

    /**
     * 创建带元数据的失败结果。
     */
    public static ToolResult failure(String errorCode, String content, Map<String, Object> metadata) {
        return new ToolResult(false, content, errorCode, metadata);
    }
}
