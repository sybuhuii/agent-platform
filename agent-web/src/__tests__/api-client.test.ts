/**
 * API Client 测试 — Zod 校验、AbortError 区分、401/403 行为
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ApiError, AbortRequestError, SchemaValidationError } from '@/api/errors'
import { z } from 'zod'

// Mock fetch
const mockFetch = vi.fn()
vi.stubGlobal('fetch', mockFetch)

// Mock sessionStorage
const storage: Record<string, string> = {}
vi.stubGlobal('sessionStorage', {
  getItem: (key: string) => storage[key] ?? null,
  setItem: (key: string, value: string) => { storage[key] = value },
  removeItem: (key: string) => { delete storage[key] },
  clear: () => Object.keys(storage).forEach(k => delete storage[k])
})

describe('ApiError', () => {
  it('should create ApiError with correct properties', () => {
    const err = new ApiError(400, '参数错误', 'INVALID_ARGUMENT')
    expect(err.status).toBe(400)
    expect(err.message).toBe('参数错误')
    expect(err.errorCode).toBe('INVALID_ARGUMENT')
  })
})

describe('AbortRequestError', () => {
  it('should be distinct from ApiError', () => {
    const err = new AbortRequestError()
    expect(err).toBeInstanceOf(AbortRequestError)
    expect(err).not.toBeInstanceOf(ApiError)
    expect(err.message).toBe('已取消等待响应')
  })
})

describe('SchemaValidationError', () => {
  it('should create SchemaValidationError', () => {
    const err = new SchemaValidationError('响应数据格式异常')
    expect(err).toBeInstanceOf(SchemaValidationError)
    expect(err.message).toBe('响应数据格式异常')
  })
})

describe('Client request', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    storage['agent_session_id'] = 'test-session'
  })

  it('should validate response with Zod schema', async () => {
    const { get } = await import('@/api/client')
    const schema = z.object({ name: z.string(), value: z.number() })
    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ name: 'test', value: 42 })
    })
    const result = await get('/api/test', schema)
    expect(result).toEqual({ name: 'test', value: 42 })
  })

  it('should throw SchemaValidationError for invalid response', async () => {
    const { get } = await import('@/api/client')
    const schema = z.object({ name: z.string(), value: z.number() })
    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ name: 'test' }) // missing value
    })
    await expect(get('/api/test', schema)).rejects.toThrow(SchemaValidationError)
  })

  it('should throw AbortRequestError for aborted fetch', async () => {
    const { get } = await import('@/api/client')
    const schema = z.object({ name: z.string() })
    const abortError = new DOMException('The operation was aborted', 'AbortError')
    mockFetch.mockRejectedValueOnce(abortError)
    await expect(get('/api/test', schema)).rejects.toThrow(AbortRequestError)
  })

  it('should throw ApiError for network failure', async () => {
    const { get } = await import('@/api/client')
    const schema = z.object({ name: z.string() })
    mockFetch.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await expect(get('/api/test', schema)).rejects.toThrow(ApiError)
  })

  it('should clear session on 401', async () => {
    const { get } = await import('@/api/client')
    const schema = z.object({ name: z.string() })
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 401,
      json: () => Promise.resolve({ errorCode: 'AUTH_FAILED', message: 'Unauthorized' })
    })
    await expect(get('/api/test', schema)).rejects.toThrow(ApiError)
    expect(storage['agent_session_id']).toBeUndefined()
  })

  it('should not clear session on 403', async () => {
    const { get } = await import('@/api/client')
    const schema = z.object({ name: z.string() })
    storage['agent_session_id'] = 'test-session'
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 403,
      json: () => Promise.resolve({ errorCode: 'PERMISSION_DENIED', message: 'Forbidden' })
    })
    await expect(get('/api/test', schema)).rejects.toThrow(ApiError)
    expect(storage['agent_session_id']).toBe('test-session')
  })
})
