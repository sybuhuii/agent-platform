/**
 * 角色管理页 — 保留并重新设计。
 */
<script setup lang="ts">
import { ref } from 'vue'
import { useRoles } from '@/queries'
import * as rolesApi from '@/api/roles'
import { useQueryClient } from '@tanstack/vue-query'
import { ALL_PERMISSIONS, getPermissionLabel } from '@/stores/permissions'
import type { RoleSummaryResponse } from '@/types'

const queryClient = useQueryClient()
const rolesQuery = useRoles()

const showCreateModal = ref(false)
const showEditModal = ref(false)
const editingRole = ref<RoleSummaryResponse | null>(null)

const newName = ref('')
const newDescription = ref('')
const newPermCodes = ref<string[]>([])
const editDescription = ref('')
const editPermCodes = ref<string[]>([])
const error = ref('')
const success = ref('')

function clearMessages() {
  error.value = ''
  success.value = ''
}

async function handleCreate() {
  if (!newName.value) {
    error.value = '角色名不能为空'
    return
  }
  try {
    await rolesApi.createRole({
      roleName: newName.value,
      description: newDescription.value,
      permissionCodes: newPermCodes.value
    })
    showCreateModal.value = false
    newName.value = ''
    newDescription.value = ''
    newPermCodes.value = []
    queryClient.invalidateQueries({ queryKey: ['admin', 'roles'] })
    success.value = '角色创建成功'
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : '创建失败'
  }
}

function openEditModal(role: RoleSummaryResponse) {
  editingRole.value = role
  editDescription.value = role.description
  editPermCodes.value = [...role.permissionCodes]
  showEditModal.value = true
  clearMessages()
}

async function handleUpdate() {
  if (!editingRole.value) return
  try {
    await rolesApi.updateRole(editingRole.value.roleName, {
      description: editDescription.value,
      permissionCodes: editPermCodes.value
    })
    showEditModal.value = false
    editingRole.value = null
    queryClient.invalidateQueries({ queryKey: ['admin', 'roles'] })
    success.value = '角色更新成功'
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : '更新失败'
  }
}

function togglePerm(permArray: string[], code: string): string[] {
  const idx = permArray.indexOf(code)
  if (idx >= 0) {
    return [...permArray.slice(0, idx), ...permArray.slice(idx + 1)]
  }
  return [...permArray, code]
}

function toggleNewPerm(code: string) {
  newPermCodes.value = togglePerm(newPermCodes.value, code)
}

function toggleEditPerm(code: string) {
  editPermCodes.value = togglePerm(editPermCodes.value, code)
}
</script>

<template>
  <div class="p-6 max-w-4xl mx-auto">
    <div class="flex items-center justify-between mb-4">
      <h1 class="text-xl font-semibold">角色管理</h1>
      <button
        class="inline-flex items-center rounded-lg bg-[var(--accent)] text-[var(--accent-foreground)] px-4 py-2 text-sm font-medium hover:bg-[var(--accent)]/90 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
        @click="showCreateModal = true; clearMessages()"
      >
        创建角色
      </button>
    </div>

    <div v-if="error" role="alert" class="rounded-lg border border-[var(--destructive)]/30 bg-[var(--destructive)]/5 p-3 text-sm text-[var(--destructive)] mb-4">
      {{ error }}
    </div>
    <div v-if="success" class="rounded-lg border border-[var(--success)]/30 bg-[var(--success)]/5 p-3 text-sm text-[var(--success)] mb-4">
      {{ success }}
    </div>

    <div v-if="rolesQuery.isLoading.value" class="text-sm text-[var(--muted-foreground)]">加载中...</div>

    <div v-else class="space-y-3">
      <div
        v-for="role in rolesQuery.data.value"
        :key="role.roleName"
        class="rounded-lg border border-[var(--card-border)] bg-[var(--card)] p-4"
      >
        <div class="flex items-center justify-between mb-2">
          <h3 class="font-medium">{{ role.roleName }}</h3>
          <button class="text-xs text-[var(--accent)] hover:underline" @click="openEditModal(role)">编辑</button>
        </div>
        <p v-if="role.description" class="text-sm text-[var(--muted-foreground)] mb-2">{{ role.description }}</p>
        <div class="flex flex-wrap gap-1">
          <span v-for="code in role.permissionCodes" :key="code" class="text-xs bg-[var(--muted)] px-1.5 py-0.5 rounded">
            {{ getPermissionLabel(code) }}
          </span>
        </div>
      </div>
    </div>

    <!-- 创建角色弹窗 -->
    <Teleport to="body">
      <div v-if="showCreateModal" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="showCreateModal = false">
        <div class="w-full max-w-md rounded-xl bg-[var(--card)] border border-[var(--card-border)] p-6 shadow-lg max-h-[80vh] overflow-y-auto">
          <h2 class="text-lg font-semibold mb-4">创建角色</h2>
          <div class="space-y-3">
            <div>
              <label class="block text-sm text-[var(--muted-foreground)] mb-1">角色名</label>
              <input v-model="newName" class="w-full rounded-lg border border-[var(--input)] bg-transparent px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]" />
            </div>
            <div>
              <label class="block text-sm text-[var(--muted-foreground)] mb-1">描述</label>
              <input v-model="newDescription" class="w-full rounded-lg border border-[var(--input)] bg-transparent px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]" />
            </div>
            <div>
              <label class="block text-sm text-[var(--muted-foreground)] mb-1">权限</label>
              <div class="space-y-1 max-h-60 overflow-y-auto">
                <label v-for="p in ALL_PERMISSIONS" :key="p.code" class="flex items-center gap-2 text-sm">
                  <input type="checkbox" :checked="newPermCodes.includes(p.code)" @change="toggleNewPerm(p.code)" />
                  {{ p.label }}
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

    <!-- 编辑角色弹窗 -->
    <Teleport to="body">
      <div v-if="showEditModal" class="fixed inset-0 z-50 flex items-center justify-center bg-black/50" @click.self="showEditModal = false">
        <div class="w-full max-w-md rounded-xl bg-[var(--card)] border border-[var(--card-border)] p-6 shadow-lg max-h-[80vh] overflow-y-auto">
          <h2 class="text-lg font-semibold mb-4">编辑角色：{{ editingRole?.roleName }}</h2>
          <div class="space-y-3">
            <div>
              <label class="block text-sm text-[var(--muted-foreground)] mb-1">描述</label>
              <input v-model="editDescription" class="w-full rounded-lg border border-[var(--input)] bg-transparent px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]" />
            </div>
            <div>
              <label class="block text-sm text-[var(--muted-foreground)] mb-1">权限</label>
              <div class="space-y-1 max-h-60 overflow-y-auto">
                <label v-for="p in ALL_PERMISSIONS" :key="p.code" class="flex items-center gap-2 text-sm">
                  <input type="checkbox" :checked="editPermCodes.includes(p.code)" @change="toggleEditPerm(p.code)" />
                  {{ p.label }}
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
  </div>
</template>
