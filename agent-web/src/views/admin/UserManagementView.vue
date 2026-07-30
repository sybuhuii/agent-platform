/**
 * 用户管理页 — 保留并重新设计，与聊天产品共享设计 Token 和组件。
 */
<script setup lang="ts">
import { ref } from 'vue'
import { useUsers } from '@/queries'
import * as usersApi from '@/api/users'
import * as rolesApi from '@/api/roles'
import { useRoles } from '@/queries'
import { useQueryClient } from '@tanstack/vue-query'
import type { UserSummaryResponse } from '@/types'

const queryClient = useQueryClient()
const usersQuery = useUsers()
const rolesQuery = useRoles()

const showCreateModal = ref(false)
const showEditModal = ref(false)
const editingUser = ref<UserSummaryResponse | null>(null)

const newUsername = ref('')
const newPassword = ref('')
const newRoleNames = ref<string[]>([])
const editRoleNames = ref<string[]>([])
const editEnabled = ref(true)
const resetPasswordUserId = ref<string | null>(null)
const resetPasswordValue = ref('')
const error = ref('')
const success = ref('')

function clearMessages() {
  error.value = ''
  success.value = ''
}

async function handleCreate() {
  if (!newUsername.value || !newPassword.value) {
    error.value = '用户名和密码不能为空'
    return
  }
  try {
    await usersApi.createUser({
      username: newUsername.value,
      password: newPassword.value,
      roleNames: newRoleNames.value
    })
    showCreateModal.value = false
    newUsername.value = ''
    newPassword.value = ''
    newRoleNames.value = []
    queryClient.invalidateQueries({ queryKey: ['admin', 'users'] })
    success.value = '用户创建成功'
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : '创建失败'
  }
}

function openEditModal(user: UserSummaryResponse) {
  editingUser.value = user
  editRoleNames.value = [...user.roleNames]
  editEnabled.value = user.enabled
  showEditModal.value = true
  clearMessages()
}

async function handleUpdate() {
  if (!editingUser.value) return
  try {
    await usersApi.updateUser(editingUser.value.userId, {
      roleNames: editRoleNames.value,
      enabled: editEnabled.value
    })
    showEditModal.value = false
    editingUser.value = null
    queryClient.invalidateQueries({ queryKey: ['admin', 'users'] })
    success.value = '用户更新成功'
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : '更新失败'
  }
}

async function handleResetPassword() {
  if (!resetPasswordUserId.value || !resetPasswordValue.value) return
  try {
    await usersApi.resetPassword(resetPasswordUserId.value, { newPassword: resetPasswordValue.value })
    resetPasswordUserId.value = null
    resetPasswordValue.value = ''
    success.value = '密码重置成功'
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : '重置失败'
  }
}

function toggleRole(roles: string[], roleName: string): string[] {
  const idx = roles.indexOf(roleName)
  if (idx >= 0) {
    return [...roles.slice(0, idx), ...roles.slice(idx + 1)]
  }
  return [...roles, roleName]
}

function toggleNewRole(roleName: string) {
  newRoleNames.value = toggleRole(newRoleNames.value, roleName)
}

function toggleEditRole(roleName: string) {
  editRoleNames.value = toggleRole(editRoleNames.value, roleName)
}
</script>

