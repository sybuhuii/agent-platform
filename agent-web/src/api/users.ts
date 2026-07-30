import { get, post, put } from './client'
import { userSummarySchema } from './contracts/schemas'
import { z } from 'zod'

export function listUsers(signal?: AbortSignal) {
  return get('/api/admin/users', z.array(userSummarySchema), signal)
}

export function createUser(data: { username: string; password: string; roleNames: string[] }, signal?: AbortSignal) {
  return post('/api/admin/users', data, userSummarySchema, signal)
}

export function updateUser(userId: string, data: { roleNames: string[]; enabled: boolean }, signal?: AbortSignal) {
  return put(`/api/admin/users/${encodeURIComponent(userId)}`, data, userSummarySchema, signal)
}

export function resetPassword(userId: string, data: { newPassword: string }, signal?: AbortSignal) {
  return post(`/api/admin/users/${encodeURIComponent(userId)}/reset-password`, data, z.object({ message: z.string() }), signal)
}
