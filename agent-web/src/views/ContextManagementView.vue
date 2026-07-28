<template>
  <div class="invoke-page">
    <h2>上下文管理</h2>

    <!-- 能力配置区域 -->
    <div v-if="capabilities" class="result-card">
      <h3>能力配置</h3>
      <p><strong>是否启用：</strong>{{ capabilities.enabled ? '是' : '否' }}</p>
      <p><strong>消息数限制：</strong>{{ capabilities.maxMessages }}</p>
      <p><strong>最大上下文Token：</strong>{{ capabilities.maxContextTokens }}</p>
      <p><strong>可用消息Token预算：</strong>{{ capabilities.availableMessageTokens }}</p>
      <p><strong>输出预留Token：</strong>{{ capabilities.reservedOutputTokens }}</p>
      <p><strong>协议预留Token：</strong>{{ capabilities.reservedProtocolTokens }}</p>
      <p><strong>安全余量：</strong>{{ capabilities.safetyMarginTokens }}</p>
      <p><strong>摘要是否启用：</strong>{{ capabilities.summaryEnabled ? '是' : '否' }}</p>
      <p><strong>摘要是否可用：</strong>{{ capabilities.summaryAvailable ? '是' : '否' }}</p>
      <p><strong>摘要阈值比例：</strong>{{ capabilities.summaryTriggerRatio }}</p>
      <p><strong>Pipeline顺序：</strong>{{ capabilities.pipelineOrder }}</p>
      <p><strong>TokenCounter是否精确：</strong>{{ capabilities.exactTokenCount ? '是' : '否' }}</p>
      <p><strong>运行时集成：</strong>{{ capabilities.runtimeIntegrationEnabled ? '是' : '否' }}</p>
      <p><strong>ReAct接入：</strong>{{ capabilities.reactIntegrated ? '是' : '否' }}</p>
      <p><strong>Supervisor接入：</strong>{{ capabilities.supervisorIntegrated ? '是' : '否' }}</p>
      <p><strong>窗口快照：</strong>{{ capabilities.contextWindowSnapshotEnabled ? '是' : '否' }}</p>
      <p><strong>完整历史保留：</strong>{{ capabilities.fullHistoryPreserved ? '是' : '否' }}</p>
      <p><strong>摘要映射：</strong>{{ capabilities.summaryMessageMapping }}</p>
      <p><strong>结果metadata：</strong>{{ capabilities.resultMetadataEnabled ? '是' : '否' }}</p>
      <p><strong>演示可用：</strong>{{ capabilities.demoAvailable ? '是' : '否' }}</p>
    </div>

    <!-- 长对话演示表单 -->
    <div class="invoke-form">
      <h3>长对话演示</h3>
      <div class="form-group">
        <label>对话轮数 (5-80)</label>
        <input type="number" v-model.number="form.rounds" min="5" max="80" :disabled="loading" />
      </div>
      <div class="form-group">
        <label>每条消息字符数 (32-512)</label>
        <input type="number" v-model.number="form.charactersPerMessage" min="32" max="512" :disabled="loading" />
      </div>
      <div class="form-group">
        <label>包含工具交互</label>
        <input type="checkbox" v-model="form.includeToolInteractions" :disabled="loading" />
      </div>
      <div class="form-group">
        <label>调用模型</label>
        <input type="checkbox" v-model="form.invokeModel" :disabled="loading" />
      </div>
      <div class="form-group">
        <label>最终问题</label>
        <textarea v-model="form.finalQuestion" rows="2" :disabled="loading"></textarea>
      </div>
      <button class="btn-primary" @click="handleDemo" :disabled="loading || !form.finalQuestion">
        {{ loading ? '执行中...' : '运行演示' }}
      </button>
      <button class="btn-secondary" @click="clearResult" v-if="demoResult">清空结果</button>
    </div>

    <div v-if="error" class="error-msg">{{ error }}</div>

    <!-- 结果区域 -->
    <div v-if="demoResult" class="result-card">
      <h3>处理结果</h3>
      <p><strong>原始消息数：</strong>{{ demoResult.originalMessageCount }}</p>
      <p><strong>处理后消息数：</strong>{{ demoResult.processedMessageCount }}</p>
      <p><strong>删除消息数：</strong>{{ demoResult.originalMessageCount - demoResult.processedMessageCount }}</p>
      <p><strong>原始Token数：</strong>{{ demoResult.originalTokenCount }}</p>
      <p><strong>处理后Token数：</strong>{{ demoResult.processedTokenCount }}</p>
      <p><strong>有效预算：</strong>{{ demoResult.effectiveMessageBudget }}</p>
      <p><strong>Token占用比例：</strong>{{ tokenRatio }}%</p>
      <p><strong>是否按消息数裁剪：</strong>{{ demoResult.messageCountTrimmed ? '是' : '否' }}</p>
      <p><strong>是否按Token裁剪：</strong>{{ demoResult.tokenTrimmed ? '是' : '否' }}</p>
      <p><strong>是否触发摘要：</strong>{{ demoResult.summaryTriggered ? '是' : '否' }}</p>
      <p><strong>是否成功摘要：</strong>{{ demoResult.summaryApplied ? '是' : '否' }}</p>
      <p><strong>被摘要消息数：</strong>{{ demoResult.summarizedMessageCount }}</p>
      <p v-if="demoResult.diagnostics && demoResult.diagnostics.length">
        <strong>诊断码：</strong>
        <span v-for="d in demoResult.diagnostics" :key="d" class="role-tag">{{ diagLabel(d) }}</span>
      </p>
      <p><strong>模型是否调用：</strong>{{ demoResult.modelInvoked ? '是' : '否' }}</p>
      <p v-if="demoResult.modelContent"><strong>模型最终回答：</strong>{{ demoResult.modelContent }}</p>
      <p v-if="demoResult.modelErrorCode"><strong>模型错误：</strong>{{ demoResult.modelErrorCode }}</p>
      <p><strong>runId：</strong>{{ demoResult.runId }}</p>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import * as ctx from '../api/context.js'

