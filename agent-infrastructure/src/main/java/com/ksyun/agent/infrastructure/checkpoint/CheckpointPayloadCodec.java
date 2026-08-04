package com.ksyun.agent.infrastructure.checkpoint;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.approval.ApprovalDecision;
import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.approval.InterruptPayload;
import com.ksyun.agent.core.approval.NodeResumeData;
import com.ksyun.agent.core.approval.NodeResumeDataCodec;
import com.ksyun.agent.core.approval.OperationType;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.context.ContextProcessingTrace;
import com.ksyun.agent.core.context.ContextTrimDiagnostic;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.MemoryContextAgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.AssistantAgentMessage;
import com.ksyun.agent.core.message.SummaryAgentMessage;
import com.ksyun.agent.core.message.ToolAgentMessage;
import com.ksyun.agent.core.message.UserAgentMessage;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointExecutionType;
import com.ksyun.agent.core.run.CheckpointPurpose;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.sanitizer.SensitiveValueSanitizer;
import com.ksyun.agent.core.supervisor.SupervisorChildExecution;
import com.ksyun.agent.core.supervisor.SupervisorChildExecutionStatus;
import com.ksyun.agent.core.supervisor.SupervisorChildRunLink;
import com.ksyun.agent.core.tool.ToolCall;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.core.tool.ToolRiskLevel;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadConversationState;
import com.ksyun.agent.runtime.context.ContextWindowSnapshot;
import com.ksyun.agent.runtime.supervisor.SupervisorAction;
import com.ksyun.agent.runtime.supervisor.SupervisorDecision;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Checkpoint payload versioned codec.
 * <p>
 * Serializes/deserializes AgentCheckpoint stateData to/from structured JSON
 * with explicit payload version and kind discriminator.
 * <p>
 * Design:
 * - No @JsonTypeInfo, no default typing, no class name serialization
 * - Explicit typed DTOs (inner records) for each payload kind
 * - SensitiveValueSanitizer applied before encoding
 * - String truncation for long content fields
 * - Max payload size: 1MB (1,048,576 bytes)
 * - Immutable collections on decode
 * - Type validation on decode
 * <p>
 * Pure Java (no Spring annotations); uses Jackson ObjectMapper injected via constructor.
 */
public class CheckpointPayloadCodec {

    private static final int CURRENT_PAYLOAD_VERSION = 1;
    private static final int MAX_PAYLOAD_SIZE = 1_048_576; // 1MB
    private static final int MAX_RESULT_CONTENT_LENGTH = 1024;
    private static final int MAX_EVIDENCE_ITEM_LENGTH = 256;
    private static final int MAX_TOOL_RESULT_CONTENT_LENGTH = 1024;
    private static final int MAX_METADATA_VALUE_LENGTH = 256;
    private static final int MAX_CONTEXT_VALUE_LENGTH = 256;

    private static final String KIND_REACT_HITL = "REACT_HITL";
    private static final String KIND_SUPERVISOR_HITL = "SUPERVISOR_HITL";
    private static final String KIND_THREAD_MEMORY_REACT = "THREAD_MEMORY_REACT";
    private static final String KIND_THREAD_MEMORY_SUPERVISOR = "THREAD_MEMORY_SUPERVISOR";

    private final ObjectMapper objectMapper;
    private final SensitiveValueSanitizer sanitizer;
    private final Map<String, NodeResumeDataCodec<?>> resumeDataCodecsByKey;
    private final Map<Class<? extends NodeResumeData>, NodeResumeDataCodec<?>> resumeDataCodecsByClass;

    public CheckpointPayloadCodec(ObjectMapper baseMapper,
                                  SensitiveValueSanitizer sanitizer,
                                  List<NodeResumeDataCodec<?>> resumeDataCodecs) {
        Objects.requireNonNull(baseMapper, "baseMapper must not be null");
        Objects.requireNonNull(sanitizer, "sanitizer must not be null");
        this.sanitizer = sanitizer;
        Map<String, NodeResumeDataCodec<?>> byKey = new LinkedHashMap<>();
        Map<Class<? extends NodeResumeData>, NodeResumeDataCodec<?>> byClass = new LinkedHashMap<>();
        for (NodeResumeDataCodec<?> codec : List.copyOf(resumeDataCodecs)) {
            NodeResumeDataCodec<?> duplicateKey = byKey.putIfAbsent(codec.typeKey(), codec);
            NodeResumeDataCodec<?> duplicateClass = byClass.putIfAbsent(codec.dataType(), codec);
            if (duplicateKey != null || duplicateClass != null) {
                throw new IllegalArgumentException("Duplicate NodeResumeData codec registration: " + codec.typeKey());
            }
        }
        this.resumeDataCodecsByKey = Map.copyOf(byKey);
        this.resumeDataCodecsByClass = Map.copyOf(byClass);

        // Create a safe copy with our modules, without modifying the global mapper
        this.objectMapper = baseMapper.copy()
                .registerModule(new JavaTimeModule())
                .registerModule(new AgentMessageModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    // ========== Public API ==========

    /**
     * Encodes an AgentCheckpoint into a versioned JSON string.
     *
     * @param checkpoint the checkpoint to encode
     * @return JSON string with payloadVersion and payloadKind at top level
     * @throws AgentFrameworkException if payload exceeds 1MB or encoding fails
     */
    public String encode(AgentCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");

        String payloadKind = determinePayloadKind(checkpoint.executionType(), checkpoint.purpose());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payloadVersion", CURRENT_PAYLOAD_VERSION);
        payload.put("payloadKind", payloadKind);

        switch (payloadKind) {
            case KIND_REACT_HITL -> encodeReactHitl(payload, checkpoint);
            case KIND_SUPERVISOR_HITL -> encodeSupervisorHitl(payload, checkpoint);
            case KIND_THREAD_MEMORY_REACT, KIND_THREAD_MEMORY_SUPERVISOR ->
                    encodeThreadMemory(payload, checkpoint);
            default -> throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Unknown payloadKind: " + payloadKind);
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Failed to encode checkpoint payload", e);
        }

        if (json.length() > MAX_PAYLOAD_SIZE) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint payload exceeds 1MB limit (" + json.length() + " bytes)");
        }

        return json;
    }

    /**
     * Decodes a JSON string into an AgentCheckpoint, merging decoded stateData and
     * pendingApproval with the partial checkpoint's top-level fields.
     *
     * @param json             the JSON string persisted in the database
     * @param partialCheckpoint checkpoint providing top-level fields (checkpointId, runId,
     *                          threadId, userId, executionType, purpose, agentName, nodeName,
     *                          status, version, createdAt, updatedAt)
     * @return fully reconstructed AgentCheckpoint
     * @throws AgentFrameworkException if JSON is invalid or version/kind unsupported
     */
    public AgentCheckpoint decode(String json, AgentCheckpoint partialCheckpoint) {
        Objects.requireNonNull(json, "json must not be null");
        Objects.requireNonNull(partialCheckpoint, "partialCheckpoint must not be null");

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Failed to parse checkpoint payload JSON", e);
        }

        int version = root.path("payloadVersion").asInt(-1);
        if (version != CURRENT_PAYLOAD_VERSION) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Unsupported payloadVersion: " + version);
        }

