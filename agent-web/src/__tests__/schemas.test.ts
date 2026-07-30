/**
 * Zod 边界校验测试
 */
import { describe, it, expect } from 'vitest'
import {
  loginResponseSchema,
  supervisorInvokeResponseSchema,
  approvalDecisionSchema,
  threadEntrySchema
} from '@/api/contracts/schemas'

describe('Zod schemas', () => {
  describe('loginResponseSchema', () => {
    it('should validate valid login response', () => {
      const result = loginResponseSchema.safeParse({
        sessionId: 'abc123',
        username: 'admin',
        roles: ['ADMIN'],
        expiresAtEpochMillis: Date.now()
      })
      expect(result.success).toBe(true)
    })

    it('should reject missing required fields', () => {
      const result = loginResponseSchema.safeParse({
        sessionId: 'abc123'
      })
      expect(result.success).toBe(false)
    })
  })

  describe('supervisorInvokeResponseSchema', () => {
    it('should validate valid supervisor response', () => {
      const result = supervisorInvokeResponseSchema.safeParse({
        runId: 'run-1',
        threadId: 'thread-1',
        supervisorName: 'general_supervisor',
        success: true,
        content: 'Hello',
        evidence: [],
        metadata: {}
      })
      expect(result.success).toBe(true)
    })

    it('should validate supervisor response with approvalId in metadata', () => {
      const result = supervisorInvokeResponseSchema.safeParse({
        runId: 'run-1',
        threadId: 'thread-1',
        supervisorName: 'general_supervisor',
        success: false,
        content: '',
        evidence: [],
        metadata: { approvalId: 'ap-1', operationName: 'test-tool', riskLevel: 'HIGH' }
      })
      expect(result.success).toBe(true)
    })
  })

  describe('approvalDecisionSchema', () => {
    it('should validate APPROVE action', () => {
      const result = approvalDecisionSchema.safeParse({
        approvalId: 'ap-1',
        action: 'APPROVE',
        comment: 'Looks good'
      })
      expect(result.success).toBe(true)
    })

    it('should reject empty approvalId', () => {
      const result = approvalDecisionSchema.safeParse({
        approvalId: '',
        action: 'APPROVE',
        comment: ''
      })
      expect(result.success).toBe(false)
    })
  })

  describe('threadEntrySchema', () => {
    it('should validate valid thread entry without supervisorName', () => {
      const result = threadEntrySchema.safeParse({
        threadId: 't-1',
        title: 'Test conversation',
        createdAt: Date.now(),
        lastMessageAt: Date.now()
      })
      expect(result.success).toBe(true)
    })

    it('should reject thread entry with old mode field', () => {
      const result = threadEntrySchema.safeParse({
        threadId: 't-1',
        mode: 'agent',
        targetName: 'test-agent',
        title: 'Test',
        createdAt: Date.now(),
        lastMessageAt: Date.now()
      })
      expect(result.success).toBe(false)
    })
  })
})
