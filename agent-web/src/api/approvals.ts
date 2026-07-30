import { get, post } from './client'
import { pendingApprovalSummarySchema, pendingApprovalDetailSchema, approvalResumeResponseSchema, approvalDecisionSchema } from './contracts/schemas'
import { z } from 'zod'
import type { ApprovalAction } from '@/types'

export function listPendingApprovals(signal?: AbortSignal) {
  return get('/api/hitl/approvals', z.array(pendingApprovalSummarySchema), signal)
}

export function getPendingApproval(runId: string, signal?: AbortSignal) {
  return get(`/api/hitl/approvals/${encodeURIComponent(runId)}`, pendingApprovalDetailSchema, signal)
}

export function decideAndResume(runId: string, approvalId: string, action: ApprovalAction, comment: string, signal?: AbortSignal) {
  const payload = { approvalId, action, comment }
  // 校验 payload
  approvalDecisionSchema.parse(payload)
  return post(`/api/hitl/approvals/${encodeURIComponent(runId)}/decide-and-resume`, payload, approvalResumeResponseSchema, signal)
}
