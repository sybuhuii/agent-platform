/**
 * API 错误映射测试
 */
import { describe, it, expect } from 'vitest'
import { ApiError, fromResponse } from '@/api/errors'

describe('ApiError', () => {
  it('should create ApiError with correct properties', () => {
    const err = new ApiError(400, '参数错误', 'INVALID_ARGUMENT')
    expect(err.status).toBe(400)
    expect(err.message).toBe('参数错误')
    expect(err.errorCode).toBe('INVALID_ARGUMENT')
    expect(err.name).toBe('ApiError')
  })

  it('should create ApiError from response with data', () => {
    const err = fromResponse(403, { errorCode: 'PERMISSION_DENIED', message: '权限不足' })
    expect(err.status).toBe(403)
    expect(err.message).toBe('权限不足')
    expect(err.errorCode).toBe('PERMISSION_DENIED')
  })

  it('should create ApiError from response without data', () => {
    const err = fromResponse(500, null)
    expect(err.status).toBe(500)
    expect(err.message).toBe('系统内部错误')
    expect(err.errorCode).toBeUndefined()
  })

  it('should map common status codes to default messages', () => {
    expect(fromResponse(400, null).message).toBe('请求参数错误')
    expect(fromResponse(401, null).message).toBe('登录状态已失效，请重新登录')
    expect(fromResponse(403, null).message).toBe('权限不足')
    expect(fromResponse(404, null).message).toBe('资源不存在')
    expect(fromResponse(409, null).message).toBe('资源冲突')
    expect(fromResponse(502, null).message).toBe('上游服务错误')
    expect(fromResponse(503, null).message).toBe('服务暂不可用')
    expect(fromResponse(418, null).message).toBe('请求失败')
  })
})
