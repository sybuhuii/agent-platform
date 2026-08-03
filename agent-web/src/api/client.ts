/**
 * 统一 HTTP 客户端。
 * - Session Header 集中注入
 * - 401 集中处理（通过可注册的 onUnauthorized 回调）
 * - AbortSignal 传入 fetch，AbortError 明确区分
 * - Zod Schema 校验响应，消除 `data as T`
 * - 不在日志中输出 Session、密码、完整消息或敏感参数
 */
import { ApiError, AbortRequestError, SchemaValidationError, fromResponse } from './errors'
import type { ApiErrorResponse } from '@/types'
import type { ZodType } from 'zod'

const SESSION_KEY = 'agent_session_id'

function getSessionId(): string {
  return sessionStorage.getItem(SESSION_KEY) ?? ''
}

export function setSessionId(sid: string): void {
  if (sid) {
    sessionStorage.setItem(SESSION_KEY, sid)
  }
}

export function clearSessionId(): void {
  sessionStorage.removeItem(SESSION_KEY)
}

export { getSessionId }

// ─── 401 集中回调 ───

type UnauthorizedHandler = () => void

let onUnauthorizedHandler: UnauthorizedHandler | null = null

/** 应用启动时注册一次 401 处理器，避免循环依赖 */
export function registerUnauthorizedHandler(handler: UnauthorizedHandler): void {
  onUnauthorizedHandler = handler
}

// ─── 请求选项 ───

interface RequestOptions {
  method?: string
  body?: unknown
  headers?: Record<string, string>
  signal?: AbortSignal
}

// ─── 核心请求（返回 unknown，必须由上层校验） ───

async function requestUnknown(url: string, options: RequestOptions = {}): Promise<unknown> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...options.headers
  }

  const isPublicAuthRequest = url.includes('/api/auth/login') || url.includes('/api/auth/register')

  // 公开认证接口不附加 X-Session-Id
  if (!isPublicAuthRequest) {
    const sid = getSessionId()
    if (sid) {
      headers['X-Session-Id'] = sid
    }
  }

  const config: RequestInit = {
    method: options.method ?? 'GET',
    headers,
    signal: options.signal
  }

  if (options.body !== undefined && config.method !== 'GET') {
    config.body = JSON.stringify(options.body)
  }

  let response: Response
  try {
    response = await fetch(url, config)
  } catch (err: unknown) {
    // 明确区分用户主动取消 vs 网络断开
    if (err instanceof DOMException && err.name === 'AbortError') {
      throw new AbortRequestError()
    }
    throw new ApiError(0, '网络连接失败，请检查后端服务是否启动')
  }

  // 401: 清除 Session，触发集中回调
  if (response.status === 401) {
    // 公开认证接口自身的 401 不触发过期跳转
    if (!isPublicAuthRequest) {
      clearSessionId()
      onUnauthorizedHandler?.()
    }
    throw new ApiError(401, '登录状态已失效，请重新登录')
  }

  let data: unknown = null
  try {
    data = await response.json()
  } catch {
    data = null
  }

  if (!response.ok) {
    const errorData = data as ApiErrorResponse | null
    if (response.status === 403 && isPublicAuthRequest && errorData === null) {
      throw new ApiError(403, '当前前端地址未被后端允许，请检查跨域配置')
    }
    throw fromResponse(response.status, errorData)
  }

  return data
}

// ─── 带 Zod 校验的请求 ───

async function requestWithSchema<T>(url: string, schema: ZodType<T>, options: RequestOptions = {}): Promise<T> {
  const data = await requestUnknown(url, options)
  const result = schema.safeParse(data)
  if (!result.success) {
    // 不泄漏完整响应或原始数据
    throw new SchemaValidationError('响应数据格式异常')
  }
  return result.data
}

// ─── 公开 API ───

/** GET 请求，带 Zod 校验 */
export function get<T>(url: string, schema: ZodType<T>, signal?: AbortSignal): Promise<T> {
  return requestWithSchema<T>(url, schema, { method: 'GET', signal })
}

/** POST 请求，带 Zod 校验 */
export function post<T>(url: string, body: unknown, schema: ZodType<T>, signal?: AbortSignal): Promise<T> {
  return requestWithSchema<T>(url, schema, { method: 'POST', body, signal })
}

/** PUT 请求，带 Zod 校验 */
export function put<T>(url: string, body: unknown, schema: ZodType<T>, signal?: AbortSignal): Promise<T> {
  return requestWithSchema<T>(url, schema, { method: 'PUT', body, signal })
}
