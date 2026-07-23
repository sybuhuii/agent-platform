package com.ksyun.agent.bootstrap.sample;

import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.core.supervisor.SupervisorProvider;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 仅用于框架演示的 Sample Supervisor Provider。
 * <p>
 * 只提供 SupervisorDefinition，不实现 Supervisor 运行逻辑。
 * 通过已有 SupervisorProviderRegistrar 自动注册。
 * 不添加 @Component，由 SampleAgentConfiguration 显式创建 Bean。
 * 不得直接调用 SupervisorRegistry.register。
 * 不得依赖 SupervisorEngine、ModelInvocationGateway 或 ReactAgentEngine。
 */
public class SampleSupervisorProvider implements SupervisorProvider {

    private static final Collection<SupervisorDefinition> SUPERVISORS = List.of(
            createGeneralSupervisor()
    );

    @Override
    public Collection<SupervisorDefinition> provideSupervisors() {
        return SUPERVISORS;
    }

    /**
     * general_supervisor：通用多Agent调度Supervisor。
     * <p>
     * maxIterations=5：典型两轮调度需4次Reason（DISPATCH+Aggregate各一轮），
     * 5次上限留有余量且防止无限循环。
     */
    private static SupervisorDefinition createGeneralSupervisor() {
        return new SupervisorDefinition(
                "general_supervisor",
                "通用多Agent调度Supervisor，可根据用户任务选择计算Agent或通用工具Agent，并汇总子Agent结果。",
                "你是一个多Agent调度者（Supervisor），负责分析总任务、选择专业子Agent并汇总结果。\n"
                        + "计算类任务优先选择calculator_agent。\n"
                        + "时间、文本搜索、回显或综合工具任务可选择utility_agent。\n"
                        + "复杂任务允许分派多个子任务，但当前按顺序执行。\n"
                        + "不得自行执行专业工具。\n"
                        + "不得伪造子Agent执行结果。\n"
                        + "收到子Agent观察结果后，判断是否需要继续分派。\n"
                        + "信息充分时返回FINISH。\n"
                        + "必须遵守严格JSON决策协议。\n"
                        + "不得输出Markdown、代码围栏或JSON以外内容。\n"
                        + "不得输出详细思维链，只输出简洁decisionSummary。\n"
                        + "不得选择memberAgents之外的Agent。\n"
                        + "不得把子Agent当作工具调用。",
                Set.of("utility_agent", "calculator_agent"),
                5
        );
    }
}
