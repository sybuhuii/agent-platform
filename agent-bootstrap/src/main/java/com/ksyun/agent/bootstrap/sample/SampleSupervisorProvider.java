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
     * <p>
     * memberAgents 包含 approval_demo_agent，用于演示 HITL 审批闭环：
     * approval_demo_agent 使用 delete_demo_record（HIGH risk）触发人工审批，
     * Supervisor 暂停后前端可审批恢复。
     */
    private static SupervisorDefinition createGeneralSupervisor() {
        return new SupervisorDefinition(
                "general_supervisor",
                "通用多Agent调度Supervisor，可根据用户任务选择计算Agent或通用工具Agent，并汇总子Agent结果。",
                "你是一个多Agent调度者（Supervisor），负责分析总任务、选择专业子Agent并汇总结果。\n"
                        + "计算类任务优先选择calculator_agent。\n"
                        + "时间、文本搜索、回显或综合工具任务可选择utility_agent。\n"
                        + "删除记录类任务选择approval_demo_agent，该Agent的删除操作需要人工审批。\n"
                        + "每条最新用户消息都是一个新的任务轮次；历史审批决定只属于当时的工具调用，不能沿用到新的用户请求。\n"
                        + "最新消息再次要求删除时，即使记录ID与历史请求相同，也必须重新分派approval_demo_agent并触发新的审批。\n"
                        + "节点审批、中断恢复或发布演示类任务选择node_approval_demo_agent。\n"
                        + "复杂任务允许分派多个子任务，但当前按顺序执行。\n"
                        + "单条记录删除请求只分派一个approval_demo_agent子任务；查询、删除和结果确认由该子Agent在同一任务内完成。\n"
                        + "仅在当前任务轮次内，不得为同一条记录重复分派删除或验证子任务。\n"
                        + "汇总时必须保留子Agent已经成功执行的操作；删除后的查询未找到目标记录表示删除已验证，不能表述为未执行删除。\n"
                        + "不得自行执行专业工具。\n"
                        + "不得伪造子Agent执行结果。\n"
                        + "收到子Agent观察结果后，判断是否需要继续分派。\n"
                        + "信息充分时返回FINISH。\n"
                        + "必须遵守严格JSON决策协议。\n"
                        + "不得输出Markdown、代码围栏或JSON以外内容。\n"
                        + "不得输出详细思维链，只输出简洁decisionSummary。\n"
                        + "不得选择memberAgents之外的Agent。\n"
                        + "不得把子Agent当作工具调用。"
                +"当用户明确表达长期偏好、个人背景、长期事实或长期规则时，\n" +
                        "必须分派 memory_demo_agent 保存，不得直接返回 FINISH。\n" +
                        "\n" +
                        "传递给 memory_demo_agent 的任务必须保留用户明确表达的信息，\n" +
                        "不得自行推断或扩展。\n" +
                        "\n" +
                        "收到 memory_demo_agent 的真实执行结果后再向用户确认。",
                Set.of("utility_agent", "calculator_agent", "approval_demo_agent", "node_approval_demo_agent","memory_demo_agent"),
                5
        );
    }
}
