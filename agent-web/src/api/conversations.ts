import { get, put } from './client'
import { conversationThreadSchema, conversationMessageSchema } from './contracts/schemas'
import { z } from 'zod'

export interface ListThreadsParams {
  cursorThreadId?: string
  cursorLastMessageAt?: number
  pageSize?: number
}

export function listConversations(params?: ListThreadsParams, signal?: AbortSignal) {
  const searchParams = new URLSearchParams()
  if (params?.cursorThreadId) searchParams.set('cursorThreadId', params.cursorThreadId)
  if (params?.cursorLastMessageAt != null) searchParams.set('cursorLastMessageAt', String(params.cursorLastMessageAt))
  if (params?.pageSize) searchParams.set('pageSize', String(params.pageSize))
  const qs = searchParams.toString()
  const url = qs ? `/api/conversations?${qs}` : '/api/conversations'
  return get(url, z.array(conversationThreadSchema), signal)
}

export function listMessages(threadId: string, beforeSequence?: number, pageSize?: number, signal?: AbortSignal) {
  const searchParams = new URLSearchParams()
  if (beforeSequence != null) searchParams.set('beforeSequence', String(beforeSequence))
  if (pageSize) searchParams.set('pageSize', String(pageSize))
  const qs = searchParams.toString()
  const url = qs ? `/api/conversations/${threadId}/messages?${qs}` : `/api/conversations/${threadId}/messages`
  return get(url, z.array(conversationMessageSchema), signal)
}

export function renameConversation(threadId: string, title: string, signal?: AbortSignal) {
  return put(`/api/conversations/${threadId}/rename`, { title }, conversationThreadSchema, signal)
}

export function pinConversation(threadId: string, signal?: AbortSignal) {
  return put(`/api/conversations/${threadId}/pin`, {}, conversationThreadSchema, signal)
}

export function unpinConversation(threadId: string, signal?: AbortSignal) {
  return put(`/api/conversations/${threadId}/unpin`, {}, conversationThreadSchema, signal)
}

export function archiveConversation(threadId: string, signal?: AbortSignal) {
  return put(`/api/conversations/${threadId}/archive`, {}, conversationThreadSchema, signal)
}
