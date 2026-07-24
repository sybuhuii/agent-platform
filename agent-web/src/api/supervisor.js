import { get, post } from './http.js'

export function listSupervisors() {
  return get('/api/framework/supervisors')
}

export function invokeSupervisor(supervisorName, message) {
  return post('/api/supervisor/invoke', { supervisorName, message })
}
