package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.context.ContextTrimDiagnostic;
import com.ksyun.agent.core.context.ContextTrimRequest;
import com.ksyun.agent.core.context.ContextTrimResult;
import com.ksyun.agent.core.context.ContextTrimmer;
import com.ksyun.agent.core.context.TokenCounter;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.AssistantAgentMessage;
import com.ksyun.agent.core.message.SummaryAgentMessage;
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
 * 按消息数量裁剪的上下文裁剪器，纯 Java 实现。
 * <p>
 * 裁剪算法：
 * 1. 校验请求
 * 2. 验证消息历史合法性
 * 3. 将消息划分为原子组
 * 4. 全部 SYSTEM 组永久保留（不计入 maxMessages）
 * 5. 全部 SUMMARY 组永久保留（不计入 maxMessages）
 * 6. 识别最新 User 所在组及其后的所有组为 mandatory groups
 * 7. 从旧到新删除非 mandatory 非 System/Summary 组，直到满足消息数预算
 * 8. 不得拆分 TOOL_INTERACTION 组
 * 9. 如果为了保持原子组而略微超过 maxMessages，记录 ATOMIC_GROUP_OVERSHOOT
 * 10. 完成选择后按原始下标升序输出
 * 11. 最新用户输入保护
 * <p>
 * maxMessages 只统计非 System 非 Summary 消息。
 * <p>
 * 约束：
 * - 不依赖 Spring 容器
 * - 不调用模型或工具
 * - 不持有 Session、ModelClient、Registry 或 Gateway
 * - 不修改输入消息列表
 * - 不产生幻觉内容
 * - 裁剪结果保持原始消息顺序
 * - 线程安全、无状态
 * - TokenCounter 不得为 null
 */
public class MessageCountContextTrimmer implements ContextTrimmer {

    private static final Logger log = LoggerFactory.getLogger(MessageCountContextTrimmer.class);

    private final ContextMessageHistoryValidator historyValidator;
    private final ContextMessageGrouper grouper;
    private final TokenCounter tokenCounter;

    public MessageCountContextTrimmer(ContextMessageHistoryValidator historyValidator,
                                       ContextMessageGrouper grouper,
                                       TokenCounter tokenCounter) {
        this.historyValidator = Objects.requireNonNull(historyValidator);
        this.grouper = Objects.requireNonNull(grouper);
        this.tokenCounter = Objects.requireNonNull(tokenCounter);
    }

