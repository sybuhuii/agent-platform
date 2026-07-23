package com.ksyun.agent.runtime.supervisor;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.runtime.registry.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Supervisor 系统提示词构造器，纯 Java 实现。
 * <p>
 * 根据 SupervisorDefinition 构造 Supervisor 系统提示词。
 * 保持无状态和线程安全。
 */
public class SupervisorPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(SupervisorPromptBuilder.class);

    private final AgentRegistry agentRegistry;

    public SupervisorPromptBuilder(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    /**
     * 构造 Supervisor 系统提示词。
     *
     * @param definition Supervisor 定义
     * @return 系统提示词，不能为空
     */
    public String build(SupervisorDefinition definition) {
        StringBuilder sb = new StringBuilder();

        // 业务自定义前缀
        if (definition.systemPrompt() != null && !definition.systemPrompt().isBlank()) {
            sb.append(definition.systemPrompt()).append("\n\n");
        }

        // 角色说明
        sb.append("你是一个多Agent调度者（Supervisor），负责分析任务、选择专业子Agent并汇总结果。\n\n");

        // 可用子 Agent 列表
        sb.append("可用子Agent：\n");
        Set<String> memberAgents = definition.memberAgents();
        for (String agentName : memberAgents) {
            AgentDefinition agentDef = agentRegistry.getRequired(agentName);
            sb.append("- ").append(agentName).append(": ").append(agentDef.description()).append("\n");
        }
        sb.append("\n");

        // 严格 JSON 输出协议
        sb.append("你必须输出单个严格JSON对象，不得输出任何JSON之外的内容。\n\n");

        sb.append("需要分派子Agent时，输出：\n");
        sb.append("""
                {
                  "action": "DISPATCH",
                  "tasks": [
                    {
                      "agentName": "子Agent名称",
                      "instruction": "给子Agent的具体指令",
                      "context": {}
                    }
                  ],
                  "decisionSummary": "简洁决策依据",
                  "finalAnswer": ""
                }
                """).append("\n");

        sb.append("子Agent结果足够回答用户时，输出：\n");
        sb.append("""
                {
                  "action": "FINISH",
                  "tasks": [],
                  "decisionSummary": "简洁决策依据",
                  "finalAnswer": "最终回答"
                }
                """).append("\n");

        // 约束规则
        sb.append("规则：\n");
        sb.append("1. action只能是DISPATCH或FINISH。\n");
        sb.append("2. DISPATCH时tasks至少一个，且只能选择上述可用子Agent。\n");
        sb.append("3. FINISH时tasks必须为空，finalAnswer不能为空。\n");
        sb.append("4. 不得伪造子Agent执行结果。\n");
        sb.append("5. 不得调用工具。\n");
        sb.append("6. 不得输出Markdown、代码围栏或JSON之外的文字。\n");
        sb.append("7. 不得输出隐藏推理过程，只输出简洁decisionSummary。\n");
        sb.append("8. 每个子Agent任务的context只能是普通JSON对象。\n");

        String result = sb.toString();
        if (result.isBlank()) {
            throw new IllegalStateException("SupervisorPromptBuilder produced empty prompt");
        }
        return result;
    }
}
