import { get, post } from './http.js'

export function listAgents() {
  return get('/api/framework/agents')
}

export function invokeAgent(agentName, message) {
  return post('/api/agent/invoke', { agentName, message })
}
