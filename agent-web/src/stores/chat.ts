/**
 * 聊天 Store — 管理客户端聊天状态。
 *
 * 核心设计：多会话消息隔离
 * - 每个会话拥有独立的消息列表、threadId 和草稿
 * - 切换会话时消息不会丢失
 * - 异步请求通过 conversationId + requestId 隔离，响应写回发起请求的会话
 * - conversationId 是前端稳定 ID，threadId 是后端首次响应后返回的真实 ID
 * - 不使用 Supervisor 名称作为会话 ID
 *
 * 业务模型：用户 → 系统 → 唯一 Supervisor → 内部 Agent → Tool
 * - 系统只有一个 Supervisor，用户不知道此概念
 * - 登录后自动获取 Supervisor 列表并使用第一个（也是唯一一个）
 * - 用户无需选择 Supervisor
 * - 页面不展示 Supervisor 技术名称
 * - SUSPENDED 状态时立即弹出审批弹窗
 * - AbortRequestError 明确区分用户主动取消
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type {
  ApprovalAction,
  ApprovalResumeResponse,
  ChatMessage,
  Conversation,
  RunState,
  SupervisorInvokeResponse,
  SystemInitStatus
} from '@/types'
import { uniqueId } from '@/utils'
import { jsonTransport } from '@/chat/jsonTransport'
import type { ChatTransport } from '@/chat/chatTransport'
import { AbortRequestError } from '@/api/errors'
import { listSupervisors } from '@/api/framework'

export const useChatStore = defineStore('chat', () => {
  // ─── 内部 Supervisor 名称（用户不可见） ───
  const _supervisorName = ref('')
  const _supervisorLoaded = ref(false)
  const _initError = ref(false)

  // ─── 系统初始化状态 ───
  const systemStatus = computed<SystemInitStatus>(() => {
    if (!_supervisorLoaded.value) return 'loading'
    if (_supervisorName.value.length > 0) return 'ready'
    return 'unavailable'
  })

  // ─── 会话列表 ───
  const conversations = ref<Conversation[]>([])

  // ─── 当前活跃会话 ID ───
  const activeConversationId = ref<string | null>(null)

  // ─── 当前活跃会话 ───
  const activeConversation = computed<Conversation | null>(() => {
    if (!activeConversationId.value) return null
    return conversations.value.find(c => c.conversationId === activeConversationId.value) ?? null
  })

  // ─── 当前会话消息（只读 computed） ───
  const messages = computed<ChatMessage[]>(() => activeConversation.value?.messages ?? [])

  // ─── 运行状态 ───
  const runState = ref<RunState>({ status: 'idle' })

  // ─── 输入草稿（代理到当前会话） ───
  const draft = computed({
    get: () => activeConversation.value?.draft ?? '',
    set: (val: string) => {
      if (activeConversation.value) {
        activeConversation.value.draft = val
      }
    }
  })

  // ─── Transport 实例 ───
  const transport: ChatTransport = jsonTransport

  // ─── 异步请求追踪 ───
  interface ActiveRequest {
    conversationId: string
    requestId: string
    controller: AbortController
  }
  let activeRequest: ActiveRequest | null = null

  const isRunning = computed(() =>
    runState.value.status === 'submitting' || runState.value.status === 'running'
  )

  /** 系统是否就绪（Supervisor 已自动获取） */
  const isReady = computed(() => _supervisorLoaded.value && _supervisorName.value.length > 0)

  /** 保持向后兼容：supervisorLoaded */
  const supervisorLoaded = computed(() => _supervisorLoaded.value)

  /** 保持向后兼容：currentThreadId */
  const currentThreadId = computed(() => activeConversation.value?.threadId)

  /** 保持向后兼容：threads 列表 */
  const threads = computed(() =>
    conversations.value
      .filter(c => c.threadId)
      .map(c => ({
        threadId: c.threadId!,
        title: c.title,
        createdAt: c.createdAt,
        lastMessageAt: c.lastMessageAt
      }))
  )

  // ─── 会话持久化（sessionStorage） ───
  const STORAGE_KEY = 'agent-chat-conversations'
  const ACTIVE_KEY = 'agent-chat-active-id'

  /** 安全的会话数据（不包含敏感信息） */
  interface SafeConversationData {
    conversationId: string
    threadId?: string
    title: string
    pinned?: boolean
    messages: ChatMessage[]
    draft: string
    createdAt: number
    lastMessageAt: number
  }

  function sanitizeForStorage(conv: Conversation): SafeConversationData {
    // 过滤消息中的敏感字段
    const safeMessages: ChatMessage[] = conv.messages.map(msg => {
      if (msg.role === 'assistant') {
        // 不保存 metadata, evidence 等内部 Agent 信息
        return {
          role: msg.role,
          id: msg.id,
          content: msg.content,
          timestamp: msg.timestamp,
          runId: msg.runId,
          threadId: msg.threadId,
          success: msg.success
        } as ChatMessage
      }
      if (msg.role === 'approval') {
        return {
          role: msg.role,
          id: msg.id,
          approvalId: msg.approvalId,
          runId: msg.runId,
          approvalRunId: msg.approvalRunId,
          operationName: msg.operationName,
          riskLevel: msg.riskLevel,
          reason: msg.reason,
          status: msg.status,
          timestamp: msg.timestamp
        } as ChatMessage
      }
      // user, error, tool-call, tool-result 是安全的
      return msg
    })
    return {
      conversationId: conv.conversationId,
      threadId: conv.threadId,
      title: conv.title,
      pinned: conv.pinned,
      messages: safeMessages,
      draft: conv.draft,
      createdAt: conv.createdAt,
      lastMessageAt: conv.lastMessageAt
    }
  }

  function saveToStorage(): void {
    try {
      const data = conversations.value.map(sanitizeForStorage)
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(data))
      sessionStorage.setItem(ACTIVE_KEY, activeConversationId.value ?? '')
    } catch {
      // sessionStorage 不可用或超出配额
    }
  }

  function loadFromStorage(): void {
    try {
      const raw = sessionStorage.getItem(STORAGE_KEY)
      const activeId = sessionStorage.getItem(ACTIVE_KEY)
      if (raw) {
        const data = JSON.parse(raw) as SafeConversationData[]
        conversations.value = data.map(d => ({
          conversationId: d.conversationId,
          threadId: d.threadId,
          title: d.title,
          pinned: d.pinned ?? false,
          messages: d.messages,
          draft: d.draft,
          createdAt: d.createdAt,
          lastMessageAt: d.lastMessageAt
        }))
        if (activeId && conversations.value.some(c => c.conversationId === activeId)) {
          activeConversationId.value = activeId
        } else if (conversations.value.length > 0) {
          activeConversationId.value = conversations.value[0]!.conversationId
        }
      }
    } catch {
      // 数据损坏，忽略
    }
  }

  function clearStorage(): void {
    try {
      sessionStorage.removeItem(STORAGE_KEY)
      sessionStorage.removeItem(ACTIVE_KEY)
    } catch {
      // ignore
    }
  }

  /** 自动获取唯一 Supervisor */
  async function initSupervisor(): Promise<void> {
    // 允许重试：重置状态
    _supervisorLoaded.value = false
    _initError.value = false
    try {
      const supervisors = await listSupervisors()
      if (supervisors.length === 1) {
        _supervisorName.value = supervisors[0]!.name
      } else if (supervisors.length > 1) {
        // 多个 Supervisor — 不擅自使用第一个
        _supervisorName.value = ''
      } else {
        // 空列表
        _supervisorName.value = ''
      }
    } catch {
      _supervisorName.value = ''
      _initError.value = true
    } finally {
      _supervisorLoaded.value = true
    }
  }

  // ─── 消息操作（按会话 ID） ───

  function addMessage(conversationId: string, msg: ChatMessage): void {
    const conv = conversations.value.find(c => c.conversationId === conversationId)
    if (conv) {
      conv.messages.push(msg)
      conv.lastMessageAt = Date.now()
      saveToStorage()
    }
  }

  function updateMessage(conversationId: string, id: string, patch: Partial<ChatMessage>): void {
    const conv = conversations.value.find(c => c.conversationId === conversationId)
    if (conv) {
      const idx = conv.messages.findIndex(m => m.id === id)
      if (idx >= 0) {
        conv.messages[idx] = { ...conv.messages[idx]!, ...patch } as ChatMessage
        conv.lastMessageAt = Date.now()
        saveToStorage()
      }
    }
  }

  /** 处理 Supervisor 响应的状态 */
  function handleResponse(response: SupervisorInvokeResponse, assistantId: string, targetConversationId: string): void {
    const conv = conversations.value.find(c => c.conversationId === targetConversationId)
    if (!conv) return

    const patch: Partial<ChatMessage> = {
      content: response.content,
      runId: response.runId,
      threadId: response.threadId,
      success: response.success,
      errorCode: response.errorCode,
      evidence: response.evidence,
      metadata: response.metadata
    }

    updateMessage(targetConversationId, assistantId, patch)

    // 绑定 threadId 到发起请求的会话
    if (!conv.threadId && response.threadId) {
      conv.threadId = response.threadId
    }

    // 标题使用该会话第一条用户消息的安全截断版本
    if (conv.title === '新对话') {
      const firstUserMsg = conv.messages.find(m => m.role === 'user')
      if (firstUserMsg && firstUserMsg.role === 'user') {
        conv.title = firstUserMsg.content.slice(0, 50) || '新对话'
      }
    }

    conv.lastMessageAt = Date.now()

    // 状态判断
    if (!response.success) {
      const meta = response.metadata
      if (meta && typeof meta === 'object' && 'approvalId' in meta) {
        runState.value = { status: 'suspended', runId: response.runId }
        // 嵌套 Supervisor 暂停时，前端审批使用子 Agent 的 runId
        const approvalRunId = response.approvalRunId ?? response.runId
        addMessage(targetConversationId, {
          role: 'approval',
          id: uniqueId(),
          approvalId: String(meta.approvalId),
          runId: response.runId,
          approvalRunId,
          operationName: String(meta.operationName ?? ''),
          riskLevel: String(meta.riskLevel ?? ''),
          reason: String(meta.reason ?? ''),
          status: 'pending',
          timestamp: Date.now()
        })
      } else {
        runState.value = {
          status: 'failed',
          runId: response.runId,
          errorCode: response.errorCode,
          message: response.content || '执行失败'
        }
      }
    } else {
      runState.value = { status: 'completed', runId: response.runId }
    }

    saveToStorage()
  }

  function handleApprovalResume(
      response: ApprovalResumeResponse,
      approvalMessageId: string,
      action: ApprovalAction,
      targetConversationId?: string
  ): void {
    const conversationId = targetConversationId ?? activeConversationId.value
    const conv = conversationId
      ? conversations.value.find(item => item.conversationId === conversationId) ?? null
      : null
    if (!conversationId || !conv) return

    updateMessage(conversationId, approvalMessageId, {
      status: action === 'APPROVE' ? 'approved' : 'rejected'
    })

    if (!conv.threadId && response.threadId) {
      conv.threadId = response.threadId
    }

    if (response.content.trim()) {
      addMessage(conversationId, {
        role: 'assistant',
        id: uniqueId(),
        content: response.content,
        timestamp: Date.now(),
        runId: response.runId,
        threadId: response.threadId,
        success: response.success,
        errorCode: response.errorCode,
        evidence: response.evidence,
        metadata: response.safeMetadata,
        status: response.status
      })
    }

    if (response.status === 'SUSPENDED' && response.approvalId) {
      const metadata = response.safeMetadata ?? {}
      addMessage(conversationId, {
        role: 'approval',
        id: uniqueId(),
        approvalId: response.approvalId,
        runId: response.runId,
        approvalRunId: response.approvalRunId ?? response.runId,
        operationName: response.operationName,
        riskLevel: response.riskLevel,
        reason: String(metadata.reason ?? '该操作需要人工确认'),
        status: 'pending',
        timestamp: Date.now()
      })
      runState.value = { status: 'suspended', runId: response.runId }
    } else if (response.status === 'COMPLETED') {
      runState.value = { status: 'completed', runId: response.runId }
    } else if (response.status === 'FAILED') {
      runState.value = {
        status: 'failed',
        runId: response.runId,
        errorCode: response.errorCode,
        message: response.content || '恢复执行失败'
      }
    } else {
      runState.value = { status: 'running', runId: response.runId }
    }

    conv.lastMessageAt = Date.now()
    saveToStorage()
  }

  /**
   * 将待审批页面完成的审批结果同步回对应会话。
   * approvalId 由后端生成，能够跨页面稳定定位原审批消息。
   */
  function handleExternalApprovalResume(
      response: ApprovalResumeResponse,
      approvalId: string,
      action: ApprovalAction
  ): string | null {
    for (const conversation of conversations.value) {
      const approvalMessage = conversation.messages.find(message =>
        message.role === 'approval' &&
        message.status === 'pending' &&
        message.approvalId === approvalId
      )

      if (approvalMessage?.role === 'approval') {
        handleApprovalResume(
            response,
            approvalMessage.id,
            action,
            conversation.conversationId
        )
        return conversation.conversationId
      }
    }
    return null
  }

  /** 后端已不存在待审批检查点时，收敛浏览器中的陈旧审批状态。 */
  function markApprovalResolved(approvalId: string): void {
    for (const conversation of conversations.value) {
      const approvalMessage = conversation.messages.find(message =>
        message.role === 'approval' &&
        message.status === 'pending' &&
        message.approvalId === approvalId
      )

      if (approvalMessage?.role === 'approval') {
        approvalMessage.status = 'resolved'
        conversation.lastMessageAt = Date.now()
        if (conversation.conversationId === activeConversationId.value) {
          runState.value = { status: 'idle' }
        }
        saveToStorage()
        return
      }
    }
  }

  /** 发送用户消息并调用后端 */
  async function sendMessage(content: string): Promise<void> {
    if (!content.trim() || isRunning.value || !_supervisorName.value) return

    if (!activeConversationId.value) {
      newConversation()
    }

    if (!activeConversationId.value) return

    const targetConvId = activeConversationId.value
    const requestId = uniqueId()

    addMessage(targetConvId, {
      role: 'user',
      id: uniqueId(),
      content: content.trim(),
      timestamp: Date.now()
    })

    // 清空当前会话草稿
    const conv = conversations.value.find(c => c.conversationId === targetConvId)
    if (conv) {
      conv.draft = ''
    }

    // 取消旧请求
    if (activeRequest) {
      activeRequest.controller.abort()
      activeRequest = null
    }

    runState.value = { status: 'submitting' }
    const controller = new AbortController()
    activeRequest = { conversationId: targetConvId, requestId, controller }
    const signal = controller.signal

    const assistantId = uniqueId()
    addMessage(targetConvId, {
      role: 'assistant',
      id: assistantId,
      content: '',
      timestamp: Date.now()
    })

    try {
      runState.value = { status: 'running' }
      const threadId = conv?.threadId
      const response = await transport.invokeSupervisor(
        _supervisorName.value,
        content.trim(),
        threadId,
        signal
      )
      // 只有当请求仍然属于当前 activeRequest 时才处理
      if (activeRequest?.requestId === requestId) {
        handleResponse(response, assistantId, targetConvId)
      }
    } catch (err: unknown) {
      // 只有当请求仍然属于当前 activeRequest 时才处理错误
      if (activeRequest?.requestId !== requestId) {
        // 旧请求的异常，不影响新请求
        return
      }
      if (err instanceof AbortRequestError) {
        updateMessage(targetConvId, assistantId, { content: '已取消等待响应', timestamp: Date.now() })
        runState.value = { status: 'idle' }
      } else {
        const message = err instanceof Error ? err.message : '请求失败'
        updateMessage(targetConvId, assistantId, { role: 'error', id: assistantId, content: message, timestamp: Date.now() })
        runState.value = { status: 'failed', message }
      }
    } finally {
      // 只清理自己的请求
      if (activeRequest?.requestId === requestId) {
        activeRequest = null
      }
    }
  }

  /** 取消前端等待 */
  function cancelRequest(): void {
    if (activeRequest) {
      activeRequest.controller.abort()
      activeRequest = null
    }
    runState.value = { status: 'idle' }
  }

  /** 新建对话 */
  function newConversation(): void {
    // 取消当前请求
    if (activeRequest) {
      activeRequest.controller.abort()
      activeRequest = null
    }

    const convId = uniqueId()
    const newConv: Conversation = {
      conversationId: convId,
      threadId: undefined,
      title: '新对话',
      pinned: false,
      messages: [],
      draft: '',
      createdAt: Date.now(),
      lastMessageAt: Date.now()
    }

    conversations.value.unshift(newConv)
    activeConversationId.value = convId
    runState.value = { status: 'idle' }

    saveToStorage()
  }

  /** 切换到已有会话 */
  function switchConversation(conversationId: string): void {
    // 取消当前请求但保留已有消息
    if (activeRequest) {
      activeRequest.controller.abort()
      activeRequest = null
    }

    // 验证会话存在
    const targetConv = conversations.value.find(c => c.conversationId === conversationId)
    if (!targetConv) return

    activeConversationId.value = conversationId
    runState.value = { status: 'idle' }

    saveToStorage()
  }

  /** 重命名会话；空标题不覆盖现有标题。 */
  function renameConversation(conversationId: string, title: string): boolean {
    const normalizedTitle = title.trim().slice(0, 80)
    if (!normalizedTitle) return false

    const conversation = conversations.value.find(
      item => item.conversationId === conversationId
    )
    if (!conversation) return false

    conversation.title = normalizedTitle
    saveToStorage()
    return true
  }

  /** 设置会话置顶状态。 */
  function setConversationPinned(conversationId: string, pinned: boolean): void {
    const conversation = conversations.value.find(
      item => item.conversationId === conversationId
    )
    if (!conversation || conversation.pinned === pinned) return

    conversation.pinned = pinned
    saveToStorage()
  }

  /** 保持向后兼容：switchThread */
  function switchThread(threadId: string): void {
    const conv = conversations.value.find(c => c.threadId === threadId)
    if (conv) {
      switchConversation(conv.conversationId)
    }
  }

  /** 删除会话 */
  function deleteConversation(conversationId: string): void {
    const idx = conversations.value.findIndex(c => c.conversationId === conversationId)
    if (idx < 0) return

    if (activeRequest?.conversationId === conversationId) {
      activeRequest.controller.abort()
      activeRequest = null
    }

    conversations.value.splice(idx, 1)

    // 如果删除的是当前会话，切换到最近的或创建新的
    if (activeConversationId.value === conversationId) {
      if (conversations.value.length > 0) {
        activeConversationId.value = conversations.value[0]!.conversationId
      } else {
        activeConversationId.value = null
      }
      runState.value = { status: 'idle' }
    }

    saveToStorage()
  }

  /** 清除所有会话数据（登出/401 时调用） */
  function clearAllConversations(): void {
    if (activeRequest) {
      activeRequest.controller.abort()
      activeRequest = null
    }
    conversations.value = []
    activeConversationId.value = null
    runState.value = { status: 'idle' }
    clearStorage()
  }

  // ─── 初始化：从存储恢复会话 ───
  loadFromStorage()
  // 如果没有会话，自动创建一个
  if (conversations.value.length === 0) {
    const convId = uniqueId()
    conversations.value.push({
      conversationId: convId,
      threadId: undefined,
      title: '新对话',
      pinned: false,
      messages: [],
      draft: '',
      createdAt: Date.now(),
      lastMessageAt: Date.now()
    })
    activeConversationId.value = convId
  }

  return {
    // 状态
    supervisorLoaded,
    currentThreadId,
    messages,
    runState,
    draft,
    threads,
    conversations,
    activeConversationId,
    activeConversation,
    systemStatus,
    isRunning,
    isReady,

    // 操作
    initSupervisor,
    addMessage: (msg: ChatMessage) => {
      if (activeConversationId.value) {
        addMessage(activeConversationId.value, msg)
      }
    },
    updateMessage: (id: string, patch: Partial<ChatMessage>) => {
      if (activeConversationId.value) {
        updateMessage(activeConversationId.value, id, patch)
      }
    },
    handleApprovalResume,
    handleExternalApprovalResume,
    markApprovalResolved,
    sendMessage,
    cancelRequest,
    newConversation,
    switchConversation,
    renameConversation,
    setConversationPinned,
    switchThread,
    deleteConversation,
    clearAllConversations
  }
})
