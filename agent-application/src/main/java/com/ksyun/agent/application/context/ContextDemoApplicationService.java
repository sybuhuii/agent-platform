package com.ksyun.agent.application.context;

import com.ksyun.agent.core.context.ContextProcessingRequest;
import com.ksyun.agent.core.context.ContextProcessingResult;
import com.ksyun.agent.core.context.ContextTrimDiagnostic;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.model.ModelRequest;
import com.ksyun.agent.core.model.ModelResponse;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.runtime.context.ContextProcessingPipeline;
import com.ksyun.agent.runtime.context.ContextProcessingRequestFactory;
import com.ksyun.agent.runtime.model.ModelInvocationGateway;
import com.ksyun.agent.runtime.run.RunIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 上下文演示应用服务，纯 Java 实现。
 * <p>
 * 依赖：
 * - ContextDemoHistoryFactory
 * - ContextProcessingPipeline
 * - ContextProcessingRequestFactory
 * - ModelInvocationGateway（可选）
 * - RunIdGenerator
 * - Clock
 * <p>
 * 约束：
 * - 不得把完整消息返回前端
 * - 不得把摘要正文返回前端
 * - 不得返回完整 Prompt
 * - 不得把用户身份发送给模型
 * - 不得保存演示历史
 * - 不得写入 CheckpointStore 和 MemoryStore
 * - 每次调用独立
 * - 不得调用 ReAct 工具
 * - 不得创建 Fake 模型结果
 */
public class ContextDemoApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ContextDemoApplicationService.class);

    private final ContextDemoHistoryFactory historyFactory;
    private final ContextProcessingPipeline pipeline;
    private final ContextProcessingRequestFactory requestFactory;
    private final Optional<ModelInvocationGateway> modelGateway;
    private final RunIdGenerator runIdGenerator;
    private final Clock clock;

    public ContextDemoApplicationService(ContextDemoHistoryFactory historyFactory,
                                          ContextProcessingPipeline pipeline,
                                          ContextProcessingRequestFactory requestFactory,
                                          ModelInvocationGateway modelGateway,
                                          RunIdGenerator runIdGenerator,
                                          Clock clock) {
        this.historyFactory = Objects.requireNonNull(historyFactory);
        this.pipeline = Objects.requireNonNull(pipeline);
        this.requestFactory = Objects.requireNonNull(requestFactory);
        this.modelGateway = Optional.ofNullable(modelGateway);
        this.runIdGenerator = Objects.requireNonNull(runIdGenerator);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 执行上下文演示。
     *
     * @param session 已验证的用户会话
     * @param command 演示命令
     * @return 演示结果
     */
    public ContextDemoResult execute(UserSession session, ContextDemoCommand command) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(command, "command must not be null");

        String runId = runIdGenerator.nextRunId();
        log.info("Context demo started: runId={}, userId={}, rounds={}, includeToolInteractions={}, invokeModel={}",
                runId, session.userId(), command.rounds(),
                command.includeToolInteractions(), command.invokeModel());

        // 1. 生成合成长历史
        List<AgentMessage> syntheticHistory = historyFactory.generate(command);

        // 2. 创建 ContextProcessingRequest
        ContextProcessingRequest request = requestFactory.create(syntheticHistory);

        // 3. 调用 ContextProcessingPipeline
        ContextProcessingResult result = pipeline.process(request);

        // 4. 可选模型调用
        boolean modelInvoked = false;
        String modelContent = null;
        String modelErrorCode = null;

        if (command.invokeModel() && modelGateway.isPresent()) {
            try {
                // 使用 processedMessages 构造无工具 ModelRequest
                // 不得把用户身份发送给模型，使用最简 RunContext
                RunContext demoRunContext = new RunContext(
                        "demo-user", "demo-session", "demo-thread", runId,
                        java.util.Set.of(), java.util.Set.of()
                );

                ModelRequest modelRequest = new ModelRequest(
                        result.processedMessages(), List.of(), java.util.Map.of());

                ModelResponse modelResponse = modelGateway.get().invoke(modelRequest, demoRunContext);
                modelInvoked = true;
                if (modelResponse.message() != null) {
                    modelContent = modelResponse.message().content();
                }
            } catch (Exception e) {
                log.error("Context demo model invocation failed: runId={}", runId, e);
                modelErrorCode = AgentErrorCode.MODEL_INVOCATION_FAILED.name();
            }
        } else if (command.invokeModel() && modelGateway.isEmpty()) {
            modelErrorCode = AgentErrorCode.MODEL_NOT_AVAILABLE.name();
        }

        // 5. 构建结果
        List<String> diagCodes = result.diagnostics().stream()
                .map(ContextTrimDiagnostic::name)
                .toList();

        return new ContextDemoResult(
                runId,
                result.originalMessageCount(),
                result.processedMessageCount(),
                (int) Math.min(result.originalTokenCount(), Integer.MAX_VALUE),
                (int) Math.min(result.processedTokenCount(), Integer.MAX_VALUE),
                result.effectiveMessageBudget(),
                result.messageCountTrimmed(),
                result.tokenTrimmed(),
                result.summaryTriggered(),
                result.summaryApplied(),
                result.summarizedMessageCount(),
                diagCodes,
                modelInvoked,
                modelContent,
                modelErrorCode,
                clock.instant()
        );
    }
}
