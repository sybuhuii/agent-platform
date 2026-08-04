/**
 * 侧边栏组件 — ChatGPT 风格。
 * - 深色/浅色侧边栏，新建对话按钮
 * - 会话按时间分组（今天/昨天/更早）
 * - 当前会话高亮，悬停效果
 * - 底部用户信息、主题切换、退出
 * - 不展示 Supervisor/Agent 技术名称
 */
<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuPortal,
  DropdownMenuRoot,
  DropdownMenuSeparator,
  DropdownMenuTrigger
} from 'reka-ui'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'
import { useSidebarStore } from '@/stores/sidebar'
import { useThemeStore } from '@/stores/theme'
import { usePendingApprovals } from '@/queries'
import Dialog from '@/components/ui/Dialog.vue'
import type { Conversation } from '@/types'
import { Plus, Bell, Users, Shield, Sun, Moon, Monitor, LogOut, ChevronDown, Sparkles, PanelLeftClose, MessageSquareText, MoreHorizontal, Pencil, Pin, PinOff, Trash2 } from '@lucide/vue'

const router = useRouter()
const authStore = useAuthStore()
const chatStore = useChatStore()
const sidebarStore = useSidebarStore()
const themeStore = useThemeStore()

const approvalsQuery = usePendingApprovals()
const pendingCount = computed(() => approvalsQuery.data.value?.length ?? 0)

const canManageUsers = computed(() => authStore.hasPermission('security:user:read'))
const canManageRoles = computed(() => authStore.hasPermission('security:role:read'))

/** 会话按时间分组 */
const groupedConversations = computed(() => {
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const yesterday = today - 86400000

  const groups: { label: string; conversations: Conversation[] }[] = [
    { label: '今天', conversations: [] },
    { label: '昨天', conversations: [] },
    { label: '更早', conversations: [] }
  ]

  const pinnedConversations = chatStore.conversations
    .filter(conversation => conversation.pinned)
    .slice()
    .sort((left, right) => right.lastMessageAt - left.lastMessageAt)

  for (const conv of chatStore.conversations.filter(conversation => !conversation.pinned)) {
    if (conv.lastMessageAt >= today) {
      groups[0]!.conversations.push(conv)
    } else if (conv.lastMessageAt >= yesterday) {
      groups[1]!.conversations.push(conv)
    } else {
      groups[2]!.conversations.push(conv)
    }
  }

  for (const group of groups) {
    group.conversations.sort((left, right) => right.lastMessageAt - left.lastMessageAt)
  }

  return [
    ...(pinnedConversations.length > 0
      ? [{ label: '置顶', conversations: pinnedConversations }]
      : []),
    ...groups.filter(group => group.conversations.length > 0)
  ]
})

const renameDialogOpen = ref(false)
const renameTarget = ref<Conversation | null>(null)
const renameTitle = ref('')
const renameError = ref('')
const deleteDialogOpen = ref(false)
const deleteTarget = ref<Conversation | null>(null)

function openRenameDialog(conversation: Conversation) {
  renameTarget.value = conversation
  renameTitle.value = conversation.title
  renameError.value = ''
  renameDialogOpen.value = true
}

async function handleRename() {
  if (!renameTarget.value) return
  try {
    if (!await chatStore.renameConversation(renameTarget.value.conversationId, renameTitle.value)) {
      renameError.value = '请输入会话名称'
      return
    }
  } catch {
    renameError.value = '重命名失败，请稍后重试'
    return
  }
  renameDialogOpen.value = false
  renameTarget.value = null
}

function openDeleteDialog(conversation: Conversation) {
  deleteTarget.value = conversation
  deleteDialogOpen.value = true
}

async function handleDelete() {
  if (!deleteTarget.value) return
  try {
    await chatStore.deleteConversation(deleteTarget.value.conversationId)
  } catch {
    return
  }
  deleteDialogOpen.value = false
  deleteTarget.value = null
}