    @Override
    public ContextTrimResult trim(ContextTrimRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        List<AgentMessage> messages = request.messages();
        int maxMessages = request.maxMessages();

        // 空消息列表
        if (messages.isEmpty()) {
            return ContextTrimResult.forMessageCount(
                    List.of(), 0, 0, 0, maxMessages, 0,
                    Set.of(), estimateTokens(messages), estimateTokens(messages));
        }

        // 1. 验证消息历史合法性
        historyValidator.validate(messages);

        // 2. 将消息划分为原子组
        List<ContextMessageGroup> groups = grouper.group(messages);

        // 3. 分离 System 组、Summary 组和非 System/非 Summary 组
        List<ContextMessageGroup> systemGroups = new ArrayList<>();
        List<ContextMessageGroup> summaryGroups = new ArrayList<>();
        List<ContextMessageGroup> nonSystemNonSummaryGroups = new ArrayList<>();
        for (ContextMessageGroup group : groups) {
            if (group.groupType() == ContextMessageGroupType.SYSTEM) {
                systemGroups.add(group);
            } else if (group.groupType() == ContextMessageGroupType.SUMMARY) {
                summaryGroups.add(group);
            } else {
                nonSystemNonSummaryGroups.add(group);
            }
        }

        // 统计 System 消息数
        int systemMessageCount = systemGroups.stream()
                .mapToInt(g -> g.messages().size())
                .sum();

        // 统计 Summary 消息数（最多1条，不计入 maxMessages）
        int summaryMessageCount = summaryGroups.stream()
                .mapToInt(g -> g.messages().size())
                .sum();

        // 统计非 System/非 Summary 消息数
        int nonSystemMessageCount = nonSystemNonSummaryGroups.stream()
                .mapToInt(g -> g.messages().size())
                .sum();

        // 估算裁剪前 Token
        long tokensBefore = estimateTokens(messages);

        // 无需裁剪：非 System 消息数量 <= maxMessages
        if (nonSystemMessageCount <= maxMessages) {
            return ContextTrimResult.noTrim(messages, systemMessageCount, nonSystemMessageCount,
                    maxMessages, tokensBefore);
        }

        // 4. 找到最新 UserAgentMessage 所在的非System/非Summary组索引
        int latestUserGroupIndex = -1;
        for (int gi = nonSystemNonSummaryGroups.size() - 1; gi >= 0; gi--) {
            ContextMessageGroup group = nonSystemNonSummaryGroups.get(gi);
            for (AgentMessage msg : group.messages()) {
                if (msg instanceof UserAgentMessage) {
                    latestUserGroupIndex = gi;
                    break;
                }
            }
            if (latestUserGroupIndex >= 0) break;
        }

        // 5. 标记 mandatory groups
        // 最新 User 所在组及其后的所有组为 mandatory（按原始顺序）
        Set<Integer> mandatoryGroupIndices = new LinkedHashSet<>();
        if (latestUserGroupIndex >= 0) {
            for (int gi = latestUserGroupIndex; gi < nonSystemNonSummaryGroups.size(); gi++) {
                mandatoryGroupIndices.add(gi);
            }
        }

        // 6. 计算强制保留消息数
        int mandatoryCount = 0;
        for (int gi : mandatoryGroupIndices) {
            mandatoryCount += nonSystemNonSummaryGroups.get(gi).messages().size();
        }

        // 7. 从旧到新选择非 mandatory 组，直到满足预算
        Set<ContextTrimDiagnostic> diagnostics = new LinkedHashSet<>();
        diagnostics.add(ContextTrimDiagnostic.SYSTEM_MESSAGES_PRESERVED);

        Set<Integer> selectedGroupIndices = new LinkedHashSet<>(mandatoryGroupIndices);
        int selectedNonSystemCount = mandatoryCount;
        int overshoot = 0;

        // 如果最新 User 后的工具组导致超过 maxMessages，记录 overshoot
        if (mandatoryCount > maxMessages) {
            overshoot = mandatoryCount - maxMessages;
            diagnostics.add(ContextTrimDiagnostic.ATOMIC_GROUP_OVERSHOOT);
        }

        // 从旧到新选择非 mandatory 组（在 latestUserGroupIndex 之前的组）
        for (int gi = 0; gi < nonSystemNonSummaryGroups.size(); gi++) {
            if (mandatoryGroupIndices.contains(gi)) {
                continue; // 跳过 mandatory 组
            }

            ContextMessageGroup group = nonSystemNonSummaryGroups.get(gi);
            int groupCount = group.messages().size();

            if (group.atomic()) {
                // TOOL_INTERACTION 原子组：不得拆分
                int projectedCount = selectedNonSystemCount + groupCount;
                if (projectedCount <= maxMessages) {
                    selectedGroupIndices.add(gi);
                    selectedNonSystemCount = projectedCount;
                    diagnostics.add(ContextTrimDiagnostic.TOOL_GROUP_PRESERVED);
                }
                // else: 整组加入会超限，不选择这个旧组
            } else {
                // NORMAL 组（单条消息）
                if (selectedNonSystemCount < maxMessages) {
                    selectedGroupIndices.add(gi);
                    selectedNonSystemCount += groupCount;
                }
            }
        }

        // 8. 最新用户输入保护
        if (latestUserGroupIndex >= 0) {
            diagnostics.add(ContextTrimDiagnostic.LATEST_USER_MESSAGE_PRESERVED);
        }

        // 9. 按原始下标升序收集保留消息
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
            ContextMessageGroup ng = nonSystemNonSummaryGroups.get(selGi);
            for (int idx = ng.startIndex(); idx <= ng.endIndex(); idx++) {
                allRetainedIndices.add(idx);
            }
        }
        allRetainedIndices.sort(Integer::compareTo);

        List<AgentMessage> retainedMessages = new ArrayList<>();
        for (int idx : allRetainedIndices) {
            retainedMessages.add(messages.get(idx));
        }

        // 标记裁剪诊断
        if (selectedNonSystemCount < nonSystemMessageCount) {
            diagnostics.add(ContextTrimDiagnostic.RECENT_MESSAGES_TRIMMED);
        }

        int retainedSystemCount = systemMessageCount;
        int retainedNonSystemCount = selectedNonSystemCount + summaryMessageCount;
        long tokensAfter = estimateTokens(retainedMessages);

        return ContextTrimResult.forMessageCount(
                retainedMessages,
                messages.size(),
                retainedSystemCount,
                retainedNonSystemCount,
                maxMessages,
                overshoot,
                diagnostics,
                tokensBefore,
                tokensAfter
        );
    }

    private long estimateTokens(List<AgentMessage> messages) {
        return tokenCounter.count(messages);
    }
}
