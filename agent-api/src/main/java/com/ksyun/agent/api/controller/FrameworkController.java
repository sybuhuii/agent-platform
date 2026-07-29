package com.ksyun.agent.api.controller;

import com.ksyun.agent.api.dto.AgentInfoResponse;
import com.ksyun.agent.api.dto.ContextCapabilityResponse;
import com.ksyun.agent.api.dto.HealthResponse;
import com.ksyun.agent.api.dto.MemoryCapabilityResponse;
import com.ksyun.agent.api.dto.SupervisorInfoResponse;
import com.ksyun.agent.api.dto.ToolInfoResponse;
import com.ksyun.agent.application.framework.FrameworkQueryService;
import com.ksyun.agent.application.framework.FrameworkQueryService.ContextCapabilityInfo;
import com.ksyun.agent.application.framework.FrameworkQueryService.MemoryCapabilityInfo;
import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.core.tool.ToolDefinition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 框架查询 Controller。
 */
@RestController
@RequestMapping("/api/framework")
public class FrameworkController {

    private final FrameworkQueryService frameworkQueryService;

    public FrameworkController(FrameworkQueryService frameworkQueryService) {
        this.frameworkQueryService = frameworkQueryService;
    }

    @GetMapping("/agents")
    public List<AgentInfoResponse> listAgents() {
        return frameworkQueryService.listAgents().stream()
                .map(this::toAgentInfo)
                .toList();
    }

    @GetMapping("/tools")
    public List<ToolInfoResponse> listTools() {
        return frameworkQueryService.listTools().stream()
                .map(this::toToolInfo)
                .toList();
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", "agent-platform");
    }

    @GetMapping("/supervisors")
    public List<SupervisorInfoResponse> listSupervisors() {
        return frameworkQueryService.listSupervisors().stream()
                .map(this::toSupervisorInfo)
                .toList();
    }

    @GetMapping("/context")
    public ContextCapabilityResponse getContextCapability() {
        ContextCapabilityInfo info = frameworkQueryService.getContextCapability();
        return ContextCapabilityResponse.from(info);
    }

    @GetMapping("/memory")
    public MemoryCapabilityResponse getMemoryCapability() {
        MemoryCapabilityInfo info = frameworkQueryService.getMemoryCapability();
        return MemoryCapabilityResponse.from(info);
    }

    private AgentInfoResponse toAgentInfo(AgentDefinition def) {
        return AgentInfoResponse.from(def);
    }

    private ToolInfoResponse toToolInfo(ToolDefinition def) {
        return new ToolInfoResponse(
                def.name(),
                def.description(),
                def.riskLevel()
        );
    }

    private SupervisorInfoResponse toSupervisorInfo(SupervisorDefinition def) {
        return new SupervisorInfoResponse(
                def.name(),
                def.description(),
                def.memberAgents(),
                def.maxIterations()
        );
    }
}
