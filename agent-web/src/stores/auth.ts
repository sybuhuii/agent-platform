/**
 * 认证 Store — 管理当前用户与权限。
 * 认证状态只能从现有正式认证流程恢复。
 * 密码不得写入持久化存储。
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as authApi from '@/api/auth'
import { getSessionId, clearSessionId } from '@/api/client'
import type { UserInfoResponse } from '@/types'

export const useAuthStore = defineStore('auth', () => {
  const currentUser = ref<UserInfoResponse | null>(null)
  const authenticated = computed(() => currentUser.value !== null)
  const loading = ref(false)

  async function login(username: string, password: string): Promise<void> {
    loading.value = true
    try {
      await authApi.login(username, password)
      await fetchCurrentUser()
    } finally {
      loading.value = false
    }
  }

  async function fetchCurrentUser(): Promise<void> {
    const sid = getSessionId()
    if (!sid) {
      currentUser.value = null
      return
    }
    try {
      currentUser.value = await authApi.getMe()
    } catch {
      currentUser.value = null
      clearSessionId()
    }
  }

  async function logout(): Promise<void> {
    await authApi.logout()
    currentUser.value = null
    clearSessionId()
  }

  async function restoreAuth(): Promise<void> {
    const sid = getSessionId()
    if (sid) {
      loading.value = true
      try {
        await fetchCurrentUser()
      } finally {
        loading.value = false
      }
    }
  }

  function hasPermission(code: string): boolean {
    if (!currentUser.value?.permissions) return false
    return currentUser.value.permissions.includes(code)
  }

  function hasAnyPermission(codes: string[]): boolean {
    return codes.some(c => hasPermission(c))
  }

  function hasToolPermission(toolName: string): boolean {
    if (hasPermission('tool:*:invoke')) return true
    return hasPermission(`tool:${toolName}:invoke`)
  }

  return {
    currentUser,
    authenticated,
    loading,
    login,
    fetchCurrentUser,
    logout,
    restoreAuth,
    hasPermission,
    hasAnyPermission,
    hasToolPermission
  }
})
