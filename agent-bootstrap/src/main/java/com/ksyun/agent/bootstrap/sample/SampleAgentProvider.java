package com.ksyun.agent.bootstrap.sample;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.agent.AgentProvider;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 仅用于框架演示的 Sample Agent Provider。
 * <p>
 * 只返回 AgentDefinition，不实现 Agent 运行逻辑。
 * 通过已有 AgentProviderRegistrar 自动注册。
 * 不添加 @Component，由 SampleAgentConfiguration 显式创建 Bean。
 */
public class SampleAgentProvider implements AgentProvider {

    private static final Collection<AgentDefinition> AGENTS = List.of(
            createUtilityAgent(),
            createCalculatorAgent()
    );

    @Override
    public Collection<AgentDefinition> provideAgents() {
        return AGENTS;
    }

    /**
     * utility_agent：通用工具型 Agent。
     * <p>
     * maxIterations=6：典型单工具调用需2轮Reason，多工具场景可能3-4轮，
     * 6次上限留有余量且防止无限循环。
     */
    private static AgentDefinition createUtilityAgent() {
        return new AgentDefinition(
                "utility_agent",
                "通用工具型Agent，可根据请求使用计算、时间、回显和文本搜索工具。",
                "你是一个通用工具型助手。根据用户任务判断是否需要使用提供的工具。\n"
                        + "需要工具时返回正确的工具调用，不得伪造工具执行结果。\n"
                        + "收到工具结果后，根据真实结果继续推理。\n"
                        + "工具失败时根据失败观察调整策略或向用户解释。\n"
                        + "信息足够时停止调用工具并返回最终回答。\n"
                        + "不要在没有工具结果时声称工具已经执行。",
                Set.of("calculator", "current_time", "echo", "text_search"),
                6
        );
    }

    /**
     * calculator_agent：只负责算术计算任务。
     * <p>
     * maxIterations=4：单次计算需2轮Reason（调用+获取结果），
     * 4次上限允许一次重试且防止无限循环。
     */
    private static AgentDefinition createCalculatorAgent() {
        return new AgentDefinition(
                "calculator_agent",
                "只负责需要算术计算的任务。",
                "你是一个计算助手。涉及算术表达式时必须优先使用calculator工具。\n"
                        + "不得自行编造计算结果。\n"
                        + "收到工具结果后再生成最终回答。\n"
                        + "无法完成非计算任务时明确说明职责范围。",
                Set.of("calculator"),
                4
        );
    }
}
