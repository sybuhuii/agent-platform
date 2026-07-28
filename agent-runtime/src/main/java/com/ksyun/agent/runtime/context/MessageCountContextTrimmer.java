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
 * 5. 从最后一个非 SYSTEM 组开始向前选择完整原子组
 * 6. 直到非 System 消息数量达到 maxMessages 目标
 * 7. 不得拆分 TOOL_INTERACTION 组
 * 8. 完成选择后按原始下标升序输出
 * 9. 最新用户输入保护
 * 10. atomicGroupOvershoot 记录超限数量
 * <p>
 * maxMessages 只统计非 System 消息。
 * <p>
 * 普通情况：保留最近不超过 maxMessages 条非 System 消息。
 * <p>
 * 原子组边界情况：
 * - 加入某个 TOOL_INTERACTION 组会超过 maxMessages 时，不得只保留其中一部分
 * - 如果已经选择了更新的消息组，则默认不再选择这个超限旧组
 * - 如果最新的非 System 组本身就超过 maxMessages，为保证最新交互完整，仍保留整个最新组
 * - 这种情况记录 atomicGroupOvershoot
 * - 除最新原子组无法拆分的情况外，不应超过目标
 * - 不得为了严格满足数量而破坏工具消息配对
 * <p>
 * 约束：
 * - 不依赖 Spring 容器
 * - 不调用模型或工具
 * - 不持有 Session、ModelClient、Registry 或 Gateway
 * - 不修改输入消息列表
 * - 不产生幻觉内容
 * - 裁剪结果保持原始消息顺序
 * - 线程安全、无状态
 */
public class MessageCountContextTrimmer implements ContextTrimmer {

    private static final Logger log = LoggerFactory.getLogger(MessageCountContextTrimmer.class);

    private final ContextMessageHistoryValidator historyValidator;
    private final ContextMessageGrouper grouper;
    private final TokenCounter tokenCounter;

    public MessageCountContextTrimmer() {
        this.historyValidator = new ContextMessageHistoryValidator();
        this.grouper = new ContextMessageGrouper();
        this.tokenCounter = null;
    }

    public MessageCountContextTrimmer(ContextMessageHistoryValidator historyValidator,
                                       ContextMessageGrouper grouper) {
        this(historyValidator, grouper, null);
    }

