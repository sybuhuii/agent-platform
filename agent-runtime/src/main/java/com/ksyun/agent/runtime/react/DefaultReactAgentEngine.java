package com.ksyun.agent.runtime.react;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import com.ksyun.agent.core.message.UserAgentMessage;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.run.RunStatus;
import org.bsc.langgraph4j.CompiledGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/**
 * 默认 ReAct 执行引擎实现。
 * <p>
 * 单 Agent ReAct 执行入口。不暴露 LangGraph4j 类型。
 * 纯 Java 实现，不添加 Spring 注解。
 * <p>
 * CompiledGraph 生命周期：在构造时编译一次图，保存为 final 字段复用。
 * LangGraph4j 1.8.x CompiledGraph 的节点和边在编译后不可变，
 * invoke 不使用共享可变状态，可安全并发执行。
 * <p>
 * 保证：
 * - 图进入 SUSPEND 后能读取挂起 finalResult
 * - 不会把 SUSPENDED 转换成 FAILED
 * - 不会因为 Checkpoint 存在而自动恢复
 * - 不会删除 Checkpoint
 * - 不会继续执行后续节点
 * - 不会再次调用模型
 * - 不会再次执行危险工具
 * - 普通 COMPLETE、FAIL 和 MAX_ITERATIONS 行为不变
 */
public class DefaultReactAgentEngine implements ReactAgentEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultReactAgentEngine.class);

    private final ReactExecutionValidator validator;
    private final CompiledGraph<ReactAgentState> compiledGraph;

    public DefaultReactAgentEngine(ReactExecutionValidator validator,
                                    ReactAgentGraphFactory graphFactory) {
        this.validator = validator;
        this.compiledGraph = graphFactory.buildGraph();
    }

    @Override
    public AgentResult execute(AgentDefinition definition, AgentTask task, RunContext context) {
        // 1. 校验请求参数
        validator.validate(definition, task, context);

        // 2. 构造初始 messages
        List<AgentMessage> initialMessages = new ArrayList<>();
        if (definition.systemPrompt() != null && !definition.systemPrompt().isBlank()) {
            initialMessages.add(new SystemAgentMessage(definition.systemPrompt()));
        }
        initialMessages.add(new UserAgentMessage(task.instruction()));

        // 3. 构造初始 State
        Map<String, Object> initialState = new HashMap<>();
        initialState.put(AGENT_DEFINITION, definition);
        initialState.put(TASK, task);
        initialState.put(RUN_CONTEXT, context);
        initialState.put(MESSAGES, initialMessages);
        initialState.put(PENDING_TOOL_CALLS, List.of());
        initialState.put(LATEST_TOOL_RESULTS, List.of());
        initialState.put(TOOL_TRACES, List.of());
        initialState.put(ITERATION, 0);
        initialState.put(FINAL_RESULT, null);
        initialState.put(STOP_REASON, null);
        initialState.put(FAILURE_MESSAGE, null);
        initialState.put(FAILURE_ERROR_CODE, null);
        // Phase6 Batch2 新增初始状态
        initialState.put(TOOL_EXECUTION_CURSOR, 0);
        initialState.put(TOOL_EXECUTION_BUFFER, List.of());
        initialState.put(PENDING_APPROVAL, null);
        initialState.put(CHECKPOINT_ID, null);
        initialState.put(RUN_STATUS, null);

        // 4. 执行图
        ReactAgentState finalState;
        try {
            finalState = compiledGraph.invoke(initialState)
                    .orElseThrow(() -> new AgentFrameworkException(
                            AgentErrorCode.INTERNAL_ERROR,
                            "Graph execution returned empty state"));
        } catch (AgentFrameworkException e) {
            log.error("ReAct execution failed: runId={}, agent={}, errorCode={}",
                    context.runId(), definition.name(), e.getErrorCode());
            throw e;
        } catch (Exception e) {
            log.error("ReAct execution unexpected error: runId={}, agent={}",
                    context.runId(), definition.name(), e);
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "ReAct execution failed",
                    e
            );
        }

        // 5. 读取最终结果
        AgentResult finalResult = getFinalResult(finalState);
        if (finalResult == null) {
            log.error("ReAct execution completed without finalResult: runId={}, agent={}",
                    context.runId(), definition.name());
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "ReAct execution completed without result"
            );
        }

        // SUSPENDED 不转换成 FAILED
        return finalResult;
    }
}
