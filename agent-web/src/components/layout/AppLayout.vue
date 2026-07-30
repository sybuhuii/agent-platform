/**
 * 主布局组件 — 侧边栏 + 主内容区。
 * - 桌面端：侧边栏固定在左侧 (~300px)
 * - 移动端：使用 Reka UI Sheet 抽屉（支持 Esc、焦点锁定、遮罩）
 */
<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useSidebarStore } from '@/stores/sidebar'
import { Menu } from '@lucide/vue'
import Sidebar from '@/components/sidebar/Sidebar.vue'
import Sheet from '@/components/ui/Sheet.vue'

const sidebarStore = useSidebarStore()

const isMobile = ref(false)

function checkMobile() {
  isMobile.value = window.innerWidth < 768
  if (isMobile.value) {
    sidebarStore.open = false
  }
}

onMounted(() => {
  checkMobile()
  window.addEventListener('resize', checkMobile)
})

onUnmounted(() => {
  window.removeEventListener('resize', checkMobile)
})

const showDesktopSidebar = computed(() => !isMobile.value && sidebarStore.open)
</script>

<template>
  <div class="flex h-dvh bg-[var(--background)]">
    <!-- 桌面端侧边栏 -->
    <div
      v-if="showDesktopSidebar"
      class="w-[300px] shrink-0"
    >
      <Sidebar />
    </div>

    <!-- 移动端 Sheet 抽屉 — 使用 Reka UI Dialog 语义 -->
    <Sheet
      v-if="isMobile"
      :open="sidebarStore.mobileOpen"
      side="left"
      @update:open="sidebarStore.mobileOpen = $event"
    >
      <Sidebar />
    </Sheet>

    <!-- 主内容 -->
    <div class="flex-1 flex flex-col min-w-0">
      <!-- 移动端顶栏 -->
      <div v-if="isMobile" class="flex items-center h-12 px-3 border-b border-[var(--border)]">
        <button
          class="inline-flex items-center justify-center w-9 h-9 rounded-lg hover:bg-[var(--muted)] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
          aria-label="打开菜单"
          @click="sidebarStore.toggleMobile()"
        >
          <Menu class="w-5 h-5" />
        </button>
        <span class="ml-2 text-sm font-medium">智能协作</span>
      </div>

      <!-- 页面内容 -->
      <slot />
    </div>
  </div>
</template>
