import { get, post } from './http.js'

/**
 * HITL 审批 API 封装。
 * <p>
 * 复用现有统一 HTTP 客户端（自动附加 X-Session-Id、统一 401/403 处理）。
 * 不直接使用裸 fetch。
 * 不把 sessionId 放入 URL 或 Body。
 * 不调用 /api/dev/**。
 * 不自动重试 decide-and-resume 写请求。
 * 401 继续清理 Session 并跳转登录。
 */

/**
 * 查询当前用户待审批列表。
 */
export function listPendingApprovals() {
  return get('/api/hitl/approvals')
}

/**
 * 查询指定审批详情。
 * @param {string} runId 运行 ID
 */
export function getPendingApproval(runId) {
  return get(`/api/hitl/approvals/${encodeURIComponent(runId)}`)
}

/**
 * 审批决定并恢复执行。
 * <p>
 * runId 来自 URL 路径。Body 只包含 approvalId、action、comment。
 * 不自动重试写请求。
 *
 * @param {string} runId 运行 ID（路径参数）
 * @param {{approvalId: string, action: 'APPROVE'|'REJECT', comment?: string}} payload
 */
export function decideAndResume(runId, payload) {
  return post(`/api/hitl/approvals/${encodeURIComponent(runId)}/decide-and-resume`, {
    approvalId: payload.approvalId,
    action: payload.action,
    comment: payload.comment || ''
  })
}
