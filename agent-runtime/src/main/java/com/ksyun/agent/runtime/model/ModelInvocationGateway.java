package com.ksyun.agent.runtime.model;

import com.ksyun.agent.core.model.ModelRequest;
import com.ksyun.agent.core.model.ModelResponse;
import com.ksyun.agent.core.run.RunContext;

/**
 * 模型调用网关接口。
 * <p>
 * 本批不要调用 Spring AI。
 */
public interface ModelInvocationGateway {

    /**
     * 通过网关调用模型。
     */
    ModelResponse invoke(ModelRequest request, RunContext context);
}
