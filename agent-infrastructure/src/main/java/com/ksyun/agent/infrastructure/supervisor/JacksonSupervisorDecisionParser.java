package com.ksyun.agent.infrastructure.supervisor;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.runtime.supervisor.SupervisorAction;
import com.ksyun.agent.runtime.supervisor.SupervisorDecisionDraft;
import com.ksyun.agent.runtime.supervisor.SupervisorDecisionParser;
import com.ksyun.agent.runtime.supervisor.SupervisorTaskDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 基于 Jackson 的 Supervisor 决策解析器。
 * <p>
 * 放在 agent-infrastructure 中，不得在 agent-runtime 直接依赖 Jackson。
 * 保持无状态和线程安全。
 */
public class JacksonSupervisorDecisionParser implements SupervisorDecisionParser {

    private static final Logger log = LoggerFactory.getLogger(JacksonSupervisorDecisionParser.class);

    private static final int MAX_CONTENT_LENGTH = 65536;
    private static final int MAX_TASKS = 10;

    private final ObjectMapper objectMapper;

    public JacksonSupervisorDecisionParser(ObjectMapper globalObjectMapper) {
        // 创建安全副本，不修改全局 ObjectMapper 配置
        this.objectMapper = globalObjectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    public SupervisorDecisionDraft parse(String content) {
        if (content == null || content.isBlank()) {
            log.warn("SupervisorDecisionParser: empty content");
            throw new AgentFrameworkException(
                    AgentErrorCode.MODEL_INVOCATION_FAILED,
                    "Supervisor model returned empty response"
            );
        }

        if (content.length() > MAX_CONTENT_LENGTH) {
            log.warn("SupervisorDecisionParser: content length {} exceeds limit", content.length());
            throw new AgentFrameworkException(
                    AgentErrorCode.MODEL_INVOCATION_FAILED,
                    "Supervisor model response exceeds maximum length"
            );
        }

        String json = stripCodeFence(content);

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("SupervisorDecisionParser: JSON parse error");
            throw new AgentFrameworkException(
                    AgentErrorCode.MODEL_INVOCATION_FAILED,
                    "Supervisor model returned invalid JSON"
            );
        }

        if (root == null || !root.isObject()) {
            log.warn("SupervisorDecisionParser: root is not a JSON object");
            throw new AgentFrameworkException(
                    AgentErrorCode.MODEL_INVOCATION_FAILED,
                    "Supervisor model returned non-object JSON"
            );
        }

        // action
        SupervisorAction action = parseAction(root);

        // tasks
        List<SupervisorTaskDraft> tasks = parseTasks(root);

        // decisionSummary
        String decisionSummary = parseStringField(root, "decisionSummary", "");

        // finalAnswer
        String finalAnswer = parseStringField(root, "finalAnswer", "");

        return new SupervisorDecisionDraft(action, tasks, decisionSummary, finalAnswer);
    }

    private SupervisorAction parseAction(JsonNode root) {
        JsonNode actionNode = root.get("action");
        if (actionNode == null || !actionNode.isTextual()) {
            log.warn("SupervisorDecisionParser: missing or invalid 'action' field");
            throw new AgentFrameworkException(
                    AgentErrorCode.MODEL_INVOCATION_FAILED,
                    "Supervisor model response missing or invalid 'action' field"
            );
        }
        try {
            return SupervisorAction.valueOf(actionNode.asText());
        } catch (IllegalArgumentException e) {
            log.warn("SupervisorDecisionParser: unknown action discriminator");
            throw new AgentFrameworkException(
                    AgentErrorCode.MODEL_INVOCATION_FAILED,
                    "Supervisor model returned unknown action: " + actionNode.asText()
            );
        }
    }

    private List<SupervisorTaskDraft> parseTasks(JsonNode root) {
        JsonNode tasksNode = root.get("tasks");
        if (tasksNode == null || !tasksNode.isArray()) {
            return List.of();
        }
        if (tasksNode.size() > MAX_TASKS) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MODEL_INVOCATION_FAILED,
                    "Supervisor model returned too many tasks"
            );
        }
        List<SupervisorTaskDraft> tasks = new ArrayList<>();
        for (JsonNode taskNode : tasksNode) {
            tasks.add(parseTaskDraft(taskNode));
        }
        return tasks;
    }

    private SupervisorTaskDraft parseTaskDraft(JsonNode taskNode) {
        if (!taskNode.isObject()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MODEL_INVOCATION_FAILED,
                    "Supervisor model task is not a JSON object"
            );
        }
        String agentName = parseStringField(taskNode, "agentName", null);
        String instruction = parseStringField(taskNode, "instruction", null);
        Map<String, Object> context = parseContextField(taskNode);
        return new SupervisorTaskDraft(agentName, instruction, context);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseContextField(JsonNode taskNode) {
        JsonNode contextNode = taskNode.get("context");
        if (contextNode == null || contextNode.isNull()) {
            return Map.of();
        }
        if (!contextNode.isObject()) {
            return Map.of();
        }
        try {
            return objectMapper.convertValue(contextNode, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String parseStringField(JsonNode node, String fieldName, String defaultValue) {
        JsonNode field = node.get(fieldName);
        if (field == null || !field.isTextual()) {
            return defaultValue;
        }
        return field.asText();
    }

    /**
     * 去除一层完整的 ```json 代码围栏。
     * 不得从任意自然语言中正则提取 JSON 片段。
     */
    private String stripCodeFence(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```json") && trimmed.endsWith("```")) {
            return trimmed.substring(7, trimmed.length() - 3).trim();
        }
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            return trimmed.substring(3, trimmed.length() - 3).trim();
        }
        return trimmed;
    }
}
