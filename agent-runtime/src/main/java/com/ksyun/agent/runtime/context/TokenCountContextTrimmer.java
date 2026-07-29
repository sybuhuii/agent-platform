package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.context.ContextTokenBudget;
import com.ksyun.agent.core.context.ContextTrimDiagnostic;
import com.ksyun.agent.core.context.ContextTrimRequest;
import com.ksyun.agent.core.context.ContextTrimResult;
import com.ksyun.agent.core.context.ContextTrimmer;
import com.ksyun.agent.core.context.TokenCounter;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import com.ksyun.agent.core.message.UserAgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 按 Token 预算裁剪的上下文裁剪器，纯 Java 实现。
 * <p>
 * 裁剪算法：
 * 1. 校验请求和预算
 * 2. 验证消息历史
 * 3. 将消息划分为原子组
 * 4. 计算全部消息原始 Token 数
 * 5. 提取全部 System 组（永久保留）
 * 6. 计算 System 组 Token 总量
 * 7. 计算有效消息预算
 * 8. 最新 User 所在组及其后的所有组为 mandatory
 * 9. 从旧到新选择可放入预算的非 mandatory 组
 * 10. 每次只能选择完整原子组
 * 11. 加入后不超过有效预算才保留
 * 12. 最终按原始消息下标恢复顺序
 * 13. 最终 Token 重新计数
 * 14. 确认结果不超过有效预算
 * <p>
 * Token 预算属于硬上限：
 * - 不得使用 Token overshoot
 * - 强制消息无法放入时必须失败
 * - 不得把超预算请求发送给模型
 * - 不得通过 estimatedTokensAfter 伪造在预算内
 * <p>
 * 约束：
 * - 不依赖 Spring 容器
 * - 不调用模型或工具
 * - 不持有 Session、ModelClient、Registry 或 Gateway
 * - 不修改输入消息列表
 * - 只使用注入的 TokenCounter，不得自己实现第二套字符估算
 * - TokenCounter 不得为 null
 * - 线程安全、无状态
 */
public class TokenCountContextTrimmer implements ContextTrimmer {

    private static final Logger log = LoggerFactory.getLogger(TokenCountContextTrimmer.class);

    private final ContextMessageHistoryValidator historyValidator;
    private final ContextMessageGrouper grouper;
    private final TokenCounter tokenCounter;

    public TokenCountContextTrimmer(ContextMessageHistoryValidator historyValidator,
                                     ContextMessageGrouper grouper,
                                     TokenCounter tokenCounter) {
        this.historyValidator = Objects.requireNonNull(historyValidator);
        this.grouper = Objects.requireNonNull(grouper);
        this.tokenCounter = Objects.requireNonNull(tokenCounter);
    }

    @Override
    public ContextTrimResult trim(ContextTrimRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        if (!request.hasTokenBudget()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_CONTEXT_CONFIGURATION,
                    "Token budget is required for TokenCountContextTrimmer");
        }

        List<AgentMessage> messages = request.messages();
        ContextTokenBudget tokenBudget = request.tokenBudget();
        int additionalReservedTokens = request.additionalReservedTokens();
        int effectiveBudget = request.effectiveMessageBudget();

        // 空消息列表
        if (messages.isEmpty()) {
            Set<ContextTrimDiagnostic> diags = new LinkedHashSet<>();
            diags.add(ContextTrimDiagnostic.NO_TOKEN_TRIMMING_REQUIRED);
            diags.add(ContextTrimDiagnostic.TOKEN_BUDGET_NOT_EXCEEDED);
            return ContextTrimResult.forTokenTrim(
                    List.of(), 0, 0, 0, request.maxMessages(),
                    tokenBudget.maxContextTokens(), tokenBudget.availableMessageTokens(),
                    additionalReservedTokens, effectiveBudget,
                    diags, 0, 0, true);
        }

        // 1. 验证消息历史合法性
        historyValidator.validate(messages);

        // 2. 将消息划分为原子组
        List<ContextMessageGroup> groups = grouper.group(messages);

        // 3. 计算全部消息原始 Token 数
        long tokensBefore = countTokens(messages);

