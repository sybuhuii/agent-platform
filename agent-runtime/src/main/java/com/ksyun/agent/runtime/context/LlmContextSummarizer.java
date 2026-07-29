package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.context.ContextSummaryRequest;
import com.ksyun.agent.core.context.ContextSummaryResult;
import com.ksyun.agent.core.context.ContextSummarizer;
import com.ksyun.agent.core.context.TokenCounter;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AssistantAgentMessage;
import com.ksyun.agent.core.message.SummaryAgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
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
 * 修复项：
 * - 摘要模型只调用一次
 * - tools 必须为空
 * - maxSummaryTokens 通过 ModelRequest options 的 "maxTokens" 传递
 * - 模型返回后使用 TokenCounter 做二次校验
 * - ToolCall、空文本、空白文本、超长摘要按现有结构化错误处理并降级到裁剪
 * - 不添加 Fake Model 或固定摘要
 * - 摘要统计包含被替换的旧摘要
 * - 不把 RunContext、userId、sessionId、roles、permissions 发送给模型
 * - 日志不记录 sessionId 和完整权限集合
 */
public class LlmContextSummarizer implements ContextSummarizer {

    private static final Logger log = LoggerFactory.getLogger(LlmContextSummarizer.class);

    /** 与 SpringAiModelClient 适配器实际读取的 option 名一致 */
    private static final String OPTION_MAX_TOKENS = "maxTokens";

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

            if (request.existingSummary().isPresent()) {
                historyContent = promptBuilder.appendExistingSummary(
                        historyContent, request.existingSummary().get().content());
            }

            UserAgentMessage userMessage = new UserAgentMessage(historyContent);

            // 3. 构造 ModelRequest：工具为空，通过 options 传递 maxTokens
            Map<String, Object> options = Map.of(OPTION_MAX_TOKENS, request.maxSummaryTokens());
            ModelRequest modelRequest = new ModelRequest(
                    List.of(systemMessage, userMessage),
                    List.of(), // 无工具定义
                    options
            );

            // 4. 创建摘要专用 RunContext（不包含用户身份和权限）
            RunContext summaryRunContext = new RunContext(
                    "summary-service", null, null,
                    "summary-" + Instant.now(clock).toEpochMilli(),
                    java.util.Set.of(), java.util.Set.of()
            );

            // 5. 通过 Gateway 调用模型
            ModelResponse response = modelInvocationGateway.invoke(modelRequest, summaryRunContext);

            // 6. 读取模型最终文本
            AssistantAgentMessage assistantMessage = response.message();

            // 7. 模型返回 ToolCall 时视为无效摘要输出
            if (assistantMessage.toolCalls() != null && !assistantMessage.toolCalls().isEmpty()) {
                throw new AgentFrameworkException(
                        AgentErrorCode.INVALID_CONTEXT_SUMMARY_OUTPUT,
                        "Summary model returned ToolCall, which is not allowed for summary generation");
            }

            // 8. 空文本/空白文本视为无效输出
            String summaryContent = assistantMessage.content();
            if (summaryContent == null || summaryContent.isBlank()) {
                throw new AgentFrameworkException(
                        AgentErrorCode.INVALID_CONTEXT_SUMMARY_OUTPUT,
                        "Summary model returned empty or blank content");
            }

            // 9. 创建 SummaryAgentMessage
            SummaryAgentMessage summaryMessage = new SummaryAgentMessage(
                    summaryContent, Instant.now(clock));

            // 10. 使用 TokenCounter 做二次校验
            int summaryTokenCount = tokenCounter.count(List.of(summaryMessage));

            // 11. 超长摘要按现有结构化错误处理
            if (summaryTokenCount > request.maxSummaryTokens()) {
                throw new AgentFrameworkException(
                        AgentErrorCode.INVALID_CONTEXT_SUMMARY_OUTPUT,
                        "Summary token count (" + summaryTokenCount
                                + ") exceeds maxSummaryTokens (" + request.maxSummaryTokens() + ")");
            }

            // 12. 计算源 Token 数（包含被替换的旧摘要）
            int sourceTokenCount = tokenCounter.count(request.sourceMessages());

            // 13. 摘要统计包含被替换的旧摘要
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
            log.warn("Summary failed: errorCode={}", e.getErrorCode());
            throw e;
        } catch (Exception e) {
            log.warn("Summary model invocation failed: {}", e.getMessage());
            throw new AgentFrameworkException(
                    AgentErrorCode.CONTEXT_SUMMARY_FAILED,
                    "Summary model invocation failed", e);
        }
    }
}
