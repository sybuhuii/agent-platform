package com.ksyun.agent.bootstrap.sample;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.agent.AgentProvider;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Sample Agent Provider.
 * <p>
 * Only returns AgentDefinition, does not implement Agent runtime logic.
 * Registered via AgentProviderRegistrar automatically.
 * <p>
 * 已删除 admin_agent（使用 FileDeleteTool 的模拟删除演示），
 * 替换为 approval_demo_agent（使用 list_demo_records + delete_demo_record）。
 */
public class SampleAgentProvider implements AgentProvider {

    @Override
    public Collection<AgentDefinition> provideAgents() {
        return List.of(
                utilityAgent(),
                calculatorAgent(),
                approvalDemoAgent()
        );
    }

    private AgentDefinition utilityAgent() {
        return new AgentDefinition(
                "utility_agent",
                "General utility agent with basic tools",
                "You are a general assistant. Use calculator for arithmetic, current_time for time, echo for echoing.\n"
                        + "Always use the specified tools to complete tasks, never fabricate results.\n"
                        + "Generate final answer after receiving tool results.\n"
                        + "Clearly state when a task cannot be completed.",
                Set.of("calculator", "current_time", "echo"),
                4
        );
    }

    private AgentDefinition calculatorAgent() {
        return new AgentDefinition(
                "calculator_agent",
                "Calculator assistant agent",
                "You are a calculation assistant. Always use the calculator tool for arithmetic expressions.\n"
                        + "Never fabricate calculation results.\n"
                        + "Generate final answer after receiving tool results.\n"
                        + "Clearly state when a non-calculation task is outside your scope.",
                Set.of("calculator"),
                4
        );
    }

    private AgentDefinition approvalDemoAgent() {
        return new AgentDefinition(
                "approval_demo_agent",
                "Approval demonstration agent with dangerous tool",
                "You are an approval demonstration assistant. You can use list_demo_records to view records and delete_demo_record to delete them.\n"
                        + "When asked to delete a record, you must use the delete_demo_record tool.\n"
                        + "IMPORTANT: delete_demo_record is a high-risk operation that requires manual approval.\n"
                        + "Always use the specified tools to complete tasks. Never fabricate results.\n"
                        + "Generate final answer based on real ToolResult. Never assume a tool has been executed if you did not receive its result.\n"
                        + "Clearly state when approval is pending or a tool was not executed.",
                Set.of("list_demo_records", "delete_demo_record"),
                5
        );
    }
}
