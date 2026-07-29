package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.context.TokenCounter;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.SummaryAgentMessage;
import com.ksyun.agent.core.message.UserAgentMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 摘要源选择器，纯 Java 实现。
 * <p>
 * 选择规则：
 * 1. 先完成消息分组
 * 2. 全部原始 SYSTEM 组必须保留原文
 * 3. 最后 recentGroupsToPreserve 个非 SYSTEM、非 SUMMARY 原子组必须保留原文
 * 4. 最后一条真实 User 消息所在组必须保留原文
 * 5. 最后 User 消息之后的全部组必须保留原文
 * 6. 规则3和4取并集——取"最新User所在组起点"和"最后N组起点"中更靠前的位置
 * 7. 未完成的当前轮工具组不得进入摘要源
 * 8. 旧 SUMMARY 组可作为旧摘要参与合并
 * 9. 除旧 SUMMARY 外，摘要源只能包含非 SYSTEM 旧原子组
 * 10. 工具交互组必须整体进入源或整体保留
 * 11. 摘要源必须是较旧消息构成的确定性集合
 * 12. firstSourceIndex、insertionIndex 基于原始消息索引计算
 * 13. 必须保持所有保留消息在原始历史中的全局相对顺序
 * 14. 旧 SummaryAgentMessage 被新摘要替换时，必须正确计入统计
 * <p>
 * 跳过摘要的情况：
 * - 没有可摘要旧消息 → SUMMARY_SKIPPED_NO_SOURCE
 * - 存在可摘要源但 Token 太少 → SUMMARY_SKIPPED_SOURCE_TOO_SMALL
 * - 所有消息都属于必须保留的最近上下文
 * <p>
 * 约束：
 * - 不调用模型
 * - 不修改输入列表
 * - 不使用 HashSet 无序迭代决定结果
 * - 线程安全、无状态
 */
public class ContextSummarySelector {

    private final ContextMessageGrouper grouper;
    private final TokenCounter tokenCounter;

    public ContextSummarySelector(ContextMessageGrouper grouper, TokenCounter tokenCounter) {
        this.grouper = Objects.requireNonNull(grouper);
        this.tokenCounter = Objects.requireNonNull(tokenCounter);
    }

