package com.ksyun.agent.core.model;

/**
 * 模型调用客户端接口。
 * <p>
 * 不得在 core 中出现 ChatClient、ChatModel 等 Spring AI 类型。
 */
public interface ModelClient {

    /**
     * 调用模型生成响应。
     */
    ModelResponse generate(ModelRequest request);
}
