package com.ksyun.agent.runtime.supervisor;

/**
 * Supervisor 模型结构化决策解析器接口。
 * <p>
 * 解析模型返回的严格 JSON 文本为 SupervisorDecisionDraft。
 * 不得调用模型、不得访问 AgentRegistry、不得生成 AgentTask 或 runId、不得执行子 Agent。
 * 不得依赖 Spring 容器。
 */
public interface SupervisorDecisionParser {

    /**
     * 解析模型返回的 JSON 文本为决策草稿。
     *
     * @param content 模型返回的文本内容
     * @return 解析后的决策草稿
     * @throws com.ksyun.agent.core.exception.AgentFrameworkException 解析失败时抛出 MODEL_INVOCATION_FAILED
     */
    SupervisorDecisionDraft parse(String content);
}
