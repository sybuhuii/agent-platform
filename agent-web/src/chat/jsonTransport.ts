/**
 * JSON Transport — 当前后端仅支持同步 JSON 响应。
 *
 * 业务模型：用户只与 Supervisor 交互。
 * - 只调用 /api/supervisor/invoke
 * - 不调用 /api/agent/invoke
 * - AbortSignal 全链路传递
 * - 不得用定时器逐字输出制造假流式效果
 */
import type { ChatTransport } from './chatTransport'
import { invokeSupervisor } from '@/api/supervisors'

export const jsonTransport: ChatTransport = {
  async invokeSupervisor(supervisorName, message, threadId, signal) {
    return await invokeSupervisor(supervisorName, message, threadId, signal)
  },

  abort() {
    // Abort 由 Chat Store 的 AbortController.abort() 触发
  }
}
