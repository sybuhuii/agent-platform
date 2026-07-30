/**
 * 权限路由判断测试
 */
import { describe, it, expect } from 'vitest'
import { useAuthStore } from '@/stores/auth'
import { createPinia, setActivePinia } from 'pinia'

describe('useAuthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('should start with no user', () => {
    const store = useAuthStore()
    expect(store.authenticated).toBe(false)
    expect(store.currentUser).toBeNull()
  })

  it('hasPermission should return false when no user', () => {
    const store = useAuthStore()
    expect(store.hasPermission('tool:*:invoke')).toBe(false)
  })

  it('hasPermission should return true when user has permission', () => {
    const store = useAuthStore()
    store.currentUser = {
      userId: '1',
      username: 'admin',
      roles: ['ADMIN'],
      permissions: ['tool:*:invoke', 'security:user:read']
    }
    expect(store.hasPermission('tool:*:invoke')).toBe(true)
    expect(store.hasPermission('security:user:read')).toBe(true)
    expect(store.hasPermission('security:role:write')).toBe(false)
  })

  it('hasToolPermission should check wildcard', () => {
    const store = useAuthStore()
    store.currentUser = {
      userId: '1',
      username: 'admin',
      roles: ['ADMIN'],
      permissions: ['tool:*:invoke']
    }
    expect(store.hasToolPermission('calculator')).toBe(true)
    expect(store.hasToolPermission('any_tool')).toBe(true)
  })

  it('hasToolPermission should check specific tool', () => {
    const store = useAuthStore()
    store.currentUser = {
      userId: '1',
      username: 'visitor',
      roles: ['VISITOR'],
      permissions: ['tool:calculator:invoke']
    }
    expect(store.hasToolPermission('calculator')).toBe(true)
    expect(store.hasToolPermission('echo')).toBe(false)
  })

  it('hasAnyPermission should return true if any permission matches', () => {
    const store = useAuthStore()
    store.currentUser = {
      userId: '1',
      username: 'admin',
      roles: ['ADMIN'],
      permissions: ['security:user:read']
    }
    expect(store.hasAnyPermission(['security:user:write', 'security:user:read'])).toBe(true)
    expect(store.hasAnyPermission(['security:role:write', 'security:session:revoke'])).toBe(false)
  })
})
