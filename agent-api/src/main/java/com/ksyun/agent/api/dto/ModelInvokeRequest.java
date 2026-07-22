package com.ksyun.agent.api.dto;

import java.util.List;
import java.util.Map;

/**
 * 开发模型调用请求 DTO。
 * <p>
 * 不允许客户端提交 RunContext、安全身份或自定义工具 Schema。
 */
public record ModelInvokeRequest(
        String message,
        List<String> toolNames,
        Map<String, Object> options
) {
}