        // 4. 分离 System 组、Summary 组和其他组
        List<ContextMessageGroup> systemGroups = new ArrayList<>();
        List<ContextMessageGroup> summaryGroups = new ArrayList<>();
        List<ContextMessageGroup> otherGroups = new ArrayList<>();
        for (ContextMessageGroup group : groups) {
            if (group.groupType() == ContextMessageGroupType.SYSTEM) {
                systemGroups.add(group);
            } else if (group.groupType() == ContextMessageGroupType.SUMMARY) {
                summaryGroups.add(group);
            } else {
                otherGroups.add(group);
            }
        }

        // 5. 计算 System 组和 Summary 组 Token 总量
        long systemTokens = 0;
        for (ContextMessageGroup sg : systemGroups) {
            systemTokens += countTokens(sg.messages());
        }
        long summaryTokens = 0;
        for (ContextMessageGroup smg : summaryGroups) {
            summaryTokens += countTokens(smg.messages());
        }

        int systemMessageCount = systemGroups.stream()
                .mapToInt(g -> g.messages().size())
                .sum();

        // 6. System 消息超过预算
        if (systemTokens > effectiveBudget) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CONTEXT_BUDGET_EXCEEDED,
                    "System messages token count (" + systemTokens
                            + ") exceeds effective message budget (" + effectiveBudget + ")");
        }

        // 7. 识别最新 User 所在组及其后的所有组为 mandatory
        int latestUserGroupIndexInOther = -1;
        for (int gi = otherGroups.size() - 1; gi >= 0; gi--) {
            ContextMessageGroup group = otherGroups.get(gi);
            for (AgentMessage msg : group.messages()) {
                if (msg instanceof UserAgentMessage) {
                    latestUserGroupIndexInOther = gi;
                    break;
                }
            }
            if (latestUserGroupIndexInOther >= 0) break;
        }

        // 8. 构建确定性原子组选择
        Set<ContextTrimDiagnostic> diagnostics = new LinkedHashSet<>();
        diagnostics.add(ContextTrimDiagnostic.SYSTEM_MESSAGES_PRESERVED);

        // 标记 mandatory 组：最新 User 所在组及其后的所有组
        Set<Integer> mandatoryGroupIndices = new LinkedHashSet<>();
        long mandatoryNonSystemNonSummaryTokens = 0;

        if (latestUserGroupIndexInOther >= 0) {
            // 最新 User 所在组及其后的所有组（包括工具组和普通组）都为 mandatory
            for (int gi = latestUserGroupIndexInOther; gi < otherGroups.size(); gi++) {
                mandatoryGroupIndices.add(gi);
                mandatoryNonSystemNonSummaryTokens += countTokens(otherGroups.get(gi).messages());
            }
        }

        // 强制保留上下文 = System + Summary + mandatory groups
        long mandatoryTokens = systemTokens + summaryTokens + mandatoryNonSystemNonSummaryTokens;

        // 如果没有 User 消息，不强制保留用户组
        // 选择算法从最新到最旧自然选择，仍会优先保留最新消息

        // 9. 强制上下文超过预算
        if (mandatoryTokens > effectiveBudget) {
            if (systemTokens >= effectiveBudget) {
                throw new AgentFrameworkException(
                        AgentErrorCode.CONTEXT_BUDGET_EXCEEDED,
                        "System messages token count (" + systemTokens
                                + ") meets or exceeds effective message budget (" + effectiveBudget + ")");
            }
            diagnostics.add(ContextTrimDiagnostic.SYSTEM_TOKEN_BUDGET_EXCEEDED);
            diagnostics.add(ContextTrimDiagnostic.MANDATORY_CONTEXT_TOO_LARGE);
            throw new AgentFrameworkException(
                    AgentErrorCode.CONTEXT_BUDGET_EXCEEDED,
                    "Mandatory context (System + Summary + latest user and following groups) token count ("
                            + mandatoryTokens + ") exceeds effective message budget ("
                            + effectiveBudget + ")");
        }

        // 10. 从旧到新选择可放入预算的非 mandatory 组
        Set<Integer> selectedGroupIndices = new LinkedHashSet<>(mandatoryGroupIndices);
        long selectedNonSystemNonSummaryTokens = mandatoryNonSystemNonSummaryTokens;
        long budgetForNonSystemNonSummary = effectiveBudget - systemTokens - summaryTokens;

        for (int gi = 0; gi < otherGroups.size(); gi++) {
            if (mandatoryGroupIndices.contains(gi)) {
                continue;
            }

            ContextMessageGroup group = otherGroups.get(gi);
            long groupTokens = countTokens(group.messages());

            // 检查加入后是否超过预算
            long projectedTokens = selectedNonSystemNonSummaryTokens + groupTokens;
            if (projectedTokens <= budgetForNonSystemNonSummary) {
                selectedGroupIndices.add(gi);
                selectedNonSystemNonSummaryTokens = projectedTokens;
            } else {
                // 超过预算，跳过该旧组
                if (group.groupType() == ContextMessageGroupType.TOOL_INTERACTION) {
                    diagnostics.add(ContextTrimDiagnostic.TOOL_GROUP_SKIPPED_FOR_TOKEN_BUDGET);
                } else {
                    diagnostics.add(ContextTrimDiagnostic.OLD_MESSAGES_REMOVED_FOR_TOKEN_BUDGET);
                }
                // 继续检查更旧组（可能较短的组仍可放入预算）
            }
        }

        // 11. 按原始下标升序收集保留消息
        List<Integer> allRetainedIndices = new ArrayList<>();
        for (ContextMessageGroup sg : systemGroups) {
            for (int idx = sg.startIndex(); idx <= sg.endIndex(); idx++) {
                allRetainedIndices.add(idx);
            }
        }
        for (ContextMessageGroup smg : summaryGroups) {
            for (int idx = smg.startIndex(); idx <= smg.endIndex(); idx++) {
                allRetainedIndices.add(idx);
            }
        }
        List<Integer> sortedSelected = new ArrayList<>(selectedGroupIndices);
        sortedSelected.sort(Integer::compareTo);
        for (int selGi : sortedSelected) {
            ContextMessageGroup ng = otherGroups.get(selGi);
            for (int idx = ng.startIndex(); idx <= ng.endIndex(); idx++) {
                allRetainedIndices.add(idx);
            }
        }
        allRetainedIndices.sort(Integer::compareTo);

        List<AgentMessage> retainedMessages = new ArrayList<>();
        for (int idx : allRetainedIndices) {
            retainedMessages.add(messages.get(idx));
        }

        // 12. 最终 Token 重新计数（必须对 retainedMessages 完整计数）
        long tokensAfter = countTokens(retainedMessages);

        // 13. 最终验证：不超过有效预算
        boolean withinBudget = tokensAfter <= effectiveBudget;
        if (!withinBudget) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CONTEXT_BUDGET_EXCEEDED,
                    "Final token count (" + tokensAfter
                            + ") exceeds effective message budget (" + effectiveBudget + ")");
        }

        // 14. 统计
        int retainedSystemCount = systemMessageCount;
        int retainedNonSystemCount = retainedMessages.size() - retainedSystemCount;

        // 15. 诊断
        if (latestUserGroupIndexInOther >= 0) {
            diagnostics.add(ContextTrimDiagnostic.LATEST_USER_MESSAGE_PRESERVED);
        }

        if (tokensAfter == tokensBefore) {
            diagnostics.add(ContextTrimDiagnostic.NO_TOKEN_TRIMMING_REQUIRED);
        } else {
            diagnostics.add(ContextTrimDiagnostic.TOKEN_TRIM_APPLIED);
        }

        diagnostics.add(ContextTrimDiagnostic.FINAL_TOKEN_BUDGET_VERIFIED);
        diagnostics.add(ContextTrimDiagnostic.TOKEN_BUDGET_NOT_EXCEEDED);

        return ContextTrimResult.forTokenTrim(
                retainedMessages,
                messages.size(),
                retainedSystemCount,
                retainedNonSystemCount,
                request.maxMessages(),
                tokenBudget.maxContextTokens(),
                tokenBudget.availableMessageTokens(),
                additionalReservedTokens,
                effectiveBudget,
                diagnostics,
                tokensBefore,
                tokensAfter,
                withinBudget
        );
    }

    /**
     * 使用注入的 TokenCounter 计算消息列表的 Token 数。
     * <p>
     * 异常转换为明确上下文错误。
     */
    private long countTokens(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        try {
            return tokenCounter.count(messages);
        } catch (Exception e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.TOKEN_COUNT_FAILED,
                    "Token counting failed: " + e.getMessage(), e);
        }
    }
}
