import { get, post } from './http.js'

/**
 * 查询当前用户待审批记录。
 */
export function listPendingApprovals() {
  return get('/api/agent/approval/pending')
}

/**
 * 提交审批决定。
 * @param {string} runId 运行 ID
 * @param {string} approvalId 审批 ID
 * @param {'APPROVE'|'REJECT'} action 审批动作
 * @param {string} [comment] 备注
 */
export function decideApproval(runId, approvalId, action, comment) {
  return post('/api/agent/approval/decide', {
    runId,
    approvalId,
    action,
    comment: comment || ''
  })
}
