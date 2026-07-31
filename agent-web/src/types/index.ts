/**
 * 前端类型定义 — 基于后端真实 DTO。
 *
 * 业务模型：用户 → 系统 → 唯一 Supervisor → 内部 Agent → Tool
 * - 系统始终只有一个 Supervisor，用户不知道此概念
 * - 用户登录后直接与"系统"对话，无需选择 Supervisor
 * - 前端不展示 Supervisor 技术名称（如 general_supervisor）
 * - 前端不展示 Agent 列表、Agent 选择器
 * - 后端 /api/agent/** 接口只用于测试，生产前端不得调用
 * - 危险工具需要审批时，立即弹出审批弹窗
 */

// ─── 通用 ───

/** 后端 RunStatus 枚举 */
export type RunStatus = 'CREATED' | 'RUNNING' | 'INTERRUPTED' | 'SUSPENDED' | 'COMPLETED' | 'FAILED'

/** 后端 ApprovalStatus 枚举 — 对应 core/approval/ApprovalStatus.java */
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/** 后端 ApprovalAction 枚举 */
export type ApprovalAction = 'APPROVE' | 'REJECT'

/** 后端 ToolRiskLevel 枚举 — 对应 core/tool/ToolRiskLevel.java */
export type ToolRiskLevel = 'SAFE' | 'LOW' | 'MEDIUM' | 'HIGH'

// ─── 认证 ───

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  sessionId: string
  username: string
  roles: string[]
  expiresAtEpochMillis: number
}

export interface UserInfoResponse {
  userId: string
  username: string
  roles: string[]
  permissions: string[]
}

export interface SessionInfoResponse {
  sessionId: string
  username: string
  roles: string[]
  createdAtEpochMillis: number
  expiresAtEpochMillis: number
}

// ─── 框架查询 ───

export interface AgentInfoResponse {
  name: string
  description: string
  allowedTools: string[]
  maxIterations: number
  contextManagement: AgentContextManagementInfo
}

export interface AgentContextManagementInfo {
  trimStrategy: string
  maxMessages: number
  systemPromptAlwaysPreserved: boolean
  latestUserInputPreserved: boolean
  atomicGroupOvershoot: boolean
}

export interface SupervisorInfoResponse {
  name: string
  description: string
  memberAgents: string[]
  maxIterations: number
}

export interface ToolInfoResponse {
  name: string
  description: string
  riskLevel: ToolRiskLevel
}

export interface HealthResponse {
  status: string
  framework: string
}

// ─── Supervisor 调用（内部实现，用户不知道） ───

export interface SupervisorInvokeRequest {
  supervisorName: string
  message: string
  threadId?: string
}

export interface SupervisorInvokeResponse {
  runId: string
  threadId: string
  supervisorName: string
  success: boolean
  content: string
  errorCode?: string
  evidence: string[]
  metadata: Record<string, unknown>
  /** SUSPENDED 时为子 Agent 的 runId，前端审批时使用此 runId */
  approvalRunId?: string
  /** SUSPENDED 时为父 Supervisor 的 runId（等于 runId） */
  parentRunId?: string
  /** 是否为嵌套 Supervisor 暂停 */
  isNested: boolean
}

// ─── HITL 审批 ───

export interface PendingApprovalSummaryResponse {
  runId: string
  threadId: string
  agentName: string
  approvalId: string
  operationType: string
  operationName: string
  riskLevel: string
  reason: string
  requestedAt: string
  status: ApprovalStatus
}

export interface PendingApprovalDetailResponse {
  runId: string
  threadId: string
  agentName: string
  approvalId: string
  operationType: string
  operationName: string
  riskLevel: string
  reason: string
  requestedAt: string
  status: ApprovalStatus
  nodeName: string
  safeArguments: Record<string, unknown>
  createdAt: string
  updatedAt: string
  checkpointVersion: number
}

export interface ApprovalDecisionRequest {
  approvalId: string
  action: ApprovalAction
  comment: string
}

