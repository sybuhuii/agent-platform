package com.ksyun.agent.runtime.hitl.node;

import com.ksyun.agent.core.approval.NodeResumeData;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.runtime.react.ReactAgentState;
import com.ksyun.agent.runtime.react.ReactStateKeys;
import org.bsc.langgraph4j.CompiledGraph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 服务端节点恢复处理器白名单。 */
public final class NodeResumeHandlerRegistry {

    private final Map<String, NodeResumeHandler<? extends NodeResumeData>> handlers;

    public NodeResumeHandlerRegistry(
            List<NodeResumeHandler<? extends NodeResumeData>> handlers) {
        Objects.requireNonNull(handlers, "handlers must not be null");
        Map<String, NodeResumeHandler<? extends NodeResumeData>> indexed =
                new LinkedHashMap<>();

        for (NodeResumeHandler<? extends NodeResumeData> handler : handlers) {
            Objects.requireNonNull(handler, "handler must not be null");
            String key = Objects.requireNonNull(handler.key(), "handler key must not be null");
            if (key.isBlank()) {
                throw new IllegalArgumentException("handler key must not be blank");
            }
            if (indexed.putIfAbsent(key, handler) != null) {
                throw new IllegalArgumentException(
                        "Duplicate node resume handler key: " + key);
            }
        }
        this.handlers = Map.copyOf(indexed);
    }

    public Map<String, NodeResumeHandler<? extends NodeResumeData>> handlers() {
        return handlers;
    }

    public void validate(AgentCheckpoint checkpoint) {
        dispatch(checkpoint, false);
    }

    public CompiledGraph<ReactAgentState> compileResumeGraph(AgentCheckpoint checkpoint) {
        return dispatch(checkpoint, true);
    }

    private CompiledGraph<ReactAgentState> dispatch(
            AgentCheckpoint checkpoint,
            boolean compile) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        Object keyValue = checkpoint.stateData().get(ReactStateKeys.NODE_RESUME_HANDLER_KEY);
        Object dataValue = checkpoint.stateData().get(ReactStateKeys.NODE_RESUME_DATA);
        if (!(keyValue instanceof String key) || key.isBlank()
                || !(dataValue instanceof NodeResumeData resumeData)) {
            throw notResumable("Node resume handler data is missing");
        }
        NodeResumeHandler<? extends NodeResumeData> handler = handlers.get(key);
        if (handler == null) {
            throw notResumable("Node resume handler is not registered");
        }
        return dispatchTyped(handler, checkpoint, resumeData, compile);
    }

    private <D extends NodeResumeData> CompiledGraph<ReactAgentState> dispatchTyped(
            NodeResumeHandler<D> handler,
            AgentCheckpoint checkpoint,
            NodeResumeData rawData,
            boolean compile) {
        if (!handler.resumeDataType().isInstance(rawData)) {
            throw notResumable("Node resume data type does not match registered handler");
        }
        D data = handler.resumeDataType().cast(rawData);
        handler.validate(checkpoint, data);
        return compile ? handler.compileResumeGraph(data) : null;
    }

    private AgentFrameworkException notResumable(String message) {
        return new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE, message);
    }
}
