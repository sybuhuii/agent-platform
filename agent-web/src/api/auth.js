import { post } from './http.js'

export function login(username, password) {
  // 登录不走 http.js 的统一 session 逻辑
  return fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  }).then(async res => {
    const data = await res.json()
    if (!res.ok) throw new Error(data.message || '认证失败')
    return data
  })
}

export function logout() {
  return post('/api/auth/logout').catch(() => {})
}

export function getMe() {
  return get('/api/auth/me')
}

// 避免 import 循环，直接用 get
import { get } from './http.js'