    /**
     * 选择需要摘要的旧消息。
     *
     * @param messages                完整消息列表
     * @param recentGroupsToPreserve  保留的最近非 SYSTEM/非 SUMMARY 原子组数量
     * @param minSourceTokens         最少源 Token 数，低于此值不摘要
     * @return 选择结果
     */
    public ContextSummarySelection select(
            List<AgentMessage> messages,
            int recentGroupsToPreserve,
            int minSourceTokens) {

        Objects.requireNonNull(messages, "messages must not be null");
        if (messages.isEmpty()) {
            return emptySelection(SkipReason.NO_SOURCE);
        }

        // 1. 分组
        List<ContextMessageGroup> groups = grouper.group(messages);
        if (groups.isEmpty()) {
            return emptySelection(SkipReason.NO_SOURCE);
        }

        // 2. 分离各类组
        List<ContextMessageGroup> systemGroups = new ArrayList<>();
        List<ContextMessageGroup> summaryGroups = new ArrayList<>();
        List<ContextMessageGroup> contentGroups = new ArrayList<>();

        for (ContextMessageGroup group : groups) {
            if (group.groupType() == ContextMessageGroupType.SYSTEM) {
                systemGroups.add(group);
            } else if (group.groupType() == ContextMessageGroupType.SUMMARY) {
                summaryGroups.add(group);
            } else {
                contentGroups.add(group);
            }
        }

        // 只存在 System 消息
        if (contentGroups.isEmpty()) {
            return emptySelection(SkipReason.NO_SOURCE);
        }

        // 3. 找到最新 User 消息所在的 content 组索引
        int latestUserContentGroupIndex = -1;
        for (int gi = contentGroups.size() - 1; gi >= 0; gi--) {
            for (AgentMessage msg : contentGroups.get(gi).messages()) {
                if (msg instanceof UserAgentMessage) {
                    latestUserContentGroupIndex = gi;
                    break;
                }
            }
            if (latestUserContentGroupIndex >= 0) break;
        }

        // 4. 确定必须保留的 content 组范围（取并集）
        // A: 最新 User 所在组及其后的所有组
        int preserveFromLatestUser = (latestUserContentGroupIndex >= 0)
                ? latestUserContentGroupIndex
                : contentGroups.size(); // 没有 User，不强制保留

        // B: 最后 recentGroupsToPreserve 个 content 组
        int preserveFromRecentGroups = contentGroups.size() - recentGroupsToPreserve;
        if (preserveFromRecentGroups < 0) {
            preserveFromRecentGroups = 0;
        }

        // 取并集：取更靠前的位置（保留更多组）
        int preserveFromIndex = Math.min(preserveFromLatestUser, preserveFromRecentGroups);

        // 5. 将 content 组分为摘要源和保留组
        List<ContextMessageGroup> sourceContentGroups = new ArrayList<>();
        List<ContextMessageGroup> retainedContentGroups = new ArrayList<>();

        for (int gi = 0; gi < contentGroups.size(); gi++) {
            if (gi < preserveFromIndex) {
                sourceContentGroups.add(contentGroups.get(gi));
            } else {
                retainedContentGroups.add(contentGroups.get(gi));
            }
        }

        // 6. 检查跳过条件
        // 没有可摘要旧消息（源content组为空且没有旧摘要，或只有一条旧摘要且没有新增旧消息）
        if (sourceContentGroups.isEmpty() && summaryGroups.isEmpty()) {
            return emptySelection(SkipReason.NO_SOURCE);
        }

        // 只存在一条旧摘要且没有新增旧消息可合并
        if (sourceContentGroups.isEmpty() && summaryGroups.size() == 1) {
            return emptySelection(SkipReason.NO_SOURCE);
        }

        // 7. 构建摘要源消息（旧消息 + 旧摘要）
        List<AgentMessage> sourceMessages = new ArrayList<>();
        SummaryAgentMessage existingSummary = null;

        // 旧摘要计入源
        if (!summaryGroups.isEmpty()) {
            for (AgentMessage msg : summaryGroups.get(0).messages()) {
                if (msg instanceof SummaryAgentMessage sa) {
                    existingSummary = sa;
                    sourceMessages.add(sa); // 旧摘要计入源消息
                    break;
                }
            }
        }

        // 添加旧的 content 消息
        for (ContextMessageGroup group : sourceContentGroups) {
            sourceMessages.addAll(group.messages());
        }

        // 源消息只有旧摘要，没有新增content
        if (sourceMessages.isEmpty()) {
            return emptySelection(SkipReason.NO_SOURCE);
        }

        // 检查是否只有旧摘要作为源消息
        boolean onlySummaryInSource = sourceContentGroups.isEmpty() && existingSummary != null;
        if (onlySummaryInSource) {
            return emptySelection(SkipReason.NO_SOURCE);
        }

        // 8. 计算源 Token 数
        int sourceTokenCount = tokenCounter.count(sourceMessages);

        // 可摘要源 Token 少于 minSourceTokens —— 区分两种跳过原因
        if (sourceTokenCount < minSourceTokens) {
            return emptySelection(SkipReason.SOURCE_TOO_SMALL);
        }

        // 9. 计算保留消息（保持原始全局相对顺序）
        // 收集所有保留组的索引范围
        List<Integer> retainedIndices = new ArrayList<>();

        // System 组保留
        for (ContextMessageGroup sg : systemGroups) {
            for (int idx = sg.startIndex(); idx <= sg.endIndex(); idx++) {
                retainedIndices.add(idx);
            }
        }

        // 保留的 content 组
        for (ContextMessageGroup rg : retainedContentGroups) {
            for (int idx = rg.startIndex(); idx <= rg.endIndex(); idx++) {
                retainedIndices.add(idx);
            }
        }

        // 按原始下标排序
        retainedIndices.sort(Integer::compareTo);

        // 从原始消息中按顺序提取
        List<AgentMessage> retainedMessages = new ArrayList<>();
        for (int idx : retainedIndices) {
            AgentMessage msg = messages.get(idx);
            // 跳过旧摘要（将被新摘要替换）
            if (msg instanceof SummaryAgentMessage) {
                continue;
            }
            retainedMessages.add(msg);
        }

        // 10. 计算 insertionIndex（基于原始消息索引）
        // 摘要应插入在第一个保留的非 System content 组之前
        // 如果有 System 消息，插在最后一条 System 之后
        int insertionIndex = 0;
        for (ContextMessageGroup sg : systemGroups) {
            insertionIndex = Math.max(insertionIndex, sg.endIndex() + 1);
        }

        // 11. 计算 firstSourceIndex（基于原始消息索引）
        int firstSourceIndex = -1;
        if (!sourceContentGroups.isEmpty()) {
            firstSourceIndex = sourceContentGroups.get(0).startIndex();
        } else if (existingSummary != null && !summaryGroups.isEmpty()) {
            firstSourceIndex = summaryGroups.get(0).startIndex();
        }

        // 12. 统计源组数（包含旧摘要组）
        int sourceGroupCount = sourceContentGroups.size();
        if (existingSummary != null) {
            sourceGroupCount++; // 旧摘要被替换时计入
        }

        // 13. 统计被摘要的消息数（包含旧摘要）
        int sourceMessageCount = sourceMessages.size();

        return new ContextSummarySelection(
                sourceMessages,
                retainedMessages,
                Optional.ofNullable(existingSummary),
                sourceMessageCount,
                sourceTokenCount,
                sourceGroupCount,
                firstSourceIndex,
                insertionIndex,
                SkipReason.HAS_SOURCE // 有可摘要源
        );
    }

    private ContextSummarySelection emptySelection(SkipReason reason) {
        return new ContextSummarySelection(
                List.of(), List.of(), Optional.empty(),
                0, 0, 0, -1, 0,
                reason
        );
    }

    /**
     * 摘要跳过原因。
     */
    public enum SkipReason {
        /** 存在可摘要源（未跳过） */
        HAS_SOURCE,
        /** 没有可摘要源 */
        NO_SOURCE,
        /** 存在可摘要源但 Token 太少 */
        SOURCE_TOO_SMALL
    }
}
