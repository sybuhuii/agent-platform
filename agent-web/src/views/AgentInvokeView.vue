<template>
  <div class="invoke-page">
    <h2>单 Agent 调用</h2>
    <div class="invoke-form">
      <div class="form-group">
        <label>选择 Agent</label>
        <select v-model="agentName" :disabled="loading">
          <option v-for="a in agents" :key="a.name" :value="a.name">{{ a.name }} - {{ a.description }}</option>
        </select>
      </div>
      <div class="form-group">
        <label>消息</label>
        <textarea v-model="message" rows="4" :disabled="loading"></textarea>
      </div>
      <button class="btn-primary" @click="handleInvoke" :disabled="loading || !agentName || !message">
        {{ loading ? '执行中...' : '调用' }}
      </button>
      <button class="btn-secondary" @click="clearResult" v-if="result">清空结果</button>
    </div>
    <div v-if="error" class="error-msg">{{ error }}</div>
    <div v-if="result" class="result-card">
      <h3>执行结果</h3>
      <p><strong>runId：</strong>{{ result.runId }}</p>
      <p><strong>threadId：</strong>{{ result.threadId }}</p>
      <p><strong>agentName：</strong>{{ result.agentName }}</p>
      <p><strong>success：</strong>{{ result.success }}</p>
      <p v-if="result.errorCode"><strong>errorCode：</strong>{{ result.errorCode }}</p>
      <p><strong>content：</strong>{{ result.content }}</p>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as api from '../api/agent.js'
import * as fw from '../api/framework.js'

const agents = ref([])
const agentName = ref('')
const message = ref('')
const loading = ref(false)
const result = ref(null)
const error = ref('')

onMounted(async () => {
  try { agents.value = await fw.listAgents() } catch {}
  if (agents.value.length > 0) agentName.value = agents.value[0].name
})

async function handleInvoke() {
  loading.value = true
  error.value = ''
  result.value = null
  try {
    result.value = await api.invokeAgent(agentName.value, message.value)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function clearResult() {
  result.value = null
  error.value = ''
}
</script>
