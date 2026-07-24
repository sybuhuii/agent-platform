import { getSessionId, clearSession } from '../stores/authSessionStore.js'
import router from '../router/index.js'

async function request(url, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...options.headers }

  // 登录接口不附加 X-Session-Id
  if (!url.includes('/api/auth/login')) {
    const sid = getSessionId()
    if (sid) {
      headers['X-Session-Id'] = sid
    }
  }

  const config = { ...options, headers }
  if (config.body && typeof config.body === 'object') {
    config.body = JSON.stringify(config.body)
  }

  let response
  try {
    response = await fetch(url, config)
  } catch (e) {
    throw new Error('网络连接失败，请检查后端服务是否启动')
  }

  // 401: 清除 Session，跳转登录
  if (response.status === 401) {
    clearSession()
    if (router.currentRoute.value.path !== '/login') {
      router.push({ path: '/login', query: { expired: '1' } })
    }
    const err = new Error('登录状态已失效，请重新登录')
    err.status = 401
    throw err
  }

  let data
  try {
    data = await response.json()
  } catch {
    data = null
  }

  if (!response.ok) {
    const message = (data && data.message) || getDefaultMessage(response.status)
    const err = new Error(message)
    err.status = response.status
    err.errorCode = data && data.errorCode
    throw err
  }

  return data
}

function getDefaultMessage(status) {
  switch (status) {
    case 400: return '参数错误'
    case 403: return '权限不足'
    case 404: return '资源不存在'
    case 409: return '资源冲突'
    case 500: return '系统内部错误'
    case 502: return '上游服务错误'
    case 503: return '服务暂不可用'
    default: return '请求失败'
  }
}

export function get(url) {
  return request(url, { method: 'GET' })
}

export function post(url, body) {
  return request(url, { method: 'POST', body })
}

export function put(url, body) {
  return request(url, { method: 'PUT', body })
}
