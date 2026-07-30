/**
 * ChatTransport 接口 — 抽象聊天传输层。
 *
 * 业务模型：用户只与 Supervisor 交互。
 * - invokeSupervisor 是唯一的前端调用入口
 * - 不包含 invokeAgent（前端不调用 /api/agent/invoke）
 * - 后端 Agent 接口仅用于测试，生产前端不调用
 */
import type { SupervisorInvokeResponse } from '@/types'

export interface ChatTransport {
  invokeSupervisor(supervisorName: string, message: string, threadId: string | undefined, signal: AbortSignal): Promise<SupervisorInvokeResponse>
  abort(): void
}