    public MessageCountContextTrimmer(ContextMessageHistoryValidator historyValidator,
                                       ContextMessageGrouper grouper,
                                       TokenCounter tokenCounter) {
        this.historyValidator = Objects.requireNonNull(historyValidator);
        this.grouper = Objects.requireNonNull(grouper);
        this.tokenCounter = tokenCounter;
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

        // 4. 找到最新 UserAgentMessage 索引
        int latestUserIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserAgentMessage) {
                latestUserIndex = i;
                break;
            }
        }

        // 找到包含最新用户消息的非System/非Summary组索引
        int latestUserGroupIndex = -1;
        if (latestUserIndex >= 0) {
            for (int gi = 0; gi < nonSystemNonSummaryGroups.size(); gi++) {
                ContextMessageGroup group = nonSystemNonSummaryGroups.get(gi);
                for (AgentMessage msg : group.messages()) {
                    if (msg instanceof UserAgentMessage && messages.indexOf(msg) == latestUserIndex) {
                        latestUserGroupIndex = gi;
                        break;
                    }
                }
                if (latestUserGroupIndex >= 0) break;
            }
        }

        // 5. 从最后一个非 SYSTEM/非 SUMMARY 组开始向前选择
        Set<ContextTrimDiagnostic> diagnostics = new LinkedHashSet<>();
        diagnostics.add(ContextTrimDiagnostic.SYSTEM_MESSAGES_PRESERVED);

        Set<Integer> selectedGroupIndices = new LinkedHashSet<>();
        int selectedNonSystemCount = 0;
        int overshoot = 0;

        // 从后向前遍历非 System/非 Summary 组
        for (int gi = nonSystemNonSummaryGroups.size() - 1; gi >= 0; gi--) {
            ContextMessageGroup group = nonSystemNonSummaryGroups.get(gi);
            int groupCount = group.messages().size();

            if (selectedGroupIndices.isEmpty()) {
                // 最新的非 System 组：必须保留（为保证最新交互完整）
                selectedGroupIndices.add(gi);
                selectedNonSystemCount += groupCount;

                if (group.groupType() == ContextMessageGroupType.TOOL_INTERACTION) {
                    diagnostics.add(ContextTrimDiagnostic.TOOL_GROUP_PRESERVED);
                }

                // 最新原子组超过 maxMessages 时，记录 overshoot
                if (groupCount > maxMessages) {
                    overshoot = groupCount - maxMessages;
                    diagnostics.add(ContextTrimDiagnostic.ATOMIC_GROUP_OVERSHOOT);
                }
                continue;
            }

            // 后续组：加入后是否超过 maxMessages
            if (group.atomic()) {
                // TOOL_INTERACTION 原子组：不得拆分
                int projectedCount = selectedNonSystemCount + groupCount;
                if (projectedCount <= maxMessages) {
                    selectedGroupIndices.add(gi);
                    selectedNonSystemCount += groupCount;
                    diagnostics.add(ContextTrimDiagnostic.TOOL_GROUP_PRESERVED);
                }
                // else: 整组加入会超限，不再选择这个旧组，不拆散原子组
            } else {
                // NORMAL 组（单条消息）
                if (selectedNonSystemCount < maxMessages) {
                    selectedGroupIndices.add(gi);
                    selectedNonSystemCount += groupCount;
                }
            }
        }

        // 6. 最新用户输入保护
        if (latestUserIndex >= 0) {
            boolean latestUserInSelected = false;
            for (int selGi : selectedGroupIndices) {
                ContextMessageGroup selGroup = nonSystemNonSummaryGroups.get(selGi);
                for (AgentMessage msg : selGroup.messages()) {
                    if (msg instanceof UserAgentMessage) {
                        latestUserInSelected = true;
                        break;
                    }
                }
                if (latestUserInSelected) break;
            }

            if (latestUserInSelected) {
                diagnostics.add(ContextTrimDiagnostic.LATEST_USER_MESSAGE_PRESERVED);
            } else if (latestUserGroupIndex >= 0) {
                // 最新用户消息不在已选集合中，强制加入其所在组
                ContextMessageGroup userGroup = nonSystemNonSummaryGroups.get(latestUserGroupIndex);
                if (!selectedGroupIndices.contains(latestUserGroupIndex)) {
                    selectedGroupIndices.add(latestUserGroupIndex);
                    selectedNonSystemCount += userGroup.messages().size();
                    diagnostics.add(ContextTrimDiagnostic.LATEST_USER_MESSAGE_PRESERVED);

                    if (userGroup.atomic()) {
                        diagnostics.add(ContextTrimDiagnostic.TOOL_GROUP_PRESERVED);
                    }

                    // 加入用户组可能导致 overshoot
                    if (selectedNonSystemCount > maxMessages && overshoot == 0) {
                        overshoot = selectedNonSystemCount - maxMessages;
                        diagnostics.add(ContextTrimDiagnostic.ATOMIC_GROUP_OVERSHOOT);
                    }
                }
            }
        }

        // 7. 按原始下标升序收集保留消息
        List<Integer> sortedSelected = new ArrayList<>(selectedGroupIndices);
        sortedSelected.sort(Integer::compareTo);

        List<Integer> allRetainedIndices = new ArrayList<>();
        for (ContextMessageGroup sg : systemGroups) {
            allRetainedIndices.add(sg.startIndex());
        }
        // SUMMARY 组永久保留（不计入 maxMessages，但计入最终消息总数）
        for (ContextMessageGroup smg : summaryGroups) {
            for (int idx = smg.startIndex(); idx <= smg.endIndex(); idx++) {
                allRetainedIndices.add(idx);
            }
        }
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
        // SUMMARY 组不计入 maxMessages，但计入最终消息总数
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
        if (tokenCounter == null) {
            return 0;
        }
        return tokenCounter.count(messages);
    }
}
