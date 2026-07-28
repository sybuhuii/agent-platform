<template>
  <div class="hitl-approval-view">
    <h2>人机审批中心</h2>

    <!-- 危险操作演示区域 -->
    <div class="demo-section">
      <h3>危险操作演示</h3>
      <div v-if="!demoAgentAvailable" class="demo-unavailable">
        审批演示 Agent 不可用或示例功能未启用
      </div>
      <template v-else>
        <div class="demo-controls">
          <select v-model="demoRecordId" :disabled="demoLoading" class="demo-select">
            <option value="" disabled>选择记录</option>
            <option value="demo-1">demo-1</option>
            <option value="demo-2">demo-2</option>
          </select>
          <input
            v-model="demoReason"
            :disabled="demoLoading"
            class="demo-input"
            placeholder="删除原因"
            maxlength="200"
          />
          <button
            class="btn btn-danger"
            :disabled="!demoRecordId || !demoReason.trim() || demoLoading"
            @click="triggerDangerousOp"
          >
            {{ demoLoading ? '执行中...' : '发起危险删除' }}
          </button>
        </div>
      </template>
      <!-- 发起结果 -->
      <div v-if="demoSuspended" class="demo-result suspended">
        <p><strong>运行已暂停，等待人工审批</strong></p>
        <p><strong>运行 ID：</strong>{{ shortId(demoSuspended.runId) }}</p>
        <p><strong>审批 ID：</strong>{{ shortId(demoSuspended.metadata?.approvalId) }}</p>
        <p><strong>操作：</strong>{{ demoSuspended.metadata?.operationName }}</p>
        <p><strong>风险等级：</strong>{{ demoSuspended.metadata?.riskLevel }}</p>
      </div>
      <div v-if="demoError" class="error-text">{{ demoError }}</div>
    </div>

    <!-- 最近恢复结果 -->
    <div v-if="latestResumeResult" class="resume-result-section">
      <h3>恢复结果</h3>
      <div class="resume-result" :class="resumeResultClass(latestResumeResult)">
        <p><strong>状态：</strong>{{ resumeStatusLabel(latestResumeResult) }}</p>
        <p><strong>运行 ID：</strong>{{ shortId(latestResumeResult.runId) }}</p>
        <p><strong>Agent：</strong>{{ latestResumeResult.agentName }}</p>
        <p v-if="latestResumeResult.content"><strong>结果：</strong>{{ latestResumeResult.content }}</p>
        <p v-if="latestResumeResult.errorCode"><strong>错误码：</strong>{{ latestResumeResult.errorCode }}</p>
        <p v-if="latestResumeResult.status === 'SUSPENDED' && latestResumeResult.approvalId">
          <strong>新审批 ID：</strong>{{ shortId(latestResumeResult.approvalId) }}
          （恢复后再次挂起，请在新审批中操作）
        </p>
        <p v-if="latestResumeResult.status === 'COMPLETED' && latestResumeResult.errorCode === 'APPROVAL_REJECTED'">
          危险工具未执行，Agent已收到拒绝结果。
        </p>
      </div>
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
        <div
          v-for="item in pendingList"
          :key="item.approvalId"
          class="approval-card"
          :class="{ 'card-invalid': item.invalid }"
        >
          <div class="approval-card-header">
            <span class="risk-badge" :class="riskClass(item.riskLevel)">{{ item.riskLevel }}</span>
            <span class="operation-name">{{ item.operationName }}</span>
            <span class="agent-name">{{ item.agentName }}</span>
            <span class="run-id">run:{{ shortId(item.runId) }}</span>
          </div>
          <div class="approval-card-body">
            <p><strong>原因：</strong>{{ item.reason }}</p>
            <p><strong>请求时间：</strong>{{ formatTime(item.requestedAt) }}</p>
            <p><strong>状态：</strong>{{ item.status }}</p>
          </div>
          <div class="approval-card-actions">
            <button
              class="btn btn-approve"
              :disabled="item.processing || item.invalid"
              @click="confirmApprove(item)"
            >
              {{ item.processing ? '处理中...' : '批准' }}
            </button>
            <button
              class="btn btn-reject"
              :disabled="item.processing || item.invalid"
              @click="confirmReject(item)"
            >
              {{ item.processing ? '处理中...' : '拒绝' }}
            </button>
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
        <div v-if="confirmDialog.detail && confirmDialog.detail.safeArguments && Object.keys(confirmDialog.detail.safeArguments).length > 0">
          <p><strong>参数（脱敏）：</strong></p>
          <pre class="safe-args">{{ formatSafeArgs(confirmDialog.detail.safeArguments) }}</pre>
        </div>
        <div class="comment-input">
          <label>审批意见：</label>
          <input v-model="confirmComment" class="comment-field" placeholder="可选" maxlength="1000" />
        </div>
        <div class="modal-actions">
          <button class="btn" @click="cancelConfirm">取消</button>
          <button
            class="btn"
            :class="confirmDialog.action === 'APPROVE' ? 'btn-approve' : 'btn-reject'"
            :disabled="confirmExecuting"
            @click="executeConfirm"
          >
            {{ confirmExecuting ? '提交中...' : '确认' + (confirmDialog.action === 'APPROVE' ? '批准' : '拒绝') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { listPendingApprovals, getPendingApproval, decideAndResume } from '../api/hitl.js'
import { invokeAgent } from '../api/agent.js'
import { listAgents } from '../api/framework.js'

const agents = ref([])
const demoAgentAvailable = ref(false)
const demoRecordId = ref('')
const demoReason = ref('')
const demoLoading = ref(false)
const demoSuspended = ref(null)
const demoError = ref('')

const pendingList = ref([])
const pendingLoading = ref(false)
let pollTimer = null
let isVisible = true
let isSubmitting = false // 审批/恢复请求期间暂停轮询

const latestResumeResult = ref(null)

const confirmDialog = ref(null)
const confirmComment = ref('')
const confirmExecuting = ref(false)

// 加载 Agent 列表，检查 approval_demo_agent 是否可用
async function loadAgents() {
  try {
    const data = await listAgents()
    agents.value = Array.isArray(data) ? data : []
    demoAgentAvailable.value = agents.value.some(a => a.name === 'approval_demo_agent')
  } catch (e) {
    demoAgentAvailable.value = false
  }
}

// 发起危险操作
async function triggerDangerousOp() {
  if (!demoRecordId.value || !demoReason.value.trim()) return
  demoLoading.value = true
  demoSuspended.value = null
  demoError.value = ''
  try {
    const message = `请必须使用delete_demo_record删除记录${demoRecordId.value}，原因为：${demoReason.value.trim()}。必须依据真实工具结果回答，不得假设工具已经执行。`
    const data = await invokeAgent('approval_demo_agent', message)
    if (data && data.status === 'SUSPENDED') {
      demoSuspended.value = data
      await loadPending()
    } else if (data && data.status === 'FAILED') {
      demoError.value = data.errorCode === 'TOOL_ACCESS_DENIED'
        ? '权限不足：当前用户无法执行该危险工具'
        : (data.content || '执行失败')
    } else {
      demoError.value = '模型不可用或执行异常'
    }
  } catch (e) {
    if (e.status === 503) {
      demoError.value = '模型不可用，请稍后再试'
    } else {
      demoError.value = e.message || '操作失败'
    }
  } finally {
    demoLoading.value = false
  }
}

// 加载待审批列表
async function loadPending() {
  if (isSubmitting) return // 审批/恢复期间暂停轮询
  pendingLoading.value = true
  try {
    const data = await listPendingApprovals()
    const list = Array.isArray(data) ? data : []
    // 保留已有的 processing 和 error 状态；移除不再存在的项标记为 invalid
    const newIds = new Set(list.map(i => i.approvalId))
    pendingList.value = list.map(item => {
      const existing = pendingList.value.find(p => p.approvalId === item.approvalId)
      return {
        ...item,
        processing: existing ? existing.processing : false,
        error: existing ? existing.error : null,
        invalid: false
      }
    })
    // 标记已被后端移除但前端还在 processing 的旧项为 invalid
    for (const old of pendingList.value) {
      if (!newIds.has(old.approvalId) && old.processing) {
        old.invalid = true
      }
    }
  } catch (e) {
    // 401 由 http.js 统一处理
  } finally {
    pendingLoading.value = false
  }
}

// 确认批准 - 先加载详情
async function confirmApprove(item) {
  let detail = null
  try {
    detail = await getPendingApproval(item.runId)
  } catch (e) {
    // 404 说明审批已不存在
    item.invalid = true
    item.error = '审批已不存在，请刷新'
    return
  }
  if (detail.status !== 'PENDING') {
    item.invalid = true
    item.error = '审批状态已变化，请刷新'
    return
  }
  confirmComment.value = ''
  confirmDialog.value = { action: 'APPROVE', item, detail }
}

// 确认拒绝
async function confirmReject(item) {
  let detail = null
  try {
    detail = await getPendingApproval(item.runId)
  } catch (e) {
    item.invalid = true
    item.error = '审批已不存在，请刷新'
    return
  }
  if (detail.status !== 'PENDING') {
    item.invalid = true
    item.error = '审批状态已变化，请刷新'
    return
  }
  confirmComment.value = ''
  confirmDialog.value = { action: 'REJECT', item, detail }
}

// 取消确认
function cancelConfirm() {
  if (confirmExecuting.value) return // 提交中不允许取消
  confirmDialog.value = null
}

// 执行确认
async function executeConfirm() {
  const { action, item } = confirmDialog.value
  confirmExecuting.value = true
  item.processing = true
  item.error = null
  isSubmitting = true // 暂停轮询

  try {
    const data = await decideAndResume(item.runId, {
      approvalId: item.approvalId,
      action,
      comment: confirmComment.value.trim()
    })

    // 记录恢复结果
    latestResumeResult.value = data

    if (data && data.status === 'SUSPENDED') {
      // 恢复后再次挂起：刷新列表获取新审批
      await loadPending()
    } else {
      // 完成或失败：从列表移除旧审批
      pendingList.value = pendingList.value.filter(p => p.approvalId !== item.approvalId)
      // 刷新列表确保最新
      await loadPending()
    }

    confirmDialog.value = null
  } catch (e) {
    if (e.status === 409) {
      item.error = '审批状态已发生变化，请刷新'
      item.invalid = true
      // 关闭确认对话框
      confirmDialog.value = null
      // 重新拉取列表
      await loadPending()
    } else {
      item.error = e.message || '操作失败'
      // 不关闭确认对话框，让用户重试或取消
    }
  } finally {
    item.processing = false
    confirmExecuting.value = false
    isSubmitting = false // 恢复轮询
  }
}

// 恢复结果样式
function resumeResultClass(result) {
  if (result.status === 'SUSPENDED') return 'suspended'
  if (result.success) return 'success'
  return 'error'
}

function resumeStatusLabel(result) {
  if (result.status === 'COMPLETED') return '运行已完成'
  if (result.status === 'SUSPENDED') return '运行再次等待审批'
  if (result.status === 'FAILED') return '运行失败'
  return result.status
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

// 短 ID
function shortId(id) {
  if (!id) return ''
  return id.length > 12 ? id.substring(0, 8) + '...' : id
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
    if (isVisible && !isSubmitting && !pendingLoading.value && !confirmDialog.value) {
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
  min-width: 140px;
}

.demo-input {
  padding: 0.5rem;
  border: 1px solid #ccc;
  border-radius: 4px;
  min-width: 200px;
}

.demo-unavailable {
  color: #999;
  font-style: italic;
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

.resume-result-section {
  margin-bottom: 2rem;
}

.resume-result-section h3 {
  margin-top: 0;
  margin-bottom: 0.75rem;
}

.resume-result {
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
  transition: opacity 0.3s;
}

.approval-card.card-invalid {
  opacity: 0.5;
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

.run-id {
  color: #999;
  font-size: 0.8rem;
  font-family: monospace;
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

.comment-input {
  margin-top: 1rem;
}

.comment-input label {
  display: block;
  margin-bottom: 0.3rem;
  font-size: 0.9rem;
}

.comment-field {
  width: 100%;
  padding: 0.5rem;
  border: 1px solid #ccc;
  border-radius: 4px;
  font-size: 0.9rem;
  box-sizing: border-box;
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
  max-width: 520px;
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
