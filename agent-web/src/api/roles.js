import { get, post, put } from './http.js'

export function listRoles() {
  return get('/api/admin/roles')
}

export function createRole(roleName, description, permissionCodes) {
  return post('/api/admin/roles', { roleName, description, permissionCodes })
}

export function updateRole(roleName, description, permissionCodes) {
  return put('/api/admin/roles/' + encodeURIComponent(roleName), { description, permissionCodes })
}