const capabilities = ref(null)
const form = ref({
  rounds: 30,
  charactersPerMessage: 120,
  includeToolInteractions: true,
  invokeModel: false,
  finalQuestion: '请根据保留下来的上下文简要说明当前任务。'
})
const loading = ref(false)
const demoResult = ref(null)
const error = ref('')

const tokenRatio = computed(() => {
  if (!demoResult.value || demoResult.value.effectiveMessageBudget <= 0) return 0
  return Math.round(demoResult.value.processedTokenCount / demoResult.value.effectiveMessageBudget * 100)
})

onMounted(async () => {
  try {
    capabilities.value = await ctx.getContextCapabilities()
  } catch {}
})

async function handleDemo() {
  loading.value = true
  error.value = ''
  demoResult.value = null
  try {
    demoResult.value = await ctx.runContextDemo({
      rounds: form.value.rounds,
      charactersPerMessage: form.value.charactersPerMessage,
      includeToolInteractions: form.value.includeToolInteractions,
      invokeModel: form.value.invokeModel,
      finalQuestion: form.value.finalQuestion
    })
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function clearResult() {
  demoResult.value = null
  error.value = ''
}

function diagLabel(code) {
  const labels = {
    SYSTEM_MESSAGES_PRESERVED: 'System消息保留',
    RECENT_MESSAGES_TRIMMED: '近期消息裁剪',
    TOOL_GROUP_PRESERVED: '工具组保留',
    ATOMIC_GROUP_OVERSHOOT: '原子组超量',
    LATEST_USER_MESSAGE_PRESERVED: '最新用户消息保留',
    NO_TRIMMING_REQUIRED: '无需裁剪',
    MESSAGE_COUNT_TRIM_APPLIED: '消息数裁剪',
    TOKEN_TRIM_APPLIED: 'Token裁剪',
    TOKEN_BUDGET_NOT_EXCEEDED: '预算未超',
    FINAL_TOKEN_BUDGET_VERIFIED: '预算验证通过',
    NO_TOKEN_TRIMMING_REQUIRED: '无需Token裁剪',
    SUMMARY_TRIGGERED: '摘要触发',
    SUMMARY_APPLIED: '摘要应用',
    SUMMARY_SKIPPED_DISABLED: '摘要关闭',
    SUMMARY_SKIPPED_BELOW_THRESHOLD: '摘要低于阈值',
    SUMMARY_SKIPPED_NO_SOURCE: '无摘要源',
    SUMMARY_UNAVAILABLE: '摘要器不可用',
    SUMMARY_FAILED_FALLBACK_TO_TRIMMING: '摘要失败降级',
    EXISTING_SUMMARY_REPLACED: '旧摘要替换'
  }
  return labels[code] || code
}
</script>