<template>
  <div class="p-6 max-w-4xl mx-auto">
    <div class="flex items-center justify-between mb-4">
      <h1 class="text-xl font-semibold">用户管理</h1>
      <button
        class="inline-flex items-center rounded-lg bg-[var(--accent)] text-[var(--accent-foreground)] px-4 py-2 text-sm font-medium hover:bg-[var(--accent)]/90 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
        @click="showCreateModal = true; clearMessages()"
      >
        创建用户
      </button>
    </div>

    <div v-if="error" role="alert" class="rounded-lg border border-[var(--destructive)]/30 bg-[var(--destructive)]/5 p-3 text-sm text-[var(--destructive)] mb-4">
      {{ error }}
    </div>
    <div v-if="success" class="rounded-lg border border-[var(--success)]/30 bg-[var(--success)]/5 p-3 text-sm text-[var(--success)] mb-4">
      {{ success }}
    </div>

    <div v-if="usersQuery.isLoading.value" class="text-sm text-[var(--muted-foreground)]">加载中...</div>

    <div v-else class="rounded-lg border border-[var(--card-border)] bg-[var(--card)] overflow-hidden">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-[var(--border)] bg-[var(--muted)]">
            <th class="text-left px-4 py-3 font-medium text-[var(--muted-foreground)]">用户名</th>
            <th class="text-left px-4 py-3 font-medium text-[var(--muted-foreground)]">角色</th>
            <th class="text-left px-4 py-3 font-medium text-[var(--muted-foreground)]">状态</th>
            <th class="text-right px-4 py-3 font-medium text-[var(--muted-foreground)]">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in usersQuery.data.value" :key="user.userId" class="border-b border-[var(--border)] last:border-0">
            <td class="px-4 py-3">{{ user.username }}</td>
            <td class="px-4 py-3">
              <span v-for="role in user.roleNames" :key="role" class="inline-block text-xs bg-[var(--muted)] px-1.5 py-0.5 rounded mr-1">{{ role }}</span>
            </td>
            <td class="px-4 py-3">
              <span :class="user.enabled ? 'text-[var(--success)]' : 'text-[var(--destructive)]'" class="text-xs font-medium">
                {{ user.enabled ? '启用' : '禁用' }}
              </span>
            </td>
            <td class="px-4 py-3 text-right space-x-2">
              <button class="text-xs text-[var(--accent)] hover:underline" @click="openEditModal(user)">编辑</button>
              <button class="text-xs text-[var(--accent)] hover:underline" @click="resetPasswordUserId = user.userId; resetPasswordValue = ''; clearMessages()">重置密码</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 创建用户弹窗 -->
    <Teleport to="body">
      <div v-if="showCreateModal" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="showCreateModal = false">
        <div class="w-full max-w-md rounded-xl bg-[var(--card)] border border-[var(--card-border)] p-6 shadow-lg">
          <h2 class="text-lg font-semibold mb-4">创建用户</h2>
          <div class="space-y-3">
            <div>
              <label class="block text-sm text-[var(--muted-foreground)] mb-1">用户名</label>
              <input v-model="newUsername" class="w-full rounded-lg border border-[var(--input)] bg-transparent px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]" />
            </div>
            <div>
              <label class="block text-sm text-[var(--muted-foreground)] mb-1">密码</label>
              <input v-model="newPassword" type="password" class="w-full rounded-lg border border-[var(--input)] bg-transparent px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]" />
            </div>
            <div>
              <label class="block text-sm text-[var(--muted-foreground)] mb-1">角色</label>
              <div class="space-y-1">
                <label v-for="role in rolesQuery.data.value" :key="role.roleName" class="flex items-center gap-2 text-sm">
                  <input type="checkbox" :checked="newRoleNames.includes(role.roleName)" @change="toggleNewRole(role.roleName)" />
                  {{ role.roleName }}
                </label>
              </div>
            </div>
          </div>
          <div class="flex justify-end gap-2 mt-6">
            <button class="rounded-lg border border-[var(--border)] px-4 py-2 text-sm hover:bg-[var(--muted)] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]" @click="showCreateModal = false">取消</button>
            <button class="rounded-lg bg-[var(--accent)] text-[var(--accent-foreground)] px-4 py-2 text-sm font-medium hover:bg-[var(--accent)]/90 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]" @click="handleCreate">创建</button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 编辑用户弹窗 -->
    <Teleport to="body">
      <div v-if="showEditModal" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="showEditModal = false">
        <div class="w-full max-w-md rounded-xl bg-[var(--card)] border border-[var(--card-border)] p-6 shadow-lg">
          <h2 class="text-lg font-semibold mb-4">编辑用户：{{ editingUser?.username }}</h2>
          <div class="space-y-3">
            <div>
              <label class="flex items-center gap-2 text-sm">
                <input v-model="editEnabled" type="checkbox" />
                启用
              </label>
            </div>
            <div>
              <label class="block text-sm text-[var(--muted-foreground)] mb-1">角色</label>
              <div class="space-y-1">
                <label v-for="role in rolesQuery.data.value" :key="role.roleName" class="flex items-center gap-2 text-sm">
                  <input type="checkbox" :checked="editRoleNames.includes(role.roleName)" @change="toggleEditRole(role.roleName)" />
                  {{ role.roleName }}
                </label>
              </div>
            </div>
          </div>
          <div class="flex justify-end gap-2 mt-6">
            <button class="rounded-lg border border-[var(--border)] px-4 py-2 text-sm hover:bg-[var(--muted)] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]" @click="showEditModal = false">取消</button>
            <button class="rounded-lg bg-[var(--accent)] text-[var(--accent-foreground)] px-4 py-2 text-sm font-medium hover:bg-[var(--accent)]/90 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]" @click="handleUpdate">保存</button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 重置密码弹窗 -->
    <Teleport to="body">
      <div v-if="resetPasswordUserId" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="resetPasswordUserId = null">
        <div class="w-full max-w-sm rounded-xl bg-[var(--card)] border border-[var(--card-border)] p-6 shadow-lg">
          <h2 class="text-lg font-semibold mb-4">重置密码</h2>
          <div>
            <label class="block text-sm text-[var(--muted-foreground)] mb-1">新密码</label>
            <input v-model="resetPasswordValue" type="password" class="w-full rounded-lg border border-[var(--input)] bg-transparent px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]" />
          </div>
          <div class="flex justify-end gap-2 mt-6">
            <button class="rounded-lg border border-[var(--border)] px-4 py-2 text-sm hover:bg-[var(--muted)] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]" @click="resetPasswordUserId = null">取消</button>
            <button class="rounded-lg bg-[var(--accent)] text-[var(--accent-foreground)] px-4 py-2 text-sm font-medium hover:bg-[var(--accent)]/90 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]" @click="handleResetPassword">确认</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>
