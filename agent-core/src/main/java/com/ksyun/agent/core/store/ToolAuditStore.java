package com.ksyun.agent.core.store;

import com.ksyun.agent.core.tool.audit.ToolAuditSnapshot;
import com.ksyun.agent.core.tool.audit.ToolInvocationAuditCompletion;
import com.ksyun.agent.core.tool.audit.ToolInvocationAuditStarted;

import java.util.Optional;

/**
 * 工具调用审计 Store SPI。
 * <p>
 * 位于 agent-core，实现在 agent-infrastructure（内存或 PostgreSQL）。
 * <p>
 * 语义：
 * <ul>
 *   <li>{@link #start} 是创建语义。auditId 由 Store 外生成；相同 auditId 相同内容写入幂等，
 *       相同 auditId 不同内容冲突。</li>
 *   <li>{@link #complete} 只允许从 STARTED 原子转换到终态（SUCCEEDED/FAILED/SUSPENDED/EXCEPTION）。
 *       相同终态重复提交幂等；不同终态冲突。</li>
 *   <li>不创建工具结果仓库，不保存完整参数和输出。</li>
 *   <li>返回不可变快照。</li>
 * </ul>
 * <p>
 * Store 不负责生成 auditId、调用模型、执行工具或解析权限。
 */
public interface ToolAuditStore {

    /**
     * 写入 STARTED 记录。创建语义。
     * <p>
     * 相同 auditId 相同内容幂等；不同内容抛出冲突异常。
     *
     * @param started 审计启动记录
     * @return 写入后的不可变快照
     */
    ToolAuditSnapshot start(ToolInvocationAuditStarted started);

    /**
     * 将 STARTED 原子转换为终态。
     * <p>
     * 只允许 STARTED → 终态。相同终态重复提交幂等；不同终态冲突。
     * 不存在或已是终态且一致时幂等返回当前快照。
     *
     * @param completion 终态完成记录
     * @return 更新后的不可变快照
     */
    ToolAuditSnapshot complete(ToolInvocationAuditCompletion completion);

    /**
     * 按 auditId 查询审计快照（内部验证用途）。
     *
     * @param auditId 审计 ID
     * @return 不可变快照，不存在返回 empty
     */
    Optional<ToolAuditSnapshot> findById(String auditId);
}
