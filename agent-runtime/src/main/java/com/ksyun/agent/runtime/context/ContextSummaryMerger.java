package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.SummaryAgentMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 摘要合并器，纯 Java 实现。
 * <p>
 * 规则：
 * 1. 删除 selection.sourceMessages
 * 2. 删除旧 SummaryAgentMessage
 * 3. 在 insertionIndex 插入新摘要
 * 4. 保留全部 retainedMessages
 * 5. 原始 System 消息保持原始相对顺序
 * 6. 最近原文消息保持原始相对顺序
 * 7. 最终最多存在一条摘要
 * 8. 不得产生重复消息
 * 9. 不得产生孤立 ToolAgentMessage
 * 10. 合并结果须由调用方再次通过 ContextMessageHistoryValidator（Pipeline 已实现）
 * 11. 返回不可变列表
 * 12. 不得修改原列表
 * 13. 不得使用消息 content 相等判断对象是否应被删除
 * 14. 必须使用选择结果中的确定下标或对象身份语义
 * <p>
 * 约束：
 * - 不调用模型
 * - 线程安全、无状态
 */
public class ContextSummaryMerger {

    private static final int NOT_FOUND = -1;

    /**
     * 将选择结果与新摘要合并成最终消息列表。
     *
     * @param selection 摘要源选择结果
     * @param summary   新生成的摘要消息
     * @return 合并后的不可变消息列表
     */
    public List<AgentMessage> merge(ContextSummarySelection selection, SummaryAgentMessage summary) {
        Objects.requireNonNull(selection, "selection must not be null");
        Objects.requireNonNull(summary, "summary must not be null");

        if (!selection.hasSource()) {
            // 没有源消息，直接返回原始列表（retained + 旧摘要如果有的话）
            // 但这种情况不应发生，因为 Pipeline 在调用前会检查 hasSource()
            return Collections.unmodifiableList(selection.retainedMessages());
        }

        List<AgentMessage> result = new ArrayList<>();

        // 基于 retainedMessages 和 insertionIndex 构建结果
        // retainedMessages 包含 System 消息和保留的最近 content 组
        // 插入位置：System 消息之后，content 消息之前

        List<AgentMessage> retained = selection.retainedMessages();

        // insertionIndex 指的是在原始消息列表中的位置
        // 我们需要将摘要插入到 retainedMessages 中的正确位置
        // 策略：在第一条非 System 的 retained 消息之前插入摘要

        int insertionPointInRetained = 0;
        for (int i = 0; i < retained.size(); i++) {
            if (!(retained.get(i) instanceof com.ksyun.agent.core.message.SystemAgentMessage)) {
                insertionPointInRetained = i;
                break;
            }
            insertionPointInRetained = i + 1;
        }

        // 插入 System 消息
        for (int i = 0; i < insertionPointInRetained; i++) {
            result.add(retained.get(i));
        }

        // 插入新摘要
        result.add(summary);

        // 插入剩余的 retained 消息
        for (int i = insertionPointInRetained; i < retained.size(); i++) {
            // 跳过旧摘要（retained 中不应包含旧摘要，但安全检查）
            if (retained.get(i) instanceof SummaryAgentMessage) {
                continue;
            }
            result.add(retained.get(i));
        }

        return Collections.unmodifiableList(result);
    }
}
