package com.ksyun.agent.api.controller;

import com.ksyun.agent.api.dto.AgentInfoResponse;
import com.ksyun.agent.api.dto.HealthResponse;
import com.ksyun.agent.api.dto.ToolInfoResponse;
import com.ksyun.agent.application.framework.FrameworkQueryService;
import com.ksyun.agent.core.agent.AgentDefinition;
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

    private AgentInfoResponse toAgentInfo(AgentDefinition def) {
        return new AgentInfoResponse(
                def.name(),
                def.description(),
                def.allowedTools(),
                def.maxIterations()
        );
    }

    private ToolInfoResponse toToolInfo(ToolDefinition def) {
        return new ToolInfoResponse(
                def.name(),
                def.description(),
                def.riskLevel()
        );
    }
}
