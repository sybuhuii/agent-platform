<template>
  <div class="hitl-approval-view">
    <h2>人机审批中心</h2>

    <!-- 危险操作演示区域 -->
    <div class="demo-section">
      <h3>危险操作演示</h3>
      <div class="demo-controls">
        <select v-model="demoAgentName" :disabled="demoLoading" class="demo-select">
          <option value="" disabled>选择 Agent</option>
          <option v-for="a in agents" :key="a.name" :value="a.name">{{ a.name }} — {{ a.description }}</option>
        </select>
        <select v-model="demoRecordId" :disabled="demoLoading" class="demo-select">
          <option value="" disabled>选择记录</option>
          <option value="demo-1">demo-1</option>
          <option value="demo-2">demo-2</option>
        </select>
        <button
          class="btn btn-danger"
          :disabled="!demoAgentName || !demoRecordId || demoLoading"
          @click="triggerDangerousOp"
        >
          {{ demoLoading ? '执行中...' : '发起危险删除' }}
        </button>
      </div>
      <div v-if="demoResult" class="demo-result" :class="demoResult.status === 'SUSPENDED' ? 'suspended' : demoResult.success ? 'success' : 'error'">
        <p><strong>状态：</strong>{{ demoResult.status }}</p>
        <p v-if="demoResult.content"><strong>内容：</strong>{{ demoResult.content }}</p>
        <p v-if="demoResult.errorCode"><strong>错误码：</strong>{{ demoResult.errorCode }}</p>
        <div v-if="demoResult.status === 'SUSPENDED' && demoResult.metadata">
          <p><strong>审批 ID：</strong>{{ demoResult.metadata.approvalId }}</p>
          <p><strong>操作：</strong>{{ demoResult.metadata.operationName }}</p>
          <p><strong>风险等级：</strong>{{ demoResult.metadata.riskLevel }}</p>
        </div>
      </div>
      <p v-if="demoError" class="error-text">{{ demoError }}</p>
    </div>

    <!-- 待审批列表 -->
    <div class="approval-section">
      <div class="section-header">
        <h3>待审批记录</h3>
        <button class="btn btn-sm" @click="loadPending" :disabled="pendingLoading">
          {{ pendingLoading ? '刷新中...' : '刷新' }}
        </button>
      </div>

      <div v-if="pendingLoading && pendingList.length === 0" class="loading">加载中...</div>
      <div v-else-if="pendingList.length === 0" class="empty">暂无待审批记录</div>
      <div v-else class="approval-list">
        <div v-for="item in pendingList" :key="item.approvalId" class="approval-card">
          <div class="approval-card-header">
            <span class="risk-badge" :class="riskClass(item.riskLevel)">{{ item.riskLevel }}</span>
            <span class="operation-name">{{ item.operationName }}</span>
            <span class="agent-name">{{ item.agentName }}</span>
          </div>
          <div class="approval-card-body">
            <p><strong>审批 ID：</strong>{{ item.approvalId }}</p>
            <p><strong>运行 ID：</strong>{{ item.runId }}</p>
            <p><strong>原因：</strong>{{ item.reason }}</p>
            <div v-if="item.safeArguments && Object.keys(item.safeArguments).length > 0">
              <p><strong>参数（脱敏）：</strong></p>
              <pre class="safe-args">{{ formatSafeArgs(item.safeArguments) }}</pre>
            </div>
            <p><strong>请求时间：</strong>{{ formatTime(item.requestedAt) }}</p>
          </div>
          <div class="approval-card-actions">
            <button
              class="btn btn-approve"
              :disabled="item.processing"
              @click="confirmApprove(item)"
            >
              {{ item.processing ? '处理中...' : '批准' }}
            </button>
            <button
              class="btn btn-reject"
              :disabled="item.processing"
              @click="confirmReject(item)"
            >
              {{ item.processing ? '处理中...' : '拒绝' }}
            </button>
          </div>
          <!-- 恢复结果 -->
          <div v-if="item.resumeResult" class="resume-result" :class="item.resumeResult.status === 'SUSPENDED' ? 'suspended' : item.resumeResult.success ? 'success' : 'error'">
            <p><strong>恢复状态：</strong>{{ item.resumeResult.status }}</p>
            <p v-if="item.resumeResult.content"><strong>结果：</strong>{{ item.resumeResult.content }}</p>
            <p v-if="item.resumeResult.status === 'SUSPENDED' && item.resumeResult.approvalId">
              <strong>新审批 ID：</strong>{{ item.resumeResult.approvalId }}
              （恢复后再次挂起）
            </p>
          </div>
          <div v-if="item.error" class="error-text">{{ item.error }}</div>
        </div>
      </div>
    </div>

    <!-- 确认对话框 -->
    <div v-if="confirmDialog" class="modal-overlay" @click.self="cancelConfirm">
      <div class="modal">
        <h3>{{ confirmDialog.action === 'APPROVE' ? '确认批准' : '确认拒绝' }}</h3>
        <p v-if="confirmDialog.action === 'APPROVE'" class="confirm-warn">
          批准后将实际执行该危险操作。
        </p>
        <p v-else class="confirm-warn">
          拒绝后工具不会执行，Agent将根据拒绝结果继续。
        </p>
        <p><strong>操作：</strong>{{ confirmDialog.item.operationName }}</p>
        <p><strong>风险等级：</strong>{{ confirmDialog.item.riskLevel }}</p>
        <div class="modal-actions">
          <button class="btn" @click="cancelConfirm">取消</button>
          <button
            class="btn"
            :class="confirmDialog.action === 'APPROVE' ? 'btn-approve' : 'btn-reject'"
            @click="executeConfirm"
          >
            确认{{ confirmDialog.action === 'APPROVE' ? '批准' : '拒绝' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { listPendingApprovals, decideApproval } from '../api/approval.js'
import { invokeAgent } from '../api/agent.js'
import { listAgents } from '../api/framework.js'

const agents = ref([])
const demoAgentName = ref('')
const demoRecordId = ref('')
const demoLoading = ref(false)
const demoResult = ref(null)
const demoError = ref('')

const pendingList = ref([])
const pendingLoading = ref(false)
let pollTimer = null
let isVisible = true

const confirmDialog = ref(null)

// 加载 Agent 列表
async function loadAgents() {
  try {
    const data = await listAgents()
    agents.value = Array.isArray(data) ? data : []
    // 自动选择 approval_demo_agent
    const demoAgent = agents.value.find(a => a.name === 'approval_demo_agent')
    if (demoAgent) {
      demoAgentName.value = demoAgent.name
    }
  } catch (e) {
    // 忽略，Agent 列表加载失败不影响审批查询
  }
}

// 发起危险操作
async function triggerDangerousOp() {
  if (!demoAgentName.value || !demoRecordId.value) return
  demoLoading.value = true
  demoResult.value = null
  demoError.value = ''
  try {
    const message = `请删除记录 ${demoRecordId.value}，原因是演示审批流程`
    const data = await invokeAgent(demoAgentName.value, message)
    demoResult.value = data
    // 如果返回 SUSPENDED，刷新待审批列表
    if (data && data.status === 'SUSPENDED') {
      await loadPending()
    }
  } catch (e) {
    demoError.value = e.message || '操作失败'
  } finally {
    demoLoading.value = false
  }
}

// 加载待审批列表
async function loadPending() {
  pendingLoading.value = true
  try {
    const data = await listPendingApprovals()
    const list = Array.isArray(data) ? data : []
    // 保留已有的 processing 和 resumeResult 状态
    pendingList.value = list.map(item => {
      const existing = pendingList.value.find(p => p.approvalId === item.approvalId)
      return {
        ...item,
        processing: existing ? existing.processing : false,
        resumeResult: existing ? existing.resumeResult : null,
        error: existing ? existing.error : null
      }
    })
  } catch (e) {
    // 401 由 http.js 统一处理
  } finally {
    pendingLoading.value = false
  }
}

// 确认批准
function confirmApprove(item) {
  confirmDialog.value = { action: 'APPROVE', item }
}

// 确认拒绝
function confirmReject(item) {
  confirmDialog.value = { action: 'REJECT', item }
}

// 取消确认
function cancelConfirm() {
  confirmDialog.value = null
}

// 执行确认
async function executeConfirm() {
  const { action, item } = confirmDialog.value
  confirmDialog.value = null
  item.processing = true
  item.error = null
  try {
    const data = await decideApproval(item.runId, item.approvalId, action)
    item.resumeResult = data
    // 如果恢复后再次挂起，刷新列表以获取新审批
    if (data && data.status === 'SUSPENDED') {
      await loadPending()
    } else {
      // 完成或拒绝后从列表中移除
      pendingList.value = pendingList.value.filter(p => p.approvalId !== item.approvalId)
    }
  } catch (e) {
    if (e.status === 409) {
      item.error = '版本冲突，请刷新页面后重试'
    } else {
      item.error = e.message || '操作失败'
    }
  } finally {
    item.processing = false
  }
}

// 格式化脱敏参数
function formatSafeArgs(args) {
  if (!args) return ''
  try {
    return JSON.stringify(args, null, 2)
  } catch {
    return String(args)
  }
}

// 格式化时间
function formatTime(instant) {
  if (!instant) return ''
  try {
    return new Date(instant).toLocaleString('zh-CN')
  } catch {
    return String(instant)
  }
}

// 风险等级样式
function riskClass(level) {
  if (level === 'HIGH') return 'risk-high'
  if (level === 'MEDIUM') return 'risk-medium'
  return 'risk-low'
}

// 轮询
function startPolling() {
  stopPolling()
  pollTimer = setInterval(() => {
    if (isVisible && !pendingLoading.value && !confirmDialog.value) {
      loadPending()
    }
  }, 8000) // 每8秒轮询
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

// 页面可见性
function handleVisibility() {
  isVisible = !document.hidden
  if (isVisible) {
    loadPending()
    startPolling()
  } else {
    stopPolling()
  }
}

onMounted(() => {
  loadAgents()
  loadPending()
  startPolling()
  document.addEventListener('visibilitychange', handleVisibility)
})

onUnmounted(() => {
  stopPolling()
  document.removeEventListener('visibilitychange', handleVisibility)
})
</script>

<style scoped>
.hitl-approval-view {
  max-width: 900px;
  margin: 0 auto;
}

.hitl-approval-view h2 {
  margin-bottom: 1.5rem;
}

.demo-section {
  background: #fff8e1;
  border: 1px solid #ffe082;
  border-radius: 8px;
  padding: 1.25rem;
  margin-bottom: 2rem;
}

.demo-section h3 {
  margin-top: 0;
  margin-bottom: 1rem;
  color: #e65100;
}

.demo-controls {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
  align-items: center;
}

.demo-select {
  padding: 0.5rem;
  border: 1px solid #ccc;
  border-radius: 4px;
  min-width: 160px;
}

.demo-result {
  margin-top: 1rem;
  padding: 0.75rem;
  border-radius: 4px;
  font-size: 0.9rem;
}

.demo-result.suspended {
  background: #fff3e0;
  border: 1px solid #ff9800;
}

.demo-result.success {
  background: #e8f5e9;
  border: 1px solid #4caf50;
}

.demo-result.error {
  background: #ffebee;
  border: 1px solid #f44336;
}

.approval-section {
  margin-top: 1rem;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}

.section-header h3 {
  margin: 0;
}

.approval-list {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.approval-card {
  background: #fff;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  padding: 1.25rem;
}

.approval-card-header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}

.risk-badge {
  padding: 0.2rem 0.6rem;
  border-radius: 4px;
  font-size: 0.8rem;
  font-weight: 600;
  color: #fff;
}

.risk-high { background: #f44336; }
.risk-medium { background: #ff9800; }
.risk-low { background: #4caf50; }

.operation-name {
  font-weight: 600;
  font-size: 1.1rem;
}

.agent-name {
  color: #666;
  font-size: 0.9rem;
}

.approval-card-body p {
  margin: 0.3rem 0;
  font-size: 0.9rem;
}

.safe-args {
  background: #f5f5f5;
  padding: 0.5rem;
  border-radius: 4px;
  font-size: 0.8rem;
  max-height: 120px;
  overflow-y: auto;
}

.approval-card-actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 1rem;
}

.btn {
  padding: 0.5rem 1rem;
  border: 1px solid #ccc;
  border-radius: 4px;
  cursor: pointer;
  font-size: 0.9rem;
  background: #fff;
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-sm {
  padding: 0.3rem 0.6rem;
  font-size: 0.8rem;
}

.btn-danger {
  background: #f44336;
  color: #fff;
  border-color: #f44336;
}

.btn-danger:hover:not(:disabled) {
  background: #d32f2f;
}

.btn-approve {
  background: #4caf50;
  color: #fff;
  border-color: #4caf50;
}

.btn-approve:hover:not(:disabled) {
  background: #388e3c;
}

.btn-reject {
  background: #f44336;
  color: #fff;
  border-color: #f44336;
}

.btn-reject:hover:not(:disabled) {
  background: #d32f2f;
}

.resume-result {
  margin-top: 0.75rem;
  padding: 0.75rem;
  border-radius: 4px;
  font-size: 0.9rem;
}

.resume-result.suspended {
  background: #fff3e0;
  border: 1px solid #ff9800;
}

.resume-result.success {
  background: #e8f5e9;
  border: 1px solid #4caf50;
}

.resume-result.error {
  background: #ffebee;
  border: 1px solid #f44336;
}

.error-text {
  color: #f44336;
  margin-top: 0.5rem;
  font-size: 0.9rem;
}

.loading, .empty {
  text-align: center;
  padding: 2rem;
  color: #666;
}

.confirm-warn {
  color: #e65100;
  font-weight: 600;
}

/* 模态对话框 */
.modal-overlay {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal {
  background: #fff;
  border-radius: 8px;
  padding: 1.5rem;
  max-width: 480px;
  width: 90%;
}

.modal h3 {
  margin-top: 0;
}

.modal-actions {
  display: flex;
  gap: 0.75rem;
  justify-content: flex-end;
  margin-top: 1.5rem;
}
</style>
