package com.ksyun.agent.runtime.supervisor;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import com.ksyun.agent.core.message.UserAgentMessage;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import org.bsc.langgraph4j.CompiledGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.*;

/**
 * 默认 Supervisor 执行引擎实现。
 * <p>
 * 纯 Java 实现，不添加 Spring 注解。
 * <p>
 * CompiledGraph 生命周期：在构造时编译一次图，保存为 final 字段复用。
 * LangGraph4j 1.8.x CompiledGraph 的节点和边在编译后不可变，
 * invoke 不使用共享可变状态，可安全并发执行。
 */
public class DefaultSupervisorEngine implements SupervisorEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultSupervisorEngine.class);

    private final SupervisorExecutionValidator validator;
    private final SupervisorPromptBuilder promptBuilder;
    private final CompiledGraph<SupervisorAgentState> compiledGraph;

    public DefaultSupervisorEngine(SupervisorExecutionValidator validator,
                                    SupervisorPromptBuilder promptBuilder,
                                    SupervisorGraphFactory graphFactory) {
        this.validator = validator;
        this.promptBuilder = promptBuilder;
        this.compiledGraph = graphFactory.buildGraph();
    }

    @Override
    public AgentResult execute(SupervisorDefinition definition, AgentTask rootTask, RunContext context) {
        // 1. 校验
        validator.validate(definition, rootTask, context);

        // 2. 构造系统提示词
        String systemPrompt = promptBuilder.build(definition);

        // 3. 构造初始 supervisorMessages
        List<AgentMessage> initialMessages = new ArrayList<>();
        initialMessages.add(new SystemAgentMessage(systemPrompt));
        initialMessages.add(new UserAgentMessage(rootTask.instruction()));

        // 4. 构造初始 State
        Map<String, Object> initialState = new HashMap<>();
        initialState.put(SUPERVISOR_DEFINITION, definition);
        initialState.put(ROOT_TASK, rootTask);
        initialState.put(RUN_CONTEXT, context);
        initialState.put(SUPERVISOR_MESSAGES, initialMessages);
        initialState.put(DECISION, null);
        initialState.put(PENDING_TASKS, List.of());
        initialState.put(LATEST_AGENT_RESULTS, List.of());
        initialState.put(AGENT_RESULTS, List.of());
        initialState.put(ITERATION, 0);
        initialState.put(FINAL_RESULT, null);
        initialState.put(STOP_REASON, null);
        initialState.put(FAILURE_MESSAGE, null);
        initialState.put(FAILURE_ERROR_CODE, null);

        // 5. 执行图
        SupervisorAgentState finalState;
        try {
            finalState = compiledGraph.invoke(initialState)
                    .orElseThrow(() -> new AgentFrameworkException(
                            AgentErrorCode.INTERNAL_ERROR,
                            "Supervisor graph execution returned empty state"));
        } catch (AgentFrameworkException e) {
            log.error("Supervisor execution failed: runId={}, supervisor={}, errorCode={}",
                    context.runId(), definition.name(), e.getErrorCode());
            throw e;
        } catch (Exception e) {
            log.error("Supervisor execution unexpected error: runId={}, supervisor={}",
                    context.runId(), definition.name(), e);
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "Supervisor execution failed",
                    e
            );
        }

        // 6. 读取最终结果
        AgentResult finalResult = getFinalResult(finalState);
        if (finalResult == null) {
            log.error("Supervisor execution completed without finalResult: runId={}, supervisor={}",
                    context.runId(), definition.name());
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "Supervisor execution completed without result"
            );
        }

        return finalResult;
    }
}