async function togglePinned(conversation: Conversation) {
  try {
    await chatStore.setConversationPinned(conversation.conversationId, !conversation.pinned)
  } catch {
    // Keep the local state unchanged when persistence fails.
  }
}

function handleNewConversation() {
  chatStore.newConversation()
  router.push('/')
  sidebarStore.closeMobile()
}

function handleSelectConversation(conversationId: string) {
  chatStore.switchConversation(conversationId)
  router.push('/')
  sidebarStore.closeMobile()
}

function goToApprovals() {
  router.push('/approvals')
  sidebarStore.closeMobile()
}

function goToUsers() {
  router.push('/admin/users')
  sidebarStore.closeMobile()
}

function goToRoles() {
  router.push('/admin/roles')
  sidebarStore.closeMobile()
}

async function handleLogout() {
  chatStore.clearAllConversations()
  await authStore.logout()
  router.push('/login')
}

const themeIcon = computed(() => {
  switch (themeStore.theme) {
    case 'light': return Sun
    case 'dark': return Moon
    case 'system': return Monitor
  }
})

function themeLabel(): string {
  switch (themeStore.theme) {
    case 'light': return '浅色'
    case 'dark': return '深色'
    case 'system': return '跟随系统'
  }
}

// 用户信息折叠
const showUserMenu = ref(false)
</script>

