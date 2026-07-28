package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.context.ContextSummaryRequest;
import com.ksyun.agent.core.context.ContextSummaryResult;
import com.ksyun.agent.core.context.ContextSummarizer;
import com.ksyun.agent.core.context.TokenCounter;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.AssistantAgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import com.ksyun.agent.core.message.SummaryAgentMessage;
import com.ksyun.agent.core.message.UserAgentMessage;
import com.ksyun.agent.core.model.ModelRequest;
import com.ksyun.agent.core.model.ModelResponse;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.runtime.model.ModelInvocationGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * LLM 上下文摘要器，使用模型生成摘要。
 * <p>
 * 依赖：
 * - ModelInvocationGateway
 * - ContextSummaryPromptBuilder
 * - TokenCounter
 * - Clock
 * <p>
 * 流程：
 * 1. 校验 ContextSummaryRequest
 * 2. 构建摘要专用 System 消息
 * 3. 构建包含旧历史的 User 消息
 * 4. 构造 ModelRequest（工具定义集合为空）
 * 5. 通过 ModelInvocationGateway 调用模型
 * 6. 读取模型最终文本
 * 7. 模型返回 ToolCall 时视为无效摘要输出
 * 8. 空文本视为无效输出
 * 9. 创建 SummaryAgentMessage
 * 10. 使用 TokenCounter 计算摘要 Token
 * 11. 超过 maxSummaryTokens 时视为无效输出
 * 12. 返回 ContextSummaryResult
 * <p>
 * 要求：
 * - 一次摘要处理最多调用模型一次
 * - 不无限重试
 * - 不使用 ReAct
 * - 不调用 ToolInvocationGateway
 * - 不修改原消息
 * - 不保存完整历史
 * - 不记录完整 Prompt 和模型响应
 * - 不返回固定摘要
 * - 模型异常保留 cause 并转换为明确摘要错误
 * - 摘要模型调用不得再次进入 ContextProcessingPipeline
 * - 不添加全局递归逻辑
 */
public class LlmContextSummarizer implements ContextSummarizer {

    private static final Logger log = LoggerFactory.getLogger(LlmContextSummarizer.class);

    private final ModelInvocationGateway modelInvocationGateway;
    private final ContextSummaryPromptBuilder promptBuilder;
    private final TokenCounter tokenCounter;
    private final Clock clock;

    public LlmContextSummarizer(ModelInvocationGateway modelInvocationGateway,
                                  ContextSummaryPromptBuilder promptBuilder,
                                  TokenCounter tokenCounter,
                                  Clock clock) {
        this.modelInvocationGateway = Objects.requireNonNull(modelInvocationGateway);
        this.promptBuilder = Objects.requireNonNull(promptBuilder);
        this.tokenCounter = Objects.requireNonNull(tokenCounter);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public ContextSummaryResult summarize(ContextSummaryRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        try {
            // 1. 构建 System 消息
            String systemPrompt = promptBuilder.getSystemPrompt();
            SystemAgentMessage systemMessage = new SystemAgentMessage(systemPrompt);

            // 2. 构建 User 消息（包含历史 + 旧摘要）
            String historyContent = promptBuilder.buildHistoryUserContent(request.sourceMessages());

            // 如果有旧摘要，合并到历史中
            if (request.existingSummary().isPresent()) {
                historyContent = promptBuilder.appendExistingSummary(
                        historyContent, request.existingSummary().get().content());
            }

            UserAgentMessage userMessage = new UserAgentMessage(historyContent);

            // 3. 构造 ModelRequest（工具为空，不允许模型请求工具）
            ModelRequest modelRequest = new ModelRequest(
                    List.of(systemMessage, userMessage),
                    List.of(), // 无工具定义
                    Map.of()   // 无额外选项
            );

            // 4. 创建摘要专用 RunContext（不包含用户身份）
            RunContext summaryRunContext = new RunContext(
                    "summary", // 不使用真实 userId
                    null,      // 不使用真实 sessionId
                    null,      // 不使用真实 threadId
                    "summary-" + Instant.now(clock).toEpochMilli(),
                    java.util.Set.of(),   // 无角色
                    java.util.Set.of()    // 无权限
            );

            // 5. 通过 Gateway 调用模型
            ModelResponse response = modelInvocationGateway.invoke(modelRequest, summaryRunContext);

            // 6. 读取模型最终文本
            AssistantAgentMessage assistantMessage = response.message();

            // 7. 模型返回 ToolCall 时视为无效摘要输出
            if (!assistantMessage.toolCalls().isEmpty()) {
                throw new AgentFrameworkException(
                        AgentErrorCode.INVALID_CONTEXT_SUMMARY_OUTPUT,
                        "Summary model returned ToolCall, which is not allowed for summary generation");
            }

            // 8. 空文本视为无效输出
            String summaryContent = assistantMessage.content();
            if (summaryContent == null || summaryContent.isBlank()) {
                throw new AgentFrameworkException(
                        AgentErrorCode.INVALID_CONTEXT_SUMMARY_OUTPUT,
                        "Summary model returned empty or blank content");
            }

            // 9. 创建 SummaryAgentMessage
            SummaryAgentMessage summaryMessage = new SummaryAgentMessage(
                    summaryContent, Instant.now(clock));

            // 10. 使用 TokenCounter 计算摘要 Token
            int summaryTokenCount = tokenCounter.count(List.of(summaryMessage));

            // 11. 超过 maxSummaryTokens 时视为无效输出
            if (summaryTokenCount > request.maxSummaryTokens()) {
                throw new AgentFrameworkException(
                        AgentErrorCode.INVALID_CONTEXT_SUMMARY_OUTPUT,
                        "Summary token count (" + summaryTokenCount
                                + ") exceeds maxSummaryTokens (" + request.maxSummaryTokens() + ")");
            }

            // 12. 计算源 Token 数
            int sourceTokenCount = tokenCounter.count(request.sourceMessages());

            // 13. 返回 ContextSummaryResult
            boolean existingSummaryReplaced = request.existingSummary().isPresent();

            log.info("Summary generated: sourceMessageCount={}, sourceTokenCount={}, "
                    + "summaryTokenCount={}, existingSummaryReplaced={}",
                    request.sourceMessages().size(), sourceTokenCount,
                    summaryTokenCount, existingSummaryReplaced);

            return new ContextSummaryResult(
                    summaryMessage,
                    request.sourceMessages().size(),
                    sourceTokenCount,
                    summaryTokenCount,
                    existingSummaryReplaced
            );

        } catch (AgentFrameworkException e) {
            // 保留明确摘要错误码
            log.warn("Summary failed: errorCode={}, message={}",
                    e.getErrorCode(), e.getMessage());
            throw e;
        } catch (Exception e) {
            // 模型异常保留 cause 并转换为明确摘要错误
            log.warn("Summary model invocation failed: {}", e.getMessage());
            throw new AgentFrameworkException(
                    AgentErrorCode.CONTEXT_SUMMARY_FAILED,
                    "Summary model invocation failed: " + e.getMessage(), e);
        }
    }
}
