import { get, post, put } from './http.js'

export function listUsers() {
  return get('/api/admin/users')
}

export function createUser(username, password, roleNames) {
  return post('/api/admin/users', { username, password, roleNames })
}

export function updateUser(userId, roleNames, enabled) {
  return put('/api/admin/users/' + userId, { roleNames, enabled })
}

export function resetPassword(userId, newPassword) {
  return post('/api/admin/users/' + userId + '/reset-password', { newPassword })
}
