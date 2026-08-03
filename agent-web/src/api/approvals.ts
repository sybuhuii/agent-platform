import { get, post } from './client'
import { pendingApprovalSummarySchema, pendingApprovalDetailSchema, approvalResumeResponseSchema, approvalDecisionSchema } from './contracts/schemas'
import { z } from 'zod'
import type { ApprovalAction, ApprovalResumeResponse } from '@/types'

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

export function normalizeApprovalResumeResponse(
  response: Awaited<ReturnType<typeof decideAndResume>>
): ApprovalResumeResponse {
  return {
    ...response,
    approvalRunId: response.approvalRunId ?? undefined,
    parentRunId: response.parentRunId ?? undefined,
    approvalId: response.approvalId ?? undefined,
    operationName: response.operationName ?? undefined,
    riskLevel: response.riskLevel ?? undefined,
    errorCode: response.errorCode ?? undefined,
    safeMetadata: response.safeMetadata ?? undefined
  } as ApprovalResumeResponse
}
