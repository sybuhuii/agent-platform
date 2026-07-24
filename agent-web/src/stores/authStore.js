import { reactive } from 'vue'
import { get } from '../api/http.js'
import { getSessionId, setSessionId, clearSession } from './authSessionStore.js'

const auth = reactive({
  currentUser: null,
  authenticated: false,
  loading: false
})

export function useAuth() {
  return auth
}

export async function directLogin(username, password) {
  auth.loading = true
  try {
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    })
    let data
    try {
      data = await response.json()
    } catch {
      data = null
    }
    if (!response.ok) {
      throw new Error((data && data.message) || '认证失败')
    }
    // 保存 sessionId
    setSessionId(data.sessionId)
    // 获取当前身份
    await fetchCurrentUser()
  } finally {
    auth.loading = false
  }
}

export async function fetchCurrentUser() {
  const sid = getSessionId()
  if (!sid) {
    auth.currentUser = null
    auth.authenticated = false
    return
  }
  try {
    const data = await get('/api/auth/me')
    auth.currentUser = data
    auth.authenticated = true
  } catch {
    auth.currentUser = null
    auth.authenticated = false
    clearSession()
  }
}

export async function logout() {
  try {
    await get('/api/auth/logout')
  } catch {
    // logout 失败也清除前端状态
  }
  auth.currentUser = null
  auth.authenticated = false
  clearSession()
}

export function hasPermission(code) {
  if (!auth.currentUser || !auth.currentUser.permissions) return false
  return auth.currentUser.permissions.includes(code)
}

export function hasAnyPermission(codes) {
  return codes.some(c => hasPermission(c))
}

export function hasToolPermission(toolName) {
  if (hasPermission('tool:*:invoke')) return true
  return hasPermission('tool:' + toolName + ':invoke')
}

// 应用启动时恢复认证状态
export async function restoreAuth() {
  const sid = getSessionId()
  if (sid) {
    auth.loading = true
    try {
      await fetchCurrentUser()
    } finally {
      auth.loading = false
    }
  }
}
