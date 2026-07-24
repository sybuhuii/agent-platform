<template>
  <div class="permission-demo">
    <h2>权限差异演示</h2>
    <div class="info-banner">
      <p><strong>当前用户：</strong>{{ auth.currentUser?.username }}</p>
      <p><strong>角色：</strong><span v-for="r in auth.currentUser?.roles" :key="r" class="role-tag">{{ r }}</span></p>
      <p class="hint">请分别用 admin 和 visitor 登录测试同一请求，观察工具权限差异。</p>
      <p class="hint">权限拒绝不是登录失败，而是工具 ACL 拒绝后由 Agent 解释。</p>
    </div>

    <div class="scenario-list">
      <div v-for="s in scenarios" :key="s.id" class="scenario-card">
        <h3>{{ s.title }}</h3>
        <p class="scenario-desc">{{ s.description }}</p>
        <p><strong>工具：</strong>{{ s.tool }}</p>
        <p>
          <strong>权限预期：</strong>
          <span v-if="hasToolPermission(s.tool)" class="perm-allowed">允许</span>
          <span v-else-if="hasPermission('tool:*:invoke')" class="perm-wildcard">由通配权限允许</span>
          <span v-else class="perm-denied">预计拒绝</span>
        </p>
        <div class="scenario-form">
          <div class="form-group">
            <label>Agent</label>
            <select v-model="s.agentName" :disabled="s.loading">
              <option value="utility_agent">utility_agent</option>
              <option v-if="s.tool === 'calculator'" value="calculator_agent">calculator_agent</option>
            </select>
          </div>
          <button class="btn-primary" @click="runScenario(s)" :disabled="s.loading">执行</button>
        </div>
        <div v-if="s.result" class="result-card">
          <p><strong>success：</strong>{{ s.result.success }}</p>
          <p v-if="s.result.errorCode"><strong>errorCode：</strong>{{ s.result.errorCode }}</p>
          <p><strong>回答：</strong>{{ s.result.content }}</p>
        </div>
        <div v-if="s.error" class="error-msg">{{ s.error }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useAuth, hasPermission, hasToolPermission } from '../stores/authStore.js'
import { invokeAgent } from '../api/agent.js'

const auth = useAuth()

const scenarios = ref([
  {
    id: 1,
    title: '场景一：允许工具 (calculator)',
    description: '请必须使用calculator计算12*(3+5)，并根据真实工具结果回答。',
    tool: 'calculator',
    agentName: 'utility_agent',
    loading: false,
    result: null,
    error: ''
  },
  {
    id: 2,
    title: '场景二：受限工具 (text_search)',
    description: '请必须使用text_search在以下文本中搜索Java：Java Agent Framework / Python Service / Java Runtime',
    tool: 'text_search',
    agentName: 'utility_agent',
    loading: false,
    result: null,
    error: ''
  },
  {
    id: 3,
    title: '场景三：current_time',
    description: '请必须使用current_time查询Asia/Shanghai当前时间。',
    tool: 'current_time',
    agentName: 'utility_agent',
    loading: false,
    result: null,
    error: ''
  }
])

async function runScenario(s) {
  s.loading = true
  s.result = null
  s.error = ''
  try {
    s.result = await invokeAgent(s.agentName, s.description)
  } catch (e) {
    s.error = e.message
  } finally {
    s.loading = false
  }
}
</script>
