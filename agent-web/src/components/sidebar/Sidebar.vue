/**
 * 侧边栏组件 — ChatGPT 风格。
 * - 深色/浅色侧边栏，新建对话按钮
 * - 会话按时间分组（今天/昨天/更早）
 * - 当前会话高亮，悬停效果
 * - 底部用户信息、主题切换、退出
 * - 不展示 Supervisor/Agent 技术名称
 */
<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'
import { useSidebarStore } from '@/stores/sidebar'
import { useThemeStore } from '@/stores/theme'
import { usePendingApprovals } from '@/queries'
import { Plus, MessageSquare, Bell, Users, Shield, Sun, Moon, Monitor, LogOut, ChevronDown } from '@lucide/vue'
import { ref } from 'vue'

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

  const groups: { label: string; conversations: typeof chatStore.conversations }[] = [
    { label: '今天', conversations: [] },
    { label: '昨天', conversations: [] },
    { label: '更早', conversations: [] }
  ]

  for (const conv of chatStore.conversations) {
    if (conv.lastMessageAt >= today) {
      groups[0]!.conversations.push(conv)
    } else if (conv.lastMessageAt >= yesterday) {
      groups[1]!.conversations.push(conv)
    } else {
      groups[2]!.conversations.push(conv)
    }
  }

  return groups.filter(g => g.conversations.length > 0)
})

function handleNewConversation() {
  chatStore.newConversation()
  sidebarStore.closeMobile()
}

function handleSelectConversation(conversationId: string) {
  chatStore.switchConversation(conversationId)
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
  <aside class="flex flex-col h-full bg-[var(--sidebar)] text-[var(--sidebar-foreground)]">
    <!-- 新建对话按钮 -->
    <div class="p-2 pt-3">
      <button
        class="w-full flex items-center gap-2 rounded-xl border border-[var(--sidebar-border)] px-3 py-3 text-sm font-medium hover:bg-[var(--sidebar-hover)] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
        @click="handleNewConversation"
      >
        <Plus class="w-4 h-4" aria-hidden="true" />
        新建对话
      </button>
    </div>

    <!-- 会话列表 -->
    <div class="flex-1 overflow-y-auto px-2 py-1">
      <template v-for="group in groupedConversations" :key="group.label">
        <p class="text-xs text-[var(--muted-foreground)] px-2 py-2 font-medium">{{ group.label }}</p>
        <button
          v-for="conv in group.conversations"
          :key="conv.conversationId"
          class="w-full text-left rounded-lg px-3 py-2.5 text-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--ring)] mb-0.5"
          :class="chatStore.activeConversationId === conv.conversationId
            ? 'bg-[var(--sidebar-active)] text-[var(--sidebar-active-foreground)]'
            : 'hover:bg-[var(--sidebar-hover)]'"
          @click="handleSelectConversation(conv.conversationId)"
        >
          <div class="truncate">{{ conv.title }}</div>
        </button>
      </template>

      <div v-if="chatStore.conversations.length === 0" class="px-3 py-8 text-center text-sm text-[var(--muted-foreground)]">
        暂无对话
      </div>
    </div>

    <!-- 底部导航 -->
    <nav class="border-t border-[var(--sidebar-border)] p-2 space-y-0.5">
      <button
        class="w-full flex items-center justify-between rounded-lg px-3 py-2.5 text-sm hover:bg-[var(--sidebar-hover)] transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--ring)]"
        @click="goToApprovals"
      >
        <span class="flex items-center gap-2"><Bell class="w-4 h-4" aria-hidden="true" />待审批</span>
        <span
          v-if="pendingCount > 0"
          class="min-w-[20px] h-5 flex items-center justify-center rounded-full bg-[var(--destructive)] text-[var(--destructive-foreground)] text-xs font-medium px-1.5"
        >
          {{ pendingCount }}
        </span>
      </button>

      <button
        v-if="canManageUsers"
        class="w-full flex items-center gap-2 rounded-lg px-3 py-2.5 text-sm hover:bg-[var(--sidebar-hover)] transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--ring)]"
        @click="goToUsers"
      >
        <Users class="w-4 h-4" aria-hidden="true" />
        用户管理
      </button>

      <button
        v-if="canManageRoles"
        class="w-full flex items-center gap-2 rounded-lg px-3 py-2.5 text-sm hover:bg-[var(--sidebar-hover)] transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--ring)]"
        @click="goToRoles"
      >
        <Shield class="w-4 h-4" aria-hidden="true" />
        角色管理
      </button>

      <button
        class="w-full flex items-center gap-2 rounded-lg px-3 py-2.5 text-sm hover:bg-[var(--sidebar-hover)] transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--ring)]"
        @click="themeStore.toggleTheme()"
      >
        <component :is="themeIcon" class="w-4 h-4" aria-hidden="true" />
        主题：{{ themeLabel() }}
      </button>
    </nav>

    <!-- 用户信息 -->
    <div class="border-t border-[var(--sidebar-border)] p-2">
      <button
        v-if="authStore.currentUser"
        class="w-full flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm hover:bg-[var(--sidebar-hover)] transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--ring)]"
        @click="showUserMenu = !showUserMenu"
      >
        <div class="w-7 h-7 rounded-full bg-[var(--accent)] flex items-center justify-center shrink-0">
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
  </aside>
</template>
