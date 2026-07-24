<template>
  <div class="dashboard">
    <h2>首页</h2>
    <div v-if="auth.currentUser" class="info-grid">
      <div class="info-card">
        <h3>当前用户</h3>
        <p><strong>用户名：</strong>{{ auth.currentUser.username }}</p>
        <p><strong>用户ID：</strong>{{ auth.currentUser.userId }}</p>
        <p>
          <strong>角色：</strong>
          <span v-for="role in auth.currentUser.roles" :key="role" class="role-tag">{{ role }}</span>
        </p>
      </div>
      <div class="info-card">
        <h3>工具权限</h3>
        <div v-if="hasPermission('tool:*:invoke')" class="perm-item perm-wildcard">
          全部工具调用权限（tool:*:invoke）
        </div>
        <div v-else>
          <div v-for="p in toolPerms" :key="p" class="perm-item perm-allowed">
            {{ getPermissionLabel(p) }}
          </div>
          <div v-for="p in missingToolPerms" :key="p" class="perm-item perm-denied">
            {{ getPermissionLabel(p) }}（无权限）
          </div>
        </div>
      </div>
      <div class="info-card">
        <h3>管理权限</h3>
        <div v-for="p in mgmtPerms" :key="p" class="perm-item perm-allowed">
          {{ getPermissionLabel(p) }}
        </div>
        <div v-if="mgmtPerms.length === 0" class="perm-item perm-denied">无管理权限</div>
      </div>
      <div class="info-card">
        <h3>框架资源</h3>
        <p><strong>已注册 Agent：</strong>{{ agents.length }}</p>
        <p><strong>已注册 Supervisor：</strong>{{ supervisors.length }}</p>
        <p><strong>已注册工具：</strong>{{ tools.length }}（框架注册，不代表全部有权调用）</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useAuth, hasPermission } from '../stores/authStore.js'
import { TOOL_PERMISSIONS, MANAGEMENT_PERMISSIONS, getPermissionLabel } from '../stores/permissionConstants.js'
import * as framework from '../api/framework.js'

const auth = useAuth()
const agents = ref([])
const supervisors = ref([])
const tools = ref([])

const toolPermCodes = TOOL_PERMISSIONS.map(p => p.code)
const mgmtPermCodes = MANAGEMENT_PERMISSIONS.map(p => p.code)

const toolPerms = computed(() => {
  if (!auth.currentUser?.permissions) return []
  return auth.currentUser.permissions.filter(p => toolPermCodes.includes(p))
})

const missingToolPerms = computed(() => {
  if (!auth.currentUser?.permissions) return toolPermCodes.filter(c => c !== 'tool:*:invoke')
  if (hasPermission('tool:*:invoke')) return []
  return toolPermCodes.filter(c => !auth.currentUser.permissions.includes(c))
})

const mgmtPerms = computed(() => {
  if (!auth.currentUser?.permissions) return []
  return auth.currentUser.permissions.filter(p => mgmtPermCodes.includes(p))
})

onMounted(async () => {
  try { agents.value = await framework.listAgents() } catch {}
  try { supervisors.value = await framework.listSupervisors() } catch {}
  try { tools.value = await framework.listTools() } catch {}
})
</script>