export interface ApprovalResumeResponse {
  runId: string
  threadId: string
  agentName: string
  success: boolean
  content: string
  errorCode?: string
  evidence: string[]
  status: RunStatus
  approvalId: string
  operationName: string
  riskLevel: string
  safeMetadata: Record<string, unknown>
  /** 子 Agent 的 runId，前端审批时使用此 runId */
  approvalRunId?: string
  /** 父 Supervisor 的 runId（嵌套恢复时非空） */
  parentRunId?: string
  /** 是否为嵌套 Supervisor 恢复 */
  isNested: boolean
}

// ─── 用户管理 ───

export interface UserSummaryResponse {
  userId: string
  username: string
  roleNames: string[]
  enabled: boolean
}

export interface CreateUserRequest {
  username: string
  password: string
  roleNames: string[]
}

export interface UpdateUserRequest {
  roleNames: string[]
  enabled: boolean
}

export interface ResetPasswordRequest {
  newPassword: string
}

// ─── 角色管理 ───

export interface RoleSummaryResponse {
  roleName: string
  description: string
  permissionCodes: string[]
}

export interface CreateRoleRequest {
  roleName: string
  description: string
  permissionCodes: string[]
}

export interface UpdateRoleRequest {
  description: string
  permissionCodes: string[]
}

// ─── 后端错误结构 ───

export interface ApiErrorResponse {
  errorCode: string
  message: string
}

// ─── 前端聊天模型 ───

export interface UserChatMessage {
  role: 'user'
  id: string
  content: string
  timestamp: number
}

export interface AssistantChatMessage {
  role: 'assistant'
  id: string
  content: string
  timestamp: number
  runId?: string
  threadId?: string
  success?: boolean
  errorCode?: string
  evidence?: string[]
  metadata?: Record<string, unknown>
  status?: RunStatus
}

export interface ToolCallChatMessage {
  role: 'tool-call'
  id: string
  toolName: string
  status: 'running' | 'success' | 'failed'
  result?: string
  error?: string
  timestamp: number
}

export interface ToolResultChatMessage {
  role: 'tool-result'
  id: string
  toolCallId: string
  toolName: string
  success: boolean
  result?: string
  timestamp: number
}

export interface ApprovalChatMessage {
  role: 'approval'
  id: string
  approvalId: string
  runId: string
  /** 子 Agent 的 runId，前端审批时使用此 runId（而非 runId） */
  approvalRunId?: string
  operationName: string
  riskLevel: string
  reason: string
  status: 'pending' | 'approved' | 'rejected'
  timestamp: number
}

export interface ErrorChatMessage {
  role: 'error'
  id: string
  content: string
  errorCode?: string
  timestamp: number
}

export type ChatMessage =
  | UserChatMessage
  | AssistantChatMessage
  | ToolCallChatMessage
  | ToolResultChatMessage
  | ApprovalChatMessage
  | ErrorChatMessage

// ─── 运行状态 ───

export type RunState =
  | { status: 'idle' }
  | { status: 'submitting' }
  | { status: 'running'; runId?: string }
  | { status: 'suspended'; runId: string }
  | { status: 'completed'; runId: string }
  | { status: 'failed'; runId?: string; errorCode?: string; message: string }

// ─── 线程索引 ───

export interface ThreadEntry {
  threadId: string
  title: string
  createdAt: number
  lastMessageAt: number
}

// ─── 会话模型 ───

/** 前端会话 — 每个会话拥有独立的消息列表 */
export interface Conversation {
  /** 前端稳定 ID，不依赖后端 */
  conversationId: string
  /** 后端首次响应后返回的真实线程 ID */
  threadId?: string
  /** 会话标题 */
  title: string
  /** 该会话的消息列表 */
  messages: ChatMessage[]
  /** 输入草稿 */
  draft: string
  /** 创建时间 */
  createdAt: number
  /** 最后消息时间 */
  lastMessageAt: number
}

// ─── 系统初始化状态 ───

export type SystemInitStatus = 'loading' | 'ready' | 'unavailable'
