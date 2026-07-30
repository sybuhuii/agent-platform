package com.ksyun.agent.core.tool;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 工具调用请求。
 *
 * @param id        调用 ID
 * @param name      工具名称
 * @param arguments 调用参数，不可变
 */
public record ToolCall(
        String id,
        String name,
        Map<String, Object> arguments
) implements Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public ToolCall {
        arguments = arguments == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
