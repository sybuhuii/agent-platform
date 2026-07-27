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
 */
public class SampleAgentProvider implements AgentProvider {

    @Override
    public Collection<AgentDefinition> provideAgents() {
        return List.of(
                utilityAgent(),
                calculatorAgent(),
                adminAgent()
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

    private AgentDefinition adminAgent() {
        return new AgentDefinition(
                "admin_agent",
                "Admin assistant agent with dangerous tools",
                "You are an admin assistant. You can use basic tools and the file_delete tool.\n"
                        + "When asked to delete a file, you must use the file_delete tool.\n"
                        + "Generate final answer after receiving tool results.\n"
                        + "Note: file_delete is a high-risk operation that may require manual approval.",
                Set.of("calculator", "current_time", "echo", "file_delete"),
                4
        );
    }
}
