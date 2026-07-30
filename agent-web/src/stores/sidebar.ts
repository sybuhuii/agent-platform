/**
 * 侧边栏 Store — 管理侧边栏展开状态。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useSidebarStore = defineStore('sidebar', () => {
  const open = ref(true)
  const mobileOpen = ref(false)

  function toggle() {
    open.value = !open.value
  }

  function toggleMobile() {
    mobileOpen.value = !mobileOpen.value
  }

  function closeMobile() {
    mobileOpen.value = false
  }

  return { open, mobileOpen, toggle, toggleMobile, closeMobile }
})
