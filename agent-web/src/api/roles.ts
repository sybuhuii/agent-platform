import { get, post, put } from './client'
import { roleSummarySchema } from './contracts/schemas'
import { z } from 'zod'

export function listRoles(signal?: AbortSignal) {
  return get('/api/admin/roles', z.array(roleSummarySchema), signal)
}

export function createRole(data: { roleName: string; description: string; permissionCodes: string[] }, signal?: AbortSignal) {
  return post('/api/admin/roles', data, roleSummarySchema, signal)
}

export function updateRole(roleName: string, data: { description: string; permissionCodes: string[] }, signal?: AbortSignal) {
  return put(`/api/admin/roles/${encodeURIComponent(roleName)}`, data, roleSummarySchema, signal)
}
