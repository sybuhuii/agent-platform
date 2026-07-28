<template>
  <div class="invoke-page">
    <h2>Supervisor 调用</h2>
    <div class="invoke-form">
      <div class="form-group">
        <label>选择 Supervisor</label>
        <select v-model="supervisorName" :disabled="loading">
          <option v-for="s in supervisors" :key="s.name" :value="s.name">{{ s.name }} - {{ s.description }}</option>
        </select>
      </div>
      <div class="form-group">
        <label>消息</label>
        <textarea v-model="message" rows="4" :disabled="loading"></textarea>
      </div>
      <button class="btn-primary" @click="handleInvoke" :disabled="loading || !supervisorName || !message">
        {{ loading ? '执行中...' : '调用' }}
      </button>
      <button class="btn-secondary" @click="clearResult" v-if="result">清空结果</button>
    </div>
    <div v-if="error" class="error-msg">{{ error }}</div>
    <div v-if="result" class="result-card">
      <h3>执行结果</h3>
      <p><strong>runId：</strong>{{ result.runId }}</p>
      <p><strong>threadId：</strong>{{ result.threadId }}</p>
      <p><strong>supervisorName：</strong>{{ result.supervisorName }}</p>
      <p><strong>success：</strong>{{ result.success }}</p>
      <p v-if="result.errorCode"><strong>errorCode：</strong>{{ result.errorCode }}</p>
      <p><strong>content：</strong>{{ result.content }}</p>
      <details v-if="hasContextMetadata" style="margin-top:8px">
        <summary style="cursor:pointer;color:#646cff">上下文处理信息</summary>
        <div style="margin-top:6px;padding:8px;background:#f8f8f8;border-radius:4px">
          <p v-if="result.metadata['context.processedTokenCount'] != null">
            <strong>处理后Token数：</strong>{{ result.metadata['context.processedTokenCount'] }}
          </p>
          <p v-if="result.metadata['context.effectiveMessageBudget'] != null">
            <strong>有效Token预算：</strong>{{ result.metadata['context.effectiveMessageBudget'] }}
          </p>
          <p v-if="result.metadata['context.summaryApplied'] != null">
            <strong>摘要应用：</strong>{{ result.metadata['context.summaryApplied'] ? '是' : '否' }}
          </p>
          <p v-if="result.metadata['context.messageCountTrimmed'] != null">
            <strong>消息数裁剪：</strong>{{ result.metadata['context.messageCountTrimmed'] ? '是' : '否' }}
          </p>
          <p v-if="result.metadata['context.tokenTrimmed'] != null">
            <strong>Token裁剪：</strong>{{ result.metadata['context.tokenTrimmed'] ? '是' : '否' }}
          </p>
          <p v-if="result.metadata['context.diagnostics']">
            <strong>诊断码：</strong>{{ result.metadata['context.diagnostics'].join(', ') }}
          </p>
        </div>
      </details>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import * as api from '../api/supervisor.js'
import * as fw from '../api/framework.js'

const supervisors = ref([])
const supervisorName = ref('')
const message = ref('')
const loading = ref(false)
const result = ref(null)
const error = ref('')

const hasContextMetadata = computed(() => {
  if (!result.value || !result.value.metadata) return false
  return result.value.metadata['context.processedTokenCount'] != null
})

onMounted(async () => {
  try { supervisors.value = await fw.listSupervisors() } catch {}
  if (supervisors.value.length > 0) supervisorName.value = supervisors.value[0].name
})

async function handleInvoke() {
  loading.value = true
  error.value = ''
  result.value = null
  try {
    result.value = await api.invokeSupervisor(supervisorName.value, message.value)
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
