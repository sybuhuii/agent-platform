package com.ksyun.agent.core.approval;

import com.ksyun.agent.core.tool.ToolRiskLevel;

import java.util.Map;
import java.util.Objects;

public record NodeInterruptRequest(
        String nodeName,
        String operationName,
        String reason,
        ToolRiskLevel riskLevel,
        Map<String, Object> safePayload,
        String resumeHandlerKey,
        NodeResumeData resumeData
) {
    public NodeInterruptRequest {
        Objects.requireNonNull(nodeName, "nodeName must not be null");
        Objects.requireNonNull(operationName, "operationName must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        Objects.requireNonNull(resumeHandlerKey, "resumeHandlerKey must not be null");
        Objects.requireNonNull(resumeData, "resumeData must not be null");
        if (nodeName.isBlank() || operationName.isBlank()
                || reason.isBlank() || resumeHandlerKey.isBlank()) {
            throw new IllegalArgumentException("Node interrupt text fields must not be blank");
        }
        safePayload = safePayload == null ? Map.of() : Map.copyOf(safePayload);
    }
}