/**
 * Zod schemas — 系统边界的运行时校验。
 * 所有枚举值必须与后端 Java 枚举严格一致。
 *
 * 业务模型：用户只与 Supervisor 交互。
 * - 不包含 agentInvokeResponseSchema（前端不调用 /api/agent/invoke）
 * - 后端 Agent 接口仅用于测试，生产前端不调用
 */
import { z } from 'zod'

// ─── 共享枚举 Schema ───

export const runStatusSchema = z.enum(['CREATED', 'RUNNING', 'INTERRUPTED', 'SUSPENDED', 'COMPLETED', 'FAILED'])
export const approvalStatusSchema = z.enum(['PENDING', 'APPROVED', 'REJECTED'])
export const toolRiskLevelSchema = z.enum(['SAFE', 'LOW', 'MEDIUM', 'HIGH'])
export const approvalActionSchema = z.enum(['APPROVE', 'REJECT'])

// 后端错误响应
export const apiErrorResponseSchema = z.object({
  errorCode: z.string(),
  message: z.string()
})

// 登录响应
export const loginResponseSchema = z.object({
  sessionId: z.string(),
  username: z.string(),
  roles: z.array(z.string()),
  expiresAtEpochMillis: z.number()
})

// 用户信息
export const userInfoSchema = z.object({
  userId: z.string(),
  username: z.string(),
  roles: z.array(z.string()),
  permissions: z.array(z.string())
})

// Supervisor 调用响应（用户唯一入口）
export const supervisorInvokeResponseSchema = z.object({
  runId: z.string(),
  threadId: z.string(),
  supervisorName: z.string(),
  success: z.boolean(),
  content: z.string(),
  errorCode: z.string().nullable().optional(),
  evidence: z.array(z.string()),
  metadata: z.record(z.string(), z.unknown()),
  approvalRunId: z.string().nullable().optional(),
  parentRunId: z.string().nullable().optional(),
  isNested: z.boolean().default(false)
})

// 审批决定
export const approvalDecisionSchema = z.object({
  approvalId: z.string().min(1),
  action: approvalActionSchema,
  comment: z.string().max(1000)
})

// 审批恢复响应
export const approvalResumeResponseSchema = z.object({
  runId: z.string(),
  threadId: z.string(),
  agentName: z.string(),
  success: z.boolean(),
  content: z.string(),
  errorCode: z.string().nullable().optional(),
  evidence: z.array(z.string()),
  status: runStatusSchema,
  approvalId: z.string().nullable().optional(),
  operationName: z.string().nullable().optional(),
  riskLevel: z.string().nullable().optional(),
  safeMetadata: z.record(z.string(), z.unknown()),
  approvalRunId: z.string().nullable().optional(),
  parentRunId: z.string().nullable().optional(),
  isNested: z.boolean().default(false)
})

// 待审批列表项
export const pendingApprovalSummarySchema = z.object({
  runId: z.string(),
  threadId: z.string(),
  agentName: z.string(),
  approvalId: z.string(),
  operationType: z.string(),
  operationName: z.string(),
  riskLevel: z.string(),
  reason: z.string(),
  requestedAt: z.string(),
  status: approvalStatusSchema
})

// 待审批详情
export const pendingApprovalDetailSchema = z.object({
  runId: z.string(),
  threadId: z.string(),
  agentName: z.string(),
  approvalId: z.string(),
  operationType: z.string(),
  operationName: z.string(),
  riskLevel: z.string(),
  reason: z.string(),
  requestedAt: z.string(),
  status: approvalStatusSchema,
  nodeName: z.string(),
  safeArguments: z.record(z.string(), z.unknown()),
  createdAt: z.string(),
  updatedAt: z.string(),
  checkpointVersion: z.number()
})

// 框架查询 — Supervisor 信息（用户可见）
export const supervisorInfoSchema = z.object({
  name: z.string(),
  description: z.string(),
  memberAgents: z.array(z.string()),
  maxIterations: z.number()
})

// 框架查询 — Agent 信息（仅管理页面展示，不用于用户选择）
export const agentInfoSchema = z.object({
  name: z.string(),
  description: z.string(),
  allowedTools: z.array(z.string()),
  maxIterations: z.number(),
  contextManagement: z.record(z.string(), z.unknown())
})

export const toolInfoSchema = z.object({
  name: z.string(),
  description: z.string(),
  riskLevel: toolRiskLevelSchema
})

export const healthSchema = z.object({
  status: z.string(),
  framework: z.string()
})

// 用户/角色管理
export const userSummarySchema = z.object({
  userId: z.string(),
  username: z.string(),
  roleNames: z.array(z.string()),
  enabled: z.boolean()
})

export const roleSummarySchema = z.object({
  roleName: z.string(),
  description: z.string(),
  permissionCodes: z.array(z.string())
})

// 浏览器存储恢复数据 — 不存 Supervisor 技术名称，严格模式拒绝旧字段
export const threadEntrySchema = z.object({
  threadId: z.string(),
  title: z.string(),
  createdAt: z.number(),
  lastMessageAt: z.number()
}).strict()

export const storedThreadsSchema = z.array(threadEntrySchema)

// ─── 会话历史 API ───

export const conversationThreadSchema = z.object({
  threadId: z.string(),
  title: z.string(),
  pinned: z.boolean(),
  archived: z.boolean(),
  agentName: z.string().nullable(),
  createdAtEpochMillis: z.number(),
  lastMessageAtEpochMillis: z.number()
})

export const conversationMessageSchema = z.object({
  messageId: z.string(),
  sequenceNo: z.number(),
  role: z.enum(['USER', 'ASSISTANT']),
  content: z.string(),
  createdAtEpochMillis: z.number()
})
