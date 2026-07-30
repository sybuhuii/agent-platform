/**
 * 结构化 API 错误。
 * 禁止到处抛裸字符串。
 */
import type { ApiErrorResponse } from '@/types'

export class ApiError extends Error {
  readonly status: number
  readonly errorCode?: string

  constructor(status: number, message: string, errorCode?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.errorCode = errorCode
  }
}

/** 用户主动取消请求 */
export class AbortRequestError extends Error {
  constructor() {
    super('已取消等待响应')
    this.name = 'AbortRequestError'
  }
}

/** Zod 校验失败 — 后端返回的数据结构不符合预期 */
export class SchemaValidationError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'SchemaValidationError'
  }
}

/** 从后端响应构造 ApiError */
export function fromResponse(status: number, data: ApiErrorResponse | null): ApiError {
  if (data) {
    return new ApiError(status, data.message, data.errorCode)
  }
  return new ApiError(status, getDefaultMessage(status))
}

function getDefaultMessage(status: number): string {
  switch (status) {
    case 400: return '请求参数错误'
    case 401: return '登录状态已失效，请重新登录'
    case 403: return '权限不足'
    case 404: return '资源不存在'
    case 409: return '资源冲突'
    case 500: return '系统内部错误'
    case 502: return '上游服务错误'
    case 503: return '服务暂不可用'
    default: return '请求失败'
  }
}
