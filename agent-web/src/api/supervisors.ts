import { post } from './client'
import { supervisorInvokeResponseSchema } from './contracts/schemas'

export function invokeSupervisor(supervisorName: string, message: string, threadId?: string, signal?: AbortSignal) {
  const body: Record<string, string> = { supervisorName, message }
  if (threadId) {
    body.threadId = threadId
  }
  return post('/api/supervisor/invoke', body, supervisorInvokeResponseSchema, signal)
}
