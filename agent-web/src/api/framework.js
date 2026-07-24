import { get } from './http.js'

export function listTools() {
  return get('/api/framework/tools')
}

export function listAgents() {
  return get('/api/framework/agents')
}

export function listSupervisors() {
  return get('/api/framework/supervisors')
}

export function health() {
  return get('/api/framework/health')
}
