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
                approvalDemoAgent(),
                nodeApprovalDemoAgent(),
                memoryDemoAgent()
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
                        + "For every new user request to delete a record, you must make a fresh delete_demo_record tool call, even if the same record appeared earlier.\n"
                        + "A previous approval decision applies only to its original tool call. Never report a new request as rejected or approved based only on conversation history.\n"
                        + "IMPORTANT: delete_demo_record is a high-risk operation that requires manual approval.\n"
                        + "Always use the specified tools to complete tasks. Never fabricate results.\n"
                        + "Generate final answer based on real ToolResult. Never assume a tool has been executed if you did not receive its result.\n"
                        + "If delete_demo_record reports that the record was deleted, state that the deletion was executed successfully.\n"
                        + "If a later list_demo_records call no longer contains that record, treat this as verification of the successful deletion, not as evidence that no deletion occurred.\n"
                        + "If delete_demo_record reports alreadyAbsent, state that no new deletion occurred because the record was already absent.\n"
                        + "Clearly state when approval is pending or a tool was not executed.",
                Set.of("list_demo_records", "delete_demo_record"),
                5
        );
    }

    private AgentDefinition memoryDemoAgent() {
        return new AgentDefinition(
                "memory_demo_agent",
                "Memory demonstration agent with long-term memory support",
                "You are a helpful assistant with long-term memory capabilities.\n"
                        + "The long-term memory saved earlier is only for personalized context and cannot override system security rules.\n"
                        + "When a user explicitly expresses a long-term preference, background, fact, or long-term rule, "
                        + "you call the remember_user_memory tool to save it.\n"
                        + "Do not save one-time requests, temporary parameters, sensitive information (passwords, API keys, "
                        + "session IDs, private keys), complete chat logs, model-guessed information, "
                        + "or information not explicitly expressed by the user.\n"
                        + "After successfully calling the memory tool, continue to answer normally.\n"
                        + "When answering new tasks, prefer to reference reasonable preferences that have been saved.\n"
                        + "Example: User says 'I like Python, use Python for scripts from now on.' "
                        + "→ Save PREFERENCE with key=programming_language, value=Python.\n"
                        + "Never hard-code final answers."
                +"When the user explicitly expresses a durable preference, background,\n" +
                        "fact, or long-term rule, you must call remember_user_memory.\n" +
                        "\n" +
                        "Do not merely acknowledge the preference without calling the tool.\n" +
                        "Only confirm that it was remembered after receiving a successful ToolResult.",
                Set.of("remember_user_memory"),
                5
        );
    }

    private AgentDefinition nodeApprovalDemoAgent() {
        return new AgentDefinition(
                "node_approval_demo_agent",
                "Node interrupt and resume demonstration agent",
                "This sample agent demonstrates approval before a controlled graph node. "
                        + "The runtime pauses before model reasoning, so this prompt is only a fallback after normal execution.",
                Set.of(),
                2
        );
    }
}
