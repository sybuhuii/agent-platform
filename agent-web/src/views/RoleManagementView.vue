<template>
  <div class="role-management">
    <h2>角色与权限管理</h2>
    <div class="actions">
      <button class="btn-primary" @click="showCreateForm = true">创建角色</button>
      <button class="btn-secondary" @click="loadRoles">刷新</button>
    </div>

    <table class="data-table" v-if="roles.length">
      <thead>
        <tr><th>角色名称</th><th>描述</th><th>权限编码</th><th>操作</th></tr>
      </thead>
      <tbody>
        <tr v-for="r in roles" :key="r.roleName">
          <td>{{ r.roleName }}</td>
          <td>{{ r.description }}</td>
          <td>
            <span v-for="p in r.permissionCodes" :key="p" class="perm-tag">{{ getPermissionLabel(p) }}</span>
          </td>
          <td><button class="btn-small" @click="openEditRole(r)">编辑权限</button></td>
        </tr>
      </tbody>
    </table>

    <div v-if="msg" :class="msgType === 'error' ? 'error-msg' : 'success-msg'">{{ msg }}</div>

    <!-- 创建角色 -->
    <div v-if="showCreateForm" class="modal-overlay" @click.self="showCreateForm = false">
      <div class="modal-card">
        <h3>创建角色</h3>
        <div class="form-group"><label>角色名称</label><input v-model="createForm.roleName" /></div>
        <div class="form-group"><label>描述</label><input v-model="createForm.description" /></div>
        <div class="form-group"><label>权限</label>
          <div v-for="p in ALL_PERMISSIONS" :key="p.code" class="checkbox-item">
            <label><input type="checkbox" :value="p.code" v-model="createForm.permissionCodes" />{{ p.label }}（{{ p.code }}）</label>
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn-primary" @click="handleCreate" :disabled="creating">{{ creating ? '创建中...' : '创建' }}</button>
          <button class="btn-secondary" @click="showCreateForm = false">取消</button>
        </div>
      </div>
    </div>

    <!-- 编辑角色权限 -->
    <div v-if="showEditForm" class="modal-overlay" @click.self="showEditForm = false">
      <div class="modal-card">
        <h3>编辑角色权限 - {{ editTarget.roleName }}</h3>
        <p class="warn-text">修改权限后，所有拥有此角色的用户的旧 Session 将失效，需要重新登录。</p>
        <div class="form-group"><label>描述</label><input v-model="editForm.description" /></div>
        <div class="form-group"><label>权限</label>
          <div v-for="p in ALL_PERMISSIONS" :key="p.code" class="checkbox-item">
            <label><input type="checkbox" :value="p.code" v-model="editForm.permissionCodes" />{{ p.label }}（{{ p.code }}）</label>
          </div>
          <div v-if="unknownPerms.length" class="unknown-perms">
            <p>其他权限（不在预置选项中）：</p>
            <span v-for="p in unknownPerms" :key="p" class="perm-tag perm-unknown">{{ p }}</span>
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn-primary" @click="handleUpdate" :disabled="updating">{{ updating ? '保存中...' : '保存' }}</button>
          <button class="btn-secondary" @click="showEditForm = false">取消</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import * as roleApi from '../api/roles.js'
import { ALL_PERMISSIONS, getPermissionLabel } from '../stores/permissionConstants.js'

const roles = ref([])
const msg = ref('')
const msgType = ref('success')

const showCreateForm = ref(false)
const showEditForm = ref(false)
const creating = ref(false)
const updating = ref(false)

const createForm = ref({ roleName: '', description: '', permissionCodes: [] })
const editTarget = ref(null)
const editForm = ref({ description: '', permissionCodes: [] })

const knownCodes = ALL_PERMISSIONS.map(p => p.code)
const unknownPerms = computed(() => {
  if (!editForm.value.permissionCodes) return []
  return editForm.value.permissionCodes.filter(c => !knownCodes.includes(c))
})

function setMsg(text, type) {
  msg.value = text
  msgType.value = type
  setTimeout(() => { msg.value = '' }, 5000)
}

async function loadRoles() {
  try { roles.value = await roleApi.listRoles() } catch (e) { setMsg(e.message, 'error') }
}

onMounted(() => { loadRoles() })

function openEditRole(r) {
  editTarget.value = r
  editForm.value = { description: r.description || '', permissionCodes: [...r.permissionCodes] }
  showEditForm.value = true
}

async function handleCreate() {
  creating.value = true
  try {
    const deduped = [...new Set(createForm.value.permissionCodes)]
    await roleApi.createRole(createForm.value.roleName, createForm.value.description, deduped)
    showCreateForm.value = false
    createForm.value = { roleName: '', description: '', permissionCodes: [] }
    setMsg('角色创建成功', 'success')
    await loadRoles()
  } catch (e) { setMsg(e.message, 'error') }
  finally { creating.value = false }
}

async function handleUpdate() {
  updating.value = true
  try {
    const deduped = [...new Set(editForm.value.permissionCodes)]
    await roleApi.updateRole(editTarget.value.roleName, editForm.value.description, deduped)
    showEditForm.value = false
    setMsg('角色权限更新成功，受影响用户的旧 Session 将失效，需要重新登录', 'success')
    await loadRoles()
  } catch (e) { setMsg(e.message, 'error') }
  finally { updating.value = false }
}
</script>