<template>
  <aside class="sidebar-panel flex flex-col h-full bg-[var(--sidebar)] text-[var(--sidebar-foreground)]">
    <div class="flex h-[68px] items-center justify-between px-4">
      <button class="flex items-center gap-3 rounded-xl px-1.5 py-2 text-base font-semibold tracking-[-0.02em] hover:bg-[var(--sidebar-hover)]" @click="router.push('/')">
        <span class="grid h-8 w-8 place-items-center rounded-[10px] bg-[var(--foreground)] text-[var(--background)] shadow-sm">
          <Sparkles class="h-[18px] w-[18px]" aria-hidden="true" />
        </span>
        智能协作平台
      </button>
      <button
        class="grid h-9 w-9 place-items-center rounded-lg text-[var(--muted-foreground)] hover:bg-[var(--sidebar-hover)] hover:text-[var(--foreground)]"
        aria-label="收起侧边栏"
        @click="sidebarStore.toggle()"
      >
        <PanelLeftClose class="h-5 w-5" />
      </button>
    </div>

    <!-- 新建对话按钮 -->
    <div class="px-3 pb-4">
      <button
        class="w-full flex h-12 items-center justify-center gap-2.5 rounded-xl border border-[var(--sidebar-border)] bg-[var(--background)] px-3 text-sm font-medium shadow-sm hover:bg-[var(--sidebar-hover)] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
        @click="handleNewConversation"
      >
        <Plus class="w-[18px] h-[18px]" aria-hidden="true" />
        新建对话
      </button>
    </div>

    <!-- 会话列表 -->
    <div class="flex-1 overflow-y-auto px-3 py-1">
      <div class="flex items-center justify-between px-2 pb-1 pt-1">
        <h2 class="text-sm font-semibold text-[var(--sidebar-foreground)]">对话历史</h2>
      </div>
      <template v-for="group in groupedConversations" :key="group.label">
        <p class="text-xs text-[var(--muted-foreground)] px-2 pt-4 pb-1.5 font-medium">{{ group.label }}</p>
        <div
          v-for="conv in group.conversations"
          :key="conv.conversationId"
          class="group w-full flex items-center rounded-xl text-sm transition-colors mb-0.5"
          :class="chatStore.activeConversationId === conv.conversationId
            ? 'bg-[var(--sidebar-active)] text-[var(--sidebar-active-foreground)]'
            : 'hover:bg-[var(--sidebar-hover)]'"
        >
          <button
            class="flex min-w-0 flex-1 items-center gap-2.5 px-2.5 py-2.5 text-left rounded-xl focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--ring)]"
            @click="handleSelectConversation(conv.conversationId)"
          >
            <MessageSquareText class="h-4 w-4 shrink-0 text-[var(--muted-foreground)]" aria-hidden="true" />
            <span class="min-w-0 flex-1 truncate">{{ conv.title }}</span>
            <Pin v-if="conv.pinned" class="h-3.5 w-3.5 shrink-0 text-[var(--muted-foreground)]" aria-label="已置顶" />
          </button>

          <DropdownMenuRoot>
            <DropdownMenuTrigger
              class="mr-1 grid h-8 w-8 shrink-0 place-items-center rounded-lg text-[var(--muted-foreground)] opacity-0 transition-opacity hover:bg-[var(--background)] hover:text-[var(--foreground)] focus:opacity-100 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--ring)] group-hover:opacity-100 data-[state=open]:opacity-100"
              aria-label="会话操作"
              @click.stop
            >
              <MoreHorizontal class="h-4 w-4" aria-hidden="true" />
            </DropdownMenuTrigger>
            <DropdownMenuPortal>
              <DropdownMenuContent
                :side-offset="6"
                align="end"
                class="z-[70] min-w-[150px] rounded-xl border border-[var(--border)] bg-[var(--card)] p-1.5 text-sm text-[var(--foreground)] shadow-lg outline-none"
              >
                <DropdownMenuItem
                  class="flex cursor-pointer items-center gap-2 rounded-lg px-2.5 py-2 outline-none data-[highlighted]:bg-[var(--muted)]"
                  @select="openRenameDialog(conv)"
                >
                  <Pencil class="h-4 w-4" aria-hidden="true" />
                  重命名
                </DropdownMenuItem>
                <DropdownMenuItem
                  class="flex cursor-pointer items-center gap-2 rounded-lg px-2.5 py-2 outline-none data-[highlighted]:bg-[var(--muted)]"
                  @select="togglePinned(conv)"
                >
                  <PinOff v-if="conv.pinned" class="h-4 w-4" aria-hidden="true" />
                  <Pin v-else class="h-4 w-4" aria-hidden="true" />
                  {{ conv.pinned ? '取消置顶' : '置顶' }}
                </DropdownMenuItem>
                <DropdownMenuSeparator class="my-1 h-px bg-[var(--border)]" />
                <DropdownMenuItem
                  class="flex cursor-pointer items-center gap-2 rounded-lg px-2.5 py-2 text-[var(--destructive)] outline-none data-[highlighted]:bg-[var(--destructive)]/10"
                  @select="openDeleteDialog(conv)"
                >
                  <Trash2 class="h-4 w-4" aria-hidden="true" />
                  删除
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenuPortal>
          </DropdownMenuRoot>
        </div>
      </template>

      <div v-if="chatStore.conversations.length === 0" class="px-3 py-8 text-center text-sm text-[var(--muted-foreground)]">
        暂无对话
      </div>
    </div>

    <!-- 底部导航 -->
    <nav class="border-t border-[var(--sidebar-border)] p-3 space-y-1">
      <button
        class="w-full flex items-center justify-between rounded-xl px-2.5 py-2.5 text-sm hover:bg-[var(--sidebar-hover)] transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--ring)]"
        @click="goToApprovals"
      >
        <span class="flex items-center gap-2.5"><Bell class="w-[18px] h-[18px]" aria-hidden="true" />待审批</span>
        <span
          v-if="pendingCount > 0"
          class="min-w-[22px] h-[22px] flex items-center justify-center rounded-full bg-[var(--warning)] text-white text-xs font-medium px-1.5"
        >
          {{ pendingCount }}
        </span>
      </button>

      <button
        v-if="canManageUsers"
        class="w-full flex items-center gap-2.5 rounded-xl px-2.5 py-2.5 text-sm hover:bg-[var(--sidebar-hover)] transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--ring)]"
        @click="goToUsers"
      >
        <Users class="w-[18px] h-[18px]" aria-hidden="true" />
        用户管理
      </button>

      <button
        v-if="canManageRoles"
        class="w-full flex items-center gap-2.5 rounded-xl px-2.5 py-2.5 text-sm hover:bg-[var(--sidebar-hover)] transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--ring)]"
        @click="goToRoles"
      >
        <Shield class="w-[18px] h-[18px]" aria-hidden="true" />
        角色管理
      </button>

      <button
        class="w-full flex items-center gap-2.5 rounded-xl px-2.5 py-2.5 text-sm hover:bg-[var(--sidebar-hover)] transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--ring)]"
        @click="themeStore.toggleTheme()"
      >
        <component :is="themeIcon" class="w-[18px] h-[18px]" aria-hidden="true" />
        主题：{{ themeLabel() }}
      </button>
    </nav>

    <!-- 用户信息 -->
    <div class="border-t border-[var(--sidebar-border)] p-3">
      <button
        v-if="authStore.currentUser"
        class="w-full flex items-center gap-3 rounded-xl px-1.5 py-2 text-sm hover:bg-[var(--sidebar-hover)] transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--ring)]"
        @click="showUserMenu = !showUserMenu"
      >
        <div class="w-9 h-9 rounded-full bg-[var(--foreground)] flex items-center justify-center shrink-0">
          <span class="text-xs font-medium text-[var(--accent-foreground)]">{{ authStore.currentUser.username.charAt(0).toUpperCase() }}</span>
        </div>
        <span class="flex-1 text-left truncate">{{ authStore.currentUser.username }}</span>
        <ChevronDown class="w-4 h-4 text-[var(--muted-foreground)]" aria-hidden="true" />
      </button>

      <!-- 展开菜单 -->
      <div v-if="showUserMenu" class="mt-1 pl-10">
        <button
          class="w-full flex items-center gap-2 rounded-lg px-3 py-2 text-sm text-[var(--destructive)] hover:bg-[var(--destructive)]/10 transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--ring)]"
          @click="handleLogout"
        >
          <LogOut class="w-4 h-4" aria-hidden="true" />
          退出登录
        </button>
      </div>
    </div>

    <Dialog
      :open="renameDialogOpen"
      class="max-w-md"
      @update:open="renameDialogOpen = $event"
    >
      <template #title>重命名会话</template>
      <template #description>输入一个便于识别的会话名称。</template>
      <template #content>
        <form class="space-y-4" @submit.prevent="handleRename">
          <input
            v-model="renameTitle"
            maxlength="80"
            autofocus
            class="h-11 w-full rounded-xl border border-[var(--input)] bg-[var(--background)] px-3.5 text-sm outline-none focus:border-[var(--ring)] focus:ring-1 focus:ring-[var(--ring)]"
            placeholder="会话名称"
          />
          <p v-if="renameError" class="text-sm text-[var(--destructive)]">{{ renameError }}</p>
          <div class="flex justify-end gap-2">
            <button
              type="button"
              class="h-10 rounded-lg border border-[var(--border)] px-4 text-sm hover:bg-[var(--muted)]"
              @click="renameDialogOpen = false"
            >
              取消
            </button>
            <button
              type="submit"
              class="h-10 rounded-lg bg-[var(--foreground)] px-4 text-sm font-medium text-[var(--background)] hover:opacity-85"
            >
              保存
            </button>
          </div>
        </form>
      </template>
    </Dialog>

    <Dialog
      :open="deleteDialogOpen"
      class="max-w-md"
      @update:open="deleteDialogOpen = $event"
    >
      <template #title>删除会话</template>
      <template #description>
        删除“{{ deleteTarget?.title }}”后，本地对话记录将无法恢复。
      </template>
      <template #content>
        <div class="flex justify-end gap-2">
          <button
            type="button"
            class="h-10 rounded-lg border border-[var(--border)] px-4 text-sm hover:bg-[var(--muted)]"
            @click="deleteDialogOpen = false"
          >
            取消
          </button>
          <button
            type="button"
            class="h-10 rounded-lg bg-[var(--destructive)] px-4 text-sm font-medium text-white hover:opacity-85"
            @click="handleDelete"
          >
            删除
          </button>
        </div>
      </template>
    </Dialog>
  </aside>
</template>