        String payloadKind = root.path("payloadKind").asText("");
        DecodedPayload decoded;

        switch (payloadKind) {
            case KIND_REACT_HITL -> decoded = decodeReactHitl(root);
            case KIND_SUPERVISOR_HITL -> decoded = decodeSupervisorHitl(root);
            case KIND_THREAD_MEMORY_REACT, KIND_THREAD_MEMORY_SUPERVISOR ->
                    decoded = decodeThreadMemory(root);
            default -> throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Unknown payloadKind: " + payloadKind);
        }

        PendingApproval resolvedApproval = decoded.pendingApproval() != null
                ? decoded.pendingApproval()
                : partialCheckpoint.pendingApproval();

        return new AgentCheckpoint(
                partialCheckpoint.checkpointId(),
                partialCheckpoint.runId(),
                partialCheckpoint.threadId(),
                partialCheckpoint.userId(),
                partialCheckpoint.executionType(),
                partialCheckpoint.purpose(),
                partialCheckpoint.agentName(),
                partialCheckpoint.nodeName(),
                decoded.stateData(),
                resolvedApproval,
                partialCheckpoint.status(),
                partialCheckpoint.version(),
                partialCheckpoint.createdAt(),
                partialCheckpoint.updatedAt()
        );
    }

    // ========== Payload Kind Determination ==========

    private String determinePayloadKind(CheckpointExecutionType executionType, CheckpointPurpose purpose) {
        if (executionType == CheckpointExecutionType.REACT_AGENT && purpose == CheckpointPurpose.HITL_RECOVERY) {
            return KIND_REACT_HITL;
        }
        if (executionType == CheckpointExecutionType.SUPERVISOR && purpose == CheckpointPurpose.HITL_RECOVERY) {
            return KIND_SUPERVISOR_HITL;
        }
        if (executionType == CheckpointExecutionType.REACT_AGENT && purpose == CheckpointPurpose.THREAD_MEMORY) {
            return KIND_THREAD_MEMORY_REACT;
        }
        if (executionType == CheckpointExecutionType.SUPERVISOR && purpose == CheckpointPurpose.THREAD_MEMORY) {
            return KIND_THREAD_MEMORY_SUPERVISOR;
        }
        throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                "Cannot determine payloadKind for executionType=" + executionType
                        + ", purpose=" + purpose);
    }

    // ========== REACT_HITL Encode ==========

    @SuppressWarnings("unchecked")
    private void encodeReactHitl(Map<String, Object> payload, AgentCheckpoint checkpoint) {
        Map<String, Object> sd = checkpoint.stateData();

        // task
        Object rawTask = sd.get("task");
        if (rawTask instanceof AgentTask task) {
            payload.put("task", encodeTask(task));
        }

        // messages
        Object rawMessages = sd.get("messages");
        if (rawMessages instanceof List<?> list) {
            payload.put("messages", encodeMessageList((List<AgentMessage>) (List<?>) list));
        }

        // pendingToolCalls
        Object rawPendingToolCalls = sd.get("pendingToolCalls");
        if (rawPendingToolCalls instanceof List<?> list) {
            payload.put("pendingToolCalls", encodeToolCallList((List<ToolCall>) (List<?>) list));
        }

        // toolExecutionCursor
        payload.put("toolExecutionCursor", sd.getOrDefault("toolExecutionCursor", 0));

        // toolExecutionBuffer
        Object rawBuffer = sd.get("toolExecutionBuffer");
        if (rawBuffer instanceof List<?> list) {
            payload.put("toolExecutionBuffer", encodeToolResultList((List<ToolResult>) (List<?>) list));
        }

        // iteration
        payload.put("iteration", sd.getOrDefault("iteration", 0));

        // contextWindowSnapshot
        Object rawSnapshot = sd.get("contextWindowSnapshot");
        if (rawSnapshot instanceof ContextWindowSnapshot snapshot) {
            payload.put("contextWindowSnapshot", encodeContextWindowSnapshot(snapshot));
        }

        // latestContextTrace
        Object rawTrace = sd.get("latestContextTrace");
        if (rawTrace instanceof ContextProcessingTrace trace) {
            payload.put("latestContextTrace", encodeContextProcessingTrace(trace));
        }

        // nodeResumeHandlerKey
        Object handlerKey = sd.get("nodeResumeHandlerKey");
        if (handlerKey != null) {
            payload.put("nodeResumeHandlerKey", handlerKey.toString());
        }

        // nodeResumeData
        Object resumeData = sd.get("nodeResumeData");
        if (resumeData instanceof NodeResumeData nrd) {
            payload.put("nodeResumeData", encodeNodeResumeData(nrd));
        }

        // pendingApproval
        if (checkpoint.pendingApproval() != null) {
            payload.put("pendingApproval", encodePendingApproval(checkpoint.pendingApproval()));
        }
    }

    // ========== SUPERVISOR_HITL Encode ==========

    @SuppressWarnings("unchecked")
    private void encodeSupervisorHitl(Map<String, Object> payload, AgentCheckpoint checkpoint) {
        Map<String, Object> sd = checkpoint.stateData();

        // rootTask
        Object rawRootTask = sd.get("rootTask");
        if (rawRootTask instanceof AgentTask task) {
            payload.put("rootTask", encodeTask(task));
        }

        // supervisorMessages
        Object rawMessages = sd.get("supervisorMessages");
        if (rawMessages instanceof List<?> list) {
            payload.put("supervisorMessages", encodeMessageList((List<AgentMessage>) (List<?>) list));
        }

        // decision
        Object rawDecision = sd.get("decision");
        if (rawDecision instanceof SupervisorDecision decision) {
            payload.put("decision", encodeSupervisorDecision(decision));
        }

        // pendingTasks
        Object rawPendingTasks = sd.get("pendingTasks");
        if (rawPendingTasks instanceof List<?> list) {
            payload.put("pendingTasks", encodeTaskList((List<AgentTask>) (List<?>) list));
        }

        // latestAgentResults
        Object rawLatestResults = sd.get("latestAgentResults");
        if (rawLatestResults instanceof List<?> list) {
            payload.put("latestAgentResults", encodeResultList((List<AgentResult>) (List<?>) list));
        }

        // agentResults
        Object rawAgentResults = sd.get("agentResults");
        if (rawAgentResults instanceof List<?> list) {
            payload.put("agentResults", encodeResultList((List<AgentResult>) (List<?>) list));
        }

        // dispatchTasks
        Object rawDispatchTasks = sd.get("dispatchTasks");
        if (rawDispatchTasks instanceof List<?> list) {
            payload.put("dispatchTasks",
                    encodeChildExecutionList((List<SupervisorChildExecution>) (List<?>) list));
        }

        // suspendedChildren
        Object rawSuspended = sd.get("suspendedChildren");
        if (rawSuspended instanceof List<?> list) {
            payload.put("suspendedChildren",
                    encodeChildExecutionList((List<SupervisorChildExecution>) (List<?>) list));
        }

        // iteration
        payload.put("iteration", sd.getOrDefault("iteration", 0));

        // contextWindowSnapshot
        Object rawSnapshot = sd.get("contextWindowSnapshot");
        if (rawSnapshot instanceof ContextWindowSnapshot snapshot) {
            payload.put("contextWindowSnapshot", encodeContextWindowSnapshot(snapshot));
        }

        // latestContextTrace
        Object rawTrace = sd.get("latestContextTrace");
        if (rawTrace instanceof ContextProcessingTrace trace) {
            payload.put("latestContextTrace", encodeContextProcessingTrace(trace));
        }

        // pendingApproval
        if (checkpoint.pendingApproval() != null) {
            payload.put("pendingApproval", encodePendingApproval(checkpoint.pendingApproval()));
        }
    }

    // ========== THREAD_MEMORY Encode ==========

    private void encodeThreadMemory(Map<String, Object> payload, AgentCheckpoint checkpoint) {
        Map<String, Object> sd = checkpoint.stateData();

        Object rawThreadState = sd.get("threadConversationState");
        if (rawThreadState instanceof ThreadConversationState tcs) {
            Map<String, Object> threadPayload = new LinkedHashMap<>();
            threadPayload.put("executionType", tcs.executionType().name());
            threadPayload.put("participantName", tcs.participantName());
            threadPayload.put("messages", encodeMessageList(tcs.messages()));

            if (tcs.contextWindowSnapshot().isPresent()) {
                threadPayload.put("contextWindowSnapshot",
                        encodeContextWindowSnapshot(tcs.contextWindowSnapshot().get()));
            }

            if (tcs.latestContextTrace().isPresent()) {
                threadPayload.put("latestContextTrace",
                        encodeContextProcessingTrace(tcs.latestContextTrace().get()));
            }

            threadPayload.put("lastCompletedRunId", tcs.lastCompletedRunId());
            threadPayload.put("updatedAt", tcs.updatedAt().toString());

            payload.put("threadConversationState", threadPayload);
        }
    }

    // ========== REACT_HITL Decode ==========

    private DecodedPayload decodeReactHitl(JsonNode root) {
        Map<String, Object> sd = new LinkedHashMap<>();

        // task
        JsonNode taskNode = root.get("task");
        if (taskNode != null && !taskNode.isNull()) {
            sd.put("task", decodeTask(taskNode));
        }

        // messages
        JsonNode messagesNode = root.get("messages");
        if (messagesNode != null && messagesNode.isArray()) {
            sd.put("messages", decodeMessageList(messagesNode));
        }

        // pendingToolCalls
        JsonNode pendingToolCallsNode = root.get("pendingToolCalls");
        if (pendingToolCallsNode != null && pendingToolCallsNode.isArray()) {
            sd.put("pendingToolCalls", decodeToolCallList(pendingToolCallsNode));
        }

        // toolExecutionCursor
        sd.put("toolExecutionCursor", root.path("toolExecutionCursor").asInt(0));

        // toolExecutionBuffer
        JsonNode bufferNode = root.get("toolExecutionBuffer");
        if (bufferNode != null && bufferNode.isArray()) {
            sd.put("toolExecutionBuffer", decodeToolResultList(bufferNode));
        }

        // iteration
        sd.put("iteration", root.path("iteration").asInt(0));

        // contextWindowSnapshot
        JsonNode snapshotNode = root.get("contextWindowSnapshot");
        if (snapshotNode != null && !snapshotNode.isNull()) {
            sd.put("contextWindowSnapshot", decodeContextWindowSnapshot(snapshotNode));
        }

        // latestContextTrace
        JsonNode traceNode = root.get("latestContextTrace");
        if (traceNode != null && !traceNode.isNull()) {
            sd.put("latestContextTrace", decodeContextProcessingTrace(traceNode));
        }

        // nodeResumeHandlerKey
        JsonNode handlerKeyNode = root.get("nodeResumeHandlerKey");
        if (handlerKeyNode != null && !handlerKeyNode.isNull()) {
            sd.put("nodeResumeHandlerKey", handlerKeyNode.asText());
        }

        // nodeResumeData
        JsonNode resumeDataNode = root.get("nodeResumeData");
        if (resumeDataNode != null && !resumeDataNode.isNull()) {
            sd.put("nodeResumeData", decodeNodeResumeData(resumeDataNode));
        }

        // pendingApproval
        PendingApproval pendingApproval = null;
        JsonNode approvalNode = root.get("pendingApproval");
        if (approvalNode != null && !approvalNode.isNull()) {
            pendingApproval = decodePendingApproval(approvalNode);
        }

        return new DecodedPayload(Collections.unmodifiableMap(sd), pendingApproval);
    }

    // ========== SUPERVISOR_HITL Decode ==========

    private DecodedPayload decodeSupervisorHitl(JsonNode root) {
        Map<String, Object> sd = new LinkedHashMap<>();

        // rootTask
        JsonNode rootTaskNode = root.get("rootTask");
        if (rootTaskNode != null && !rootTaskNode.isNull()) {
            sd.put("rootTask", decodeTask(rootTaskNode));
        }

        // supervisorMessages
        JsonNode messagesNode = root.get("supervisorMessages");
        if (messagesNode != null && messagesNode.isArray()) {
            sd.put("supervisorMessages", decodeMessageList(messagesNode));
        }

        // decision
        JsonNode decisionNode = root.get("decision");
        if (decisionNode != null && !decisionNode.isNull()) {
            sd.put("decision", decodeSupervisorDecision(decisionNode));
        }

        // pendingTasks
        JsonNode pendingTasksNode = root.get("pendingTasks");
        if (pendingTasksNode != null && pendingTasksNode.isArray()) {
            sd.put("pendingTasks", decodeTaskList(pendingTasksNode));
        }

        // latestAgentResults
        JsonNode latestResultsNode = root.get("latestAgentResults");
        if (latestResultsNode != null && latestResultsNode.isArray()) {
            sd.put("latestAgentResults", decodeResultList(latestResultsNode));
        }

        // agentResults
        JsonNode agentResultsNode = root.get("agentResults");
        if (agentResultsNode != null && agentResultsNode.isArray()) {
            sd.put("agentResults", decodeResultList(agentResultsNode));
        }

        // dispatchTasks
        JsonNode dispatchTasksNode = root.get("dispatchTasks");
        if (dispatchTasksNode != null && dispatchTasksNode.isArray()) {
            sd.put("dispatchTasks", decodeChildExecutionList(dispatchTasksNode));
        }

        // suspendedChildren
        JsonNode suspendedNode = root.get("suspendedChildren");
        if (suspendedNode != null && suspendedNode.isArray()) {
            sd.put("suspendedChildren", decodeChildExecutionList(suspendedNode));
        }

        // iteration
        sd.put("iteration", root.path("iteration").asInt(0));

        // contextWindowSnapshot
        JsonNode snapshotNode = root.get("contextWindowSnapshot");
        if (snapshotNode != null && !snapshotNode.isNull()) {
            sd.put("contextWindowSnapshot", decodeContextWindowSnapshot(snapshotNode));
        }

        // latestContextTrace
        JsonNode traceNode = root.get("latestContextTrace");
        if (traceNode != null && !traceNode.isNull()) {
            sd.put("latestContextTrace", decodeContextProcessingTrace(traceNode));
        }

        // pendingApproval
        PendingApproval pendingApproval = null;
        JsonNode approvalNode = root.get("pendingApproval");
        if (approvalNode != null && !approvalNode.isNull()) {
            pendingApproval = decodePendingApproval(approvalNode);
        }

        return new DecodedPayload(Collections.unmodifiableMap(sd), pendingApproval);
    }

    // ========== THREAD_MEMORY Decode ==========

    private DecodedPayload decodeThreadMemory(JsonNode root) {
        JsonNode tcsNode = root.get("threadConversationState");
        if (tcsNode == null || tcsNode.isNull()) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Missing threadConversationState in THREAD_MEMORY payload");
        }

        CheckpointExecutionType execType;
        try {
            execType = CheckpointExecutionType.valueOf(tcsNode.path("executionType").asText());
        } catch (IllegalArgumentException e) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Invalid executionType in threadConversationState", e);
        }

        String participantName = tcsNode.path("participantName").asText("");
        List<AgentMessage> messages = decodeMessageList(tcsNode.path("messages"));

        Optional<ContextWindowSnapshot> snapshotOpt = Optional.empty();
        JsonNode snapshotNode = tcsNode.get("contextWindowSnapshot");
        if (snapshotNode != null && !snapshotNode.isNull()) {
            snapshotOpt = Optional.of(decodeContextWindowSnapshot(snapshotNode));
        }

        Optional<ContextProcessingTrace> traceOpt = Optional.empty();
        JsonNode traceNode = tcsNode.get("latestContextTrace");
        if (traceNode != null && !traceNode.isNull()) {
            traceOpt = Optional.of(decodeContextProcessingTrace(traceNode));
        }

        String lastCompletedRunId = tcsNode.path("lastCompletedRunId").asText("");
        Instant updatedAt = Instant.parse(tcsNode.path("updatedAt").asText());

        ThreadConversationState tcs = new ThreadConversationState(
                execType, participantName, messages,
                snapshotOpt, traceOpt,
                lastCompletedRunId, updatedAt
        );

        Map<String, Object> sd = new LinkedHashMap<>();
        sd.put("threadConversationState", tcs);

        return new DecodedPayload(Collections.unmodifiableMap(sd), null);
    }

    // ========== Domain Encoding Helpers ==========

    private Map<String, Object> encodeTask(AgentTask task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", task.taskId());
        m.put("agentName", task.agentName());
        m.put("instruction", task.instruction());
        m.put("context", sanitizeAndTruncateMap(task.context(), MAX_CONTEXT_VALUE_LENGTH));
        return m;
    }

    private List<Map<String, Object>> encodeMessageList(List<AgentMessage> messages) {
        List<Map<String, Object>> result = new ArrayList<>(messages.size());
        for (AgentMessage msg : messages) {
            result.add(encodeMessage(msg));
        }
        return result;
    }

    private Map<String, Object> encodeMessage(AgentMessage msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (msg instanceof UserAgentMessage u) {
            m.put("type", "USER");
            m.put("content", u.content());
        } else if (msg instanceof AssistantAgentMessage a) {
            m.put("type", "ASSISTANT");
            m.put("content", a.content());
            m.put("toolCalls", encodeToolCallList(a.toolCalls()));
        } else if (msg instanceof ToolAgentMessage t) {
            m.put("type", "TOOL");
            m.put("toolCallId", t.toolCallId());
            m.put("toolName", t.toolName());
            m.put("content", t.content());
            m.put("error", t.error());
        } else if (msg instanceof SummaryAgentMessage s) {
            m.put("type", "SUMMARY");
            m.put("content", s.content());
            m.put("generatedAt", s.generatedAt().toString());
        } else {
            // Unknown or unsupported type — mark and skip details.
            // SystemAgentMessage / MemoryContextAgentMessage should never reach here
            // due to runtime whitelist filtering.
            m.put("type", "UNKNOWN");
        }
        return m;
    }

    private List<Map<String, Object>> encodeToolCallList(List<ToolCall> toolCalls) {
        List<Map<String, Object>> result = new ArrayList<>(toolCalls.size());
        for (ToolCall tc : toolCalls) {
            result.add(encodeToolCall(tc));
        }
        return result;
    }

    private Map<String, Object> encodeToolCall(ToolCall tc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", tc.id());
        m.put("name", tc.name());
        m.put("arguments", sanitizer.sanitize(tc.arguments()));
        return m;
    }

    private List<Map<String, Object>> encodeToolResultList(List<ToolResult> results) {
        List<Map<String, Object>> list = new ArrayList<>(results.size());
        for (ToolResult tr : results) {
            list.add(encodeToolResult(tr));
        }
        return list;
    }

    private Map<String, Object> encodeToolResult(ToolResult tr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", tr.success());
        m.put("content", truncate(tr.content(), MAX_TOOL_RESULT_CONTENT_LENGTH));
        m.put("errorCode", tr.errorCode());
        m.put("metadata", sanitizeAndTruncateMap(tr.metadata(), MAX_METADATA_VALUE_LENGTH));
        return m;
    }

    private Map<String, Object> encodeSupervisorDecision(SupervisorDecision decision) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", decision.action().name());
        m.put("tasks", encodeTaskList(decision.tasks()));
        m.put("decisionSummary", decision.decisionSummary());
        m.put("finalAnswer", decision.finalAnswer());
        return m;
    }

    private List<Map<String, Object>> encodeTaskList(List<AgentTask> tasks) {
        List<Map<String, Object>> result = new ArrayList<>(tasks.size());
        for (AgentTask task : tasks) {
            result.add(encodeTask(task));
        }
        return result;
    }

    private List<Map<String, Object>> encodeResultList(List<AgentResult> results) {
        List<Map<String, Object>> list = new ArrayList<>(results.size());
        for (AgentResult ar : results) {
            list.add(encodeAgentResult(ar));
        }
        return list;
    }

    private Map<String, Object> encodeAgentResult(AgentResult ar) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentName", ar.agentName());
        m.put("success", ar.success());
        m.put("content", truncate(ar.content(), MAX_RESULT_CONTENT_LENGTH));
        m.put("evidence", truncateStringList(ar.evidence(), MAX_EVIDENCE_ITEM_LENGTH));
        m.put("metadata", sanitizeAndTruncateMap(ar.metadata(), MAX_METADATA_VALUE_LENGTH));
        m.put("errorCode", ar.errorCode());
        m.put("status", ar.status().name());
        return m;
    }

    private List<Map<String, Object>> encodeChildExecutionList(List<SupervisorChildExecution> children) {
        List<Map<String, Object>> list = new ArrayList<>(children.size());
        for (SupervisorChildExecution ce : children) {
            list.add(encodeChildExecution(ce));
        }
        return list;
    }

    private Map<String, Object> encodeChildExecution(SupervisorChildExecution ce) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task", encodeTask(ce.task()));
        m.put("dispatchIndex", ce.dispatchIndex());
        m.put("runLink", ce.runLink() != null ? encodeChildRunLink(ce.runLink()) : null);
        m.put("status", ce.status().name());
        m.put("result", ce.result() != null ? encodeAgentResult(ce.result()) : null);
        m.put("approvalId", ce.approvalId());
        return m;
    }

    private Map<String, Object> encodeChildRunLink(SupervisorChildRunLink link) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("parentRunId", link.parentRunId());
        m.put("parentThreadId", link.parentThreadId());
        m.put("parentTaskId", link.parentTaskId());
        m.put("dispatchBatchId", link.dispatchBatchId());
        m.put("childRunId", link.childRunId());
        m.put("childThreadId", link.childThreadId());
        m.put("childTaskId", link.childTaskId());
        m.put("dispatchIndex", link.dispatchIndex());
        return m;
    }

    private Map<String, Object> encodeContextWindowSnapshot(
            ContextWindowSnapshot snapshot
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();

        List<AgentMessage> safeWindowMessages = snapshot.windowMessages().stream()
                .filter(message -> !(message instanceof SystemAgentMessage))
                .filter(message -> !(message instanceof MemoryContextAgentMessage))
                .toList();

        payload.put("windowMessages", encodeMessageList(safeWindowMessages));

        /*
         * THREAD_MEMORY 的 consumed 数量仍然表示原完整历史中的位置，
         * 其中包含运行时重建的 System 消息位置。
         * ContextWindowSnapshotRestorer.forThreadContinuation 会补回当前 System 消息，
         * 因此这里不能减少 consumedHistoryMessageCount。
         */
        payload.put("consumedHistoryMessageCount",
                snapshot.consumedHistoryMessageCount());
        payload.put("processingSequence", snapshot.processingSequence());
        payload.put("latestTrace",
                encodeContextProcessingTrace(snapshot.latestTrace()));
        payload.put("updatedAt", snapshot.updatedAt().toString());
        return payload;
    }

    private Map<String, Object> encodeContextProcessingTrace(ContextProcessingTrace trace) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("originalMessageCount", trace.originalMessageCount());
        m.put("processedMessageCount", trace.processedMessageCount());
        m.put("originalTokenCount", trace.originalTokenCount());
        m.put("processedTokenCount", trace.processedTokenCount());
        m.put("effectiveMessageBudget", trace.effectiveMessageBudget());
        m.put("messageCountTrimmed", trace.messageCountTrimmed());
        m.put("tokenTrimmed", trace.tokenTrimmed());
        m.put("summaryTriggered", trace.summaryTriggered());
        m.put("summaryApplied", trace.summaryApplied());
        m.put("summarizedMessageCount", trace.summarizedMessageCount());
        m.put("summarySourceTokenCount", trace.summarySourceTokenCount());
        m.put("summaryTokenCount", trace.summaryTokenCount());
        m.put("withinBudget", trace.withinBudget());
        List<String> diagnostics = new ArrayList<>(trace.diagnostics().size());
        for (ContextTrimDiagnostic d : trace.diagnostics()) {
            diagnostics.add(d.name());
        }
        m.put("diagnostics", diagnostics);
        m.put("processedAt", trace.processedAt().toString());
        return m;
    }

    private Map<String, Object> encodePendingApproval(PendingApproval pa) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("payload", encodeInterruptPayload(pa.payload()));
        m.put("status", pa.status().name());
        m.put("decision", pa.decision() != null ? encodeApprovalDecision(pa.decision()) : null);
        m.put("createdAt", pa.createdAt().toString());
        m.put("updatedAt", pa.updatedAt().toString());
        return m;
    }

    private Map<String, Object> encodeInterruptPayload(InterruptPayload ip) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("approvalId", ip.approvalId());
        m.put("runId", ip.runId());
        m.put("threadId", ip.threadId());
        m.put("userId", ip.userId());
        m.put("agentName", ip.agentName());
        m.put("nodeName", ip.nodeName());
        m.put("reason", ip.reason());
        m.put("operationType", ip.operationType().name());
        m.put("operationName", ip.operationName());
        m.put("safeArguments", ip.safeArguments());
        m.put("riskLevel", ip.riskLevel().name());
        m.put("requestedAt", ip.requestedAt().toString());
        m.put("toolCallId", ip.toolCallId());
        m.put("operationFingerprint", ip.operationFingerprint());
        return m;
    }

    private Map<String, Object> encodeApprovalDecision(ApprovalDecision ad) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("approvalId", ad.approvalId());
        m.put("status", ad.status().name());
        m.put("decidedBy", ad.decidedBy());
        m.put("comment", ad.comment());
        m.put("decidedAt", ad.decidedAt().toString());
        return m;
    }

    private Map<String, Object> encodeNodeResumeData(NodeResumeData nrd) {
        NodeResumeDataCodec<?> codec = resumeDataCodecsByClass.get(nrd.getClass());
        if (codec == null) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "No codec registered for node resume data");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resumeDataType", codec.typeKey());
        m.putAll(encodeWithCodec(codec, nrd));
        return Collections.unmodifiableMap(m);
    }

    private <D extends NodeResumeData> Map<String, Object> encodeWithCodec(
            NodeResumeDataCodec<D> codec, NodeResumeData data) {
        return codec.encode(codec.dataType().cast(data));
    }

    // ========== Domain Decoding Helpers ==========

    private AgentTask decodeTask(JsonNode node) {
        String taskId = node.path("taskId").asText("");
        String agentName = node.path("agentName").asText("");
        String instruction = node.path("instruction").asText("");
        Map<String, Object> context = decodeStringObjectMap(node.path("context"));
        return new AgentTask(taskId, agentName, instruction, context);
    }

    private List<AgentMessage> decodeMessageList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<AgentMessage> messages = new ArrayList<>(node.size());
        for (JsonNode msgNode : node) {
            AgentMessage msg = decodeMessage(msgNode);
            if (msg != null) {
                messages.add(msg);
            }
        }
        return List.copyOf(messages);
    }

    private AgentMessage decodeMessage(JsonNode node) {
        String type = node.path("type").asText("");
        return switch (type) {
            case "USER" -> new UserAgentMessage(node.path("content").asText(""));
            case "ASSISTANT" -> new AssistantAgentMessage(
                    node.path("content").asText(""),
                    decodeToolCallList(node.path("toolCalls")));
            case "TOOL" -> new ToolAgentMessage(
                    node.path("toolCallId").asText(""),
                    node.path("toolName").asText(""),
                    node.path("content").asText(""),
                    node.path("error").asBoolean(false));
            case "SUMMARY" -> new SummaryAgentMessage(
                    node.path("content").asText(""),
                    Instant.parse(node.path("generatedAt").asText()));
            default -> null; // Skip unknown types
        };
    }

    private List<ToolCall> decodeToolCallList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<ToolCall> result = new ArrayList<>(node.size());
        for (JsonNode tcNode : node) {
            result.add(decodeToolCall(tcNode));
        }
        return List.copyOf(result);
    }

    private ToolCall decodeToolCall(JsonNode node) {
        String id = node.path("id").asText("");
        String name = node.path("name").asText("");
        Map<String, Object> arguments = decodeStringObjectMap(node.path("arguments"));
        return new ToolCall(id, name, arguments);
    }

    private List<ToolResult> decodeToolResultList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<ToolResult> result = new ArrayList<>(node.size());
        for (JsonNode trNode : node) {
            result.add(decodeToolResult(trNode));
        }
        return List.copyOf(result);
    }

    private ToolResult decodeToolResult(JsonNode node) {
        boolean success = node.path("success").asBoolean(true);
        String content = node.path("content").asText("");
        String errorCode = node.path("errorCode").isNull()
                ? null : node.path("errorCode").asText(null);
        Map<String, Object> metadata = decodeStringObjectMap(node.path("metadata"));
        return new ToolResult(success, content, errorCode, metadata);
    }

    private SupervisorDecision decodeSupervisorDecision(JsonNode node) {
        SupervisorAction action = SupervisorAction.valueOf(node.path("action").asText());
        List<AgentTask> tasks = decodeTaskList(node.path("tasks"));
        String decisionSummary = node.path("decisionSummary").asText("");
        String finalAnswer = node.path("finalAnswer").isNull()
                ? null : node.path("finalAnswer").asText(null);
        return new SupervisorDecision(action, tasks, decisionSummary, finalAnswer);
    }

    private List<AgentTask> decodeTaskList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<AgentTask> result = new ArrayList<>(node.size());
        for (JsonNode taskNode : node) {
            result.add(decodeTask(taskNode));
        }
        return List.copyOf(result);
    }

    private List<AgentResult> decodeResultList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<AgentResult> result = new ArrayList<>(node.size());
        for (JsonNode arNode : node) {
            result.add(decodeAgentResult(arNode));
        }
        return List.copyOf(result);
    }

    private AgentResult decodeAgentResult(JsonNode node) {
        String agentName = node.path("agentName").asText("");
        boolean success = node.path("success").asBoolean(true);
        String content = node.path("content").asText("");
        List<String> evidence = decodeStringList(node.path("evidence"));
        Map<String, Object> metadata = decodeStringObjectMap(node.path("metadata"));
        String errorCode = node.path("errorCode").isNull()
                ? null : node.path("errorCode").asText(null);
        RunStatus status = RunStatus.valueOf(node.path("status").asText("COMPLETED"));
        return new AgentResult(agentName, success, content, evidence, metadata, errorCode, status);
    }

    private List<SupervisorChildExecution> decodeChildExecutionList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<SupervisorChildExecution> result = new ArrayList<>(node.size());
        for (JsonNode ceNode : node) {
            result.add(decodeChildExecution(ceNode));
        }
        return List.copyOf(result);
    }

    private SupervisorChildExecution decodeChildExecution(JsonNode node) {
        AgentTask task = decodeTask(node.path("task"));
        int dispatchIndex = node.path("dispatchIndex").asInt(0);
        SupervisorChildRunLink runLink = null;
        JsonNode runLinkNode = node.get("runLink");
        if (runLinkNode != null && !runLinkNode.isNull()) {
            runLink = decodeChildRunLink(runLinkNode);
        }
        SupervisorChildExecutionStatus status =
                SupervisorChildExecutionStatus.valueOf(node.path("status").asText("NOT_STARTED"));
        AgentResult result = null;
        JsonNode resultNode = node.get("result");
        if (resultNode != null && !resultNode.isNull()) {
            result = decodeAgentResult(resultNode);
        }
        String approvalId = node.path("approvalId").isNull()
                ? null : node.path("approvalId").asText(null);
        return new SupervisorChildExecution(task, dispatchIndex, runLink, status, result, approvalId);
    }

    private SupervisorChildRunLink decodeChildRunLink(JsonNode node) {
        return new SupervisorChildRunLink(
                node.path("parentRunId").asText(""),
                node.path("parentThreadId").asText(""),
                node.path("parentTaskId").asText(""),
                node.path("dispatchBatchId").asText(""),
                node.path("childRunId").asText(""),
                node.path("childThreadId").asText(""),
                node.path("childTaskId").asText(""),
                node.path("dispatchIndex").asInt(0));
    }

    private ContextWindowSnapshot decodeContextWindowSnapshot(JsonNode node) {
        List<AgentMessage> windowMessages = decodeMessageList(node.path("windowMessages"));
        int consumedCount = node.path("consumedHistoryMessageCount").asInt(0);
        int processingSequence = node.path("processingSequence").asInt(1);
        ContextProcessingTrace latestTrace = decodeContextProcessingTrace(node.path("latestTrace"));
        Instant updatedAt = Instant.parse(node.path("updatedAt").asText());
        return new ContextWindowSnapshot(windowMessages, consumedCount, processingSequence,
                latestTrace, updatedAt);
    }

    private ContextProcessingTrace decodeContextProcessingTrace(JsonNode node) {
        Set<ContextTrimDiagnostic> diagnostics = new LinkedHashSet<>();
        JsonNode diagNode = node.path("diagnostics");
        if (diagNode.isArray()) {
            for (JsonNode d : diagNode) {
                diagnostics.add(ContextTrimDiagnostic.valueOf(d.asText()));
            }
        }
        return new ContextProcessingTrace(
                node.path("originalMessageCount").asInt(0),
                node.path("processedMessageCount").asInt(0),
                node.path("originalTokenCount").asInt(0),
                node.path("processedTokenCount").asInt(0),
                node.path("effectiveMessageBudget").asInt(0),
                node.path("messageCountTrimmed").asBoolean(false),
                node.path("tokenTrimmed").asBoolean(false),
                node.path("summaryTriggered").asBoolean(false),
                node.path("summaryApplied").asBoolean(false),
                node.path("summarizedMessageCount").asInt(0),
                node.path("summarySourceTokenCount").asInt(0),
                node.path("summaryTokenCount").asInt(0),
                node.path("withinBudget").asBoolean(true),
                diagnostics,
                Instant.parse(node.path("processedAt").asText()));
    }

    private PendingApproval decodePendingApproval(JsonNode node) {
        InterruptPayload payload = decodeInterruptPayload(node.path("payload"));
        ApprovalStatus status = ApprovalStatus.valueOf(node.path("status").asText());
        ApprovalDecision decision = null;
        JsonNode decisionNode = node.get("decision");
        if (decisionNode != null && !decisionNode.isNull()) {
            decision = decodeApprovalDecision(decisionNode);
        }
        Instant createdAt = Instant.parse(node.path("createdAt").asText());
        Instant updatedAt = Instant.parse(node.path("updatedAt").asText());
        return new PendingApproval(payload, status, decision, createdAt, updatedAt);
    }

    private InterruptPayload decodeInterruptPayload(JsonNode node) {
        Map<String, Object> safeArguments = decodeStringObjectMap(node.path("safeArguments"));
        return new InterruptPayload(
                node.path("approvalId").asText(""),
                node.path("runId").asText(""),
                node.path("threadId").asText(""),
                node.path("userId").asText(""),
                node.path("agentName").asText(""),
                node.path("nodeName").asText(""),
                node.path("reason").asText(""),
                OperationType.valueOf(node.path("operationType").asText("TOOL")),
                node.path("operationName").asText(""),
                safeArguments,
                ToolRiskLevel.valueOf(node.path("riskLevel").asText("HIGH")),
                Instant.parse(node.path("requestedAt").asText()),
                node.path("toolCallId").isNull() ? null : node.path("toolCallId").asText(null),
                node.path("operationFingerprint").isNull()
                        ? null : node.path("operationFingerprint").asText(null));
    }

    private ApprovalDecision decodeApprovalDecision(JsonNode node) {
        return new ApprovalDecision(
                node.path("approvalId").asText(""),
                ApprovalStatus.valueOf(node.path("status").asText()),
                node.path("decidedBy").asText(""),
                node.path("comment").asText(""),
                Instant.parse(node.path("decidedAt").asText()));
    }

    private NodeResumeData decodeNodeResumeData(JsonNode node) {
        String resumeDataType = node.path("resumeDataType").asText("");
        NodeResumeDataCodec<?> codec = resumeDataCodecsByKey.get(resumeDataType);
        if (codec == null) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Unknown nodeResumeData type: " + resumeDataType);
        }
        try {
            Map<String, Object> fields = new LinkedHashMap<>(decodeStringObjectMap(node));
            fields.remove("resumeDataType");
            return codec.decode(Collections.unmodifiableMap(fields));
        } catch (RuntimeException e) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Invalid nodeResumeData payload for type: " + resumeDataType, e);
        }
    }

    // ========== Utility Methods ==========

    /**
     * Returns the current payload version written into the payload_version column.
     */
    public int currentPayloadVersion() {
        return CURRENT_PAYLOAD_VERSION;
    }

    /**
     * Returns the payload kind discriminator for the given checkpoint shape.
     */
    public String currentPayloadKind(CheckpointExecutionType executionType,
                                     CheckpointPurpose purpose) {
        return determinePayloadKind(executionType, purpose);
    }

    /**
     * Encodes a PendingApproval into a JSON string for the pending_approval column.
     * <p>
     * This is a convenience method for stores that persist pendingApproval in a
     * separate column. The full {@link #encode(AgentCheckpoint)} also embeds the
     * pendingApproval inside the payload JSON.
     *
     * @param pendingApproval the approval to encode
     * @return JSON string
     */
    public String encodeApproval(PendingApproval pendingApproval) {
        Objects.requireNonNull(pendingApproval, "pendingApproval must not be null");
        try {
            return objectMapper.writeValueAsString(encodePendingApproval(pendingApproval));
        } catch (JsonProcessingException e) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Failed to encode pendingApproval", e);
        }
    }

    /** Encodes the already-sanitized approval argument summary as a JSON object. */
    public String encodeSafeArguments(Map<String, Object> safeArguments) {
        try {
            return objectMapper.writeValueAsString(
                    sanitizeAndTruncateMap(safeArguments, MAX_METADATA_VALUE_LENGTH));
        } catch (JsonProcessingException e) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Failed to encode approval argument summary", e);
        }
    }

    /**
     * Decodes a PendingApproval from a JSON string (pending_approval column).
     *
     * @param json the JSON string
     * @return decoded PendingApproval
     * @throws AgentFrameworkException if JSON is invalid
     */
    public PendingApproval decodeApproval(String json) {
        Objects.requireNonNull(json, "json must not be null");
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Failed to parse pendingApproval JSON", e);
        }
        return decodePendingApproval(node);
    }

    private Map<String, Object> decodeStringObjectMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw =
                    objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> copy = new HashMap<>(raw.size());
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                copy.put(entry.getKey(), deepImmutableCopy(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Object deepImmutableCopy(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> copy = new HashMap<>(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String key) {
                    copy.put(key, deepImmutableCopy(e.getValue()));
                }
            }
            return Collections.unmodifiableMap(copy);
        } else if (value instanceof List<?> l) {
            List<Object> copy = new ArrayList<>(l.size());
            for (Object item : l) {
                copy.add(deepImmutableCopy(item));
            }
            return List.copyOf(copy);
        }
        return value;
    }

    private List<String> decodeStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            result.add(item.asText(""));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> sanitizeAndTruncateMap(Map<String, Object> map, int maxValueLength) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = sanitizer.sanitize(map);
        Map<String, Object> result = new LinkedHashMap<>(sanitized.size());
        for (Map.Entry<String, Object> entry : sanitized.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s && s.length() > maxValueLength) {
                result.put(entry.getKey(), s.substring(0, maxValueLength) + "...(truncated)");
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private List<String> truncateStringList(List<String> list, int maxItemLength) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(list.size());
        for (String s : list) {
            result.add(truncate(s, maxItemLength));
        }
        return Collections.unmodifiableList(result);
    }

    private static String truncate(String s, int maxLength) {
        if (s == null) {
            return null;
        }
        if (s.length() <= maxLength) {
            return s;
        }
        return s.substring(0, maxLength) + "...(truncated)";
    }

    // ========== Inner DTOs ==========

    private record DecodedPayload(Map<String, Object> stateData, PendingApproval pendingApproval) {}

    // ========== Jackson Module for AgentMessage ==========

    /**
     * Custom Jackson module for serializing/deserializing AgentMessage subtypes
     * using the "type" discriminator field, without @JsonTypeInfo or default typing.
     * <p>
     * The codec performs explicit per-subtype mapping via {@link #encodeMessage} and
     * {@link #decodeMessage}; this module is registered to keep the ObjectMapper aware
     * of the AgentMessage type hierarchy and is a hook for future typed serializers.
     */
    private static class AgentMessageModule extends SimpleModule {

        private AgentMessageModule() {
            super("AgentMessageModule");
        }
    }
}
