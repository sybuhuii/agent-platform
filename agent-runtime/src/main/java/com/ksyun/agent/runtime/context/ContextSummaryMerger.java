package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.SummaryAgentMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 摘要合并器，纯 Java 实现。
 * <p>
 * 规则：
 * 1. 删除 selection.sourceMessages 和旧 SummaryAgentMessage
 * 2. 用一条新 SummaryAgentMessage 替换被摘要的旧消息和旧摘要
 * 3. 在 selection.insertionIndex 插入新摘要
 * 4. 保留 System 和未摘要消息的原始全局相对顺序
 * 5. 禁止把所有 System 消息统一移动到新摘要之前
 * 6. 最终最多存在一条 SummaryAgentMessage
 * 7. 不得把 Summary 插入未完成的工具调用组
 * 8. 合并完成后必须经过现有消息结构校验器验证
 * 9. 返回 List.copyOf 做防御性复制
 * 10. 不得修改原列表
 * 11. 不得使用消息 content 相等判断对象是否应被删除
 * <p>
 * 约束：
 * - 不调用模型
 * - 线程安全、无状态
 */
public class ContextSummaryMerger {

    private final ContextMessageHistoryValidator validator;

    public ContextSummaryMerger(ContextMessageHistoryValidator validator) {
        this.validator = Objects.requireNonNull(validator);
    }

    /**
     * 将选择结果与新摘要合并成最终消息列表。
     *
     * @param originalMessages 原始完整消息列表
     * @param selection        摘要源选择结果
     * @param summary          新生成的摘要消息
     * @return 合并后的不可变消息列表
     */
    public List<AgentMessage> merge(List<AgentMessage> originalMessages,
                                     ContextSummarySelection selection,
                                     SummaryAgentMessage summary) {
        Objects.requireNonNull(originalMessages, "originalMessages must not be null");
        Objects.requireNonNull(selection, "selection must not be null");
        Objects.requireNonNull(summary, "summary must not be null");

        if (!selection.hasSource()) {
            return List.copyOf(originalMessages);
        }

        // 构建需要删除的消息索引集合（基于原始消息索引）
        // sourceMessages 中的所有消息需要删除
        java.util.Set<Integer> removedIndices = new java.util.HashSet<>();
        for (AgentMessage srcMsg : selection.sourceMessages()) {
            int idx = originalMessages.indexOf(srcMsg);
            if (idx >= 0) {
                removedIndices.add(idx);
            }
        }

        // insertionIndex 是在原始消息列表中的位置
        int insertionIndex = selection.insertionIndex();
        if (insertionIndex < 0) {
            insertionIndex = 0;
        }
        if (insertionIndex > originalMessages.size()) {
            insertionIndex = originalMessages.size();
        }

        // 按原始全局顺序构建结果
        List<AgentMessage> result = new ArrayList<>();
        boolean summaryInserted = false;

        for (int i = 0; i < originalMessages.size(); i++) {
            // 在 insertionIndex 处插入新摘要
            if (i == insertionIndex && !summaryInserted) {
                result.add(summary);
                summaryInserted = true;
            }

            // 跳过被删除的消息
            if (removedIndices.contains(i)) {
                continue;
            }

            // 跳过旧摘要（由 selection 中的 sourceMessages 已包含，但防御性检查）
            AgentMessage msg = originalMessages.get(i);
            if (msg instanceof SummaryAgentMessage) {
                continue;
            }

            result.add(msg);
        }

        // 如果 insertionIndex 在末尾，追加摘要
        if (!summaryInserted) {
            result.add(summary);
        }

        // 合并完成后验证消息结构
        validator.validate(result);

        return List.copyOf(result);
    }
}
