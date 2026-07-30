import { z } from 'zod'
import { post, get, setSessionId } from './client'
import { loginResponseSchema, userInfoSchema } from './contracts/schemas'

export async function login(username: string, password: string) {
  const data = await post('/api/auth/login', { username, password }, loginResponseSchema)
  setSessionId(data.sessionId)
  return data
}

export async function logout(): Promise<void> {
  try {
    await post('/api/auth/logout', {}, z.object({ message: z.string() }))
  } catch {
    // logout 失败也清除前端状态
  }
}

export async function getMe(signal?: AbortSignal) {
  return get('/api/auth/me', userInfoSchema, signal)
}
