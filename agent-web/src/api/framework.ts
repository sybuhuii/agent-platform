import { get } from './client'
import { supervisorInfoSchema, toolInfoSchema, healthSchema } from './contracts/schemas'
import { z } from 'zod'

export function listSupervisors(signal?: AbortSignal) {
  return get('/api/framework/supervisors', z.array(supervisorInfoSchema), signal)
}

export function listTools(signal?: AbortSignal) {
  return get('/api/framework/tools', z.array(toolInfoSchema), signal)
}

export function health(signal?: AbortSignal) {
  return get('/api/framework/health', healthSchema, signal)
}
