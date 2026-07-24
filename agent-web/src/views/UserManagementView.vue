<template>
  <div class="user-management">
    <h2>用户管理</h2>
    <div class="actions">
      <button class="btn-primary" @click="showCreateForm = true">创建用户</button>
      <button class="btn-secondary" @click="loadUsers">刷新</button>
    </div>

    <table class="data-table" v-if="users.length">
      <thead>
        <tr><th>用户ID</th><th>用户名</th><th>角色</th><th>状态</th><th>操作</th></tr>
      </thead>
      <tbody>
        <tr v-for="u in users" :key="u.userId">
          <td>{{ u.userId }}</td>
          <td>{{ u.username }}</td>
          <td><span v-for="r in u.roleNames" :key="r" class="role-tag">{{ r }}</span></td>
          <td>{{ u.enabled ? '启用' : '禁用' }}</td>
          <td>
            <button class="btn-small" @click="openEditUser(u)">编辑</button>
            <button class="btn-small btn-danger" @click="openResetPassword(u)">重置密码</button>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-if="msg" :class="msgType === 'error' ? 'error-msg' : 'success-msg'">{{ msg }}</div>

    <!-- 创建用户 -->
    <div v-if="showCreateForm" class="modal-overlay" @click.self="showCreateForm = false">
      <div class="modal-card">
        <h3>创建用户</h3>
        <div class="form-group"><label>用户名</label><input v-model="form.username" /></div>
        <div class="form-group"><label>密码</label><input v-model="form.password" type="password" /></div>
        <div class="form-group"><label>角色</label>
          <div v-for="r in roles" :key="r.roleName" class="checkbox-item">
            <label><input type="checkbox" :value="r.roleName" v-model="form.roleNames" />{{ r.roleName }}</label>
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn-primary" @click="handleCreate" :disabled="creating">{{ creating ? '创建中...' : '创建' }}</button>
          <button class="btn-secondary" @click="showCreateForm = false">取消</button>
        </div>
      </div>
    </div>

    <!-- 编辑用户 -->
    <div v-if="showEditForm" class="modal-overlay" @click.self="showEditForm = false">
      <div class="modal-card">
        <h3>编辑用户 - {{ editTarget.username }}</h3>
        <div class="form-group"><label>角色</label>
          <div v-for="r in roles" :key="r.roleName" class="checkbox-item">
            <label><input type="checkbox" :value="r.roleName" v-model="editForm.roleNames" />{{ r.roleName }}</label>
          </div>
        </div>
        <div class="form-group"><label>启用</label><input type="checkbox" v-model="editForm.enabled" /></div>
        <div class="modal-actions">
          <button class="btn-primary" @click="handleUpdate" :disabled="updating">{{ updating ? '保存中...' : '保存' }}</button>
          <button class="btn-secondary" @click="showEditForm = false">取消</button>
        </div>
      </div>
    </div>

    <!-- 重置密码 -->
    <div v-if="showResetForm" class="modal-overlay" @click.self="showResetForm = false">
      <div class="modal-card">
        <h3>重置密码 - {{ resetTarget.username }}</h3>
        <p class="warn-text">此操作将撤销该用户的所有 Session，用户需要重新登录。</p>
        <div class="form-group"><label>新密码</label><input v-model="resetForm.newPassword" type="password" /></div>
        <div class="modal-actions">
          <button class="btn-primary btn-danger" @click="handleResetPassword" :disabled="resetting">{{ resetting ? '重置中...' : '确认重置' }}</button>
          <button class="btn-secondary" @click="showResetForm = false">取消</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as userApi from '../api/users.js'
import * as roleApi from '../api/roles.js'

const users = ref([])
const roles = ref([])
const msg = ref('')
const msgType = ref('success')

const showCreateForm = ref(false)
const showEditForm = ref(false)
const showResetForm = ref(false)
const creating = ref(false)
const updating = ref(false)
const resetting = ref(false)

const form = ref({ username: '', password: '', roleNames: [] })
const editTarget = ref(null)
const editForm = ref({ roleNames: [], enabled: true })
const resetTarget = ref(null)
const resetForm = ref({ newPassword: '' })

function setMsg(text, type) {
  msg.value = text
  msgType.value = type
  setTimeout(() => { msg.value = '' }, 5000)
}

async function loadUsers() {
  try { users.value = await userApi.listUsers() } catch (e) { setMsg(e.message, 'error') }
}

async function loadRoles() {
  try { roles.value = await roleApi.listRoles() } catch {}
}

onMounted(() => { loadUsers(); loadRoles() })

function openEditUser(u) {
  editTarget.value = u
  editForm.value = { roleNames: [...u.roleNames], enabled: u.enabled }
  showEditForm.value = true
}

function openResetPassword(u) {
  resetTarget.value = u
  resetForm.value = { newPassword: '' }
  showResetForm.value = true
}

async function handleCreate() {
  creating.value = true
  try {
    await userApi.createUser(form.value.username, form.value.password, form.value.roleNames)
    showCreateForm.value = false
    form.value = { username: '', password: '', roleNames: [] }
    setMsg('用户创建成功', 'success')
    await loadUsers()
  } catch (e) { setMsg(e.message, 'error') }
  finally { creating.value = false }
}

async function handleUpdate() {
  updating.value = true
  try {
    await userApi.updateUser(editTarget.value.userId, editForm.value.roleNames, editForm.value.enabled)
    showEditForm.value = false
    setMsg('用户更新成功', 'success')
    await loadUsers()
  } catch (e) { setMsg(e.message, 'error') }
  finally { updating.value = false }
}

async function handleResetPassword() {
  resetting.value = true
  try {
    await userApi.resetPassword(resetTarget.value.userId, resetForm.value.newPassword)
    showResetForm.value = false
    resetForm.value = { newPassword: '' }
    setMsg('密码重置成功，受影响用户需要重新登录', 'success')
  } catch (e) { setMsg(e.message, 'error') }
  finally { resetting.value = false }
}
</script>
