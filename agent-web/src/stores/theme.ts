/**
 * 主题 Store — 管理浅色/深色主题切换。
 */
import { defineStore } from 'pinia'
import { ref, watchEffect } from 'vue'

export type Theme = 'light' | 'dark' | 'system'

const STORAGE_KEY = 'agent_theme'

function getSystemTheme(): 'light' | 'dark' {
  if (window.matchMedia('(prefers-color-scheme: dark)').matches) {
    return 'dark'
  }
  return 'light'
}

function applyTheme(theme: Theme): void {
  const resolved = theme === 'system' ? getSystemTheme() : theme
  document.documentElement.classList.toggle('dark', resolved === 'dark')
}

export const useThemeStore = defineStore('theme', () => {
  const theme = ref<Theme>((localStorage.getItem(STORAGE_KEY) as Theme) || 'system')

  watchEffect(() => {
    applyTheme(theme.value)
    localStorage.setItem(STORAGE_KEY, theme.value)
  })

  function setTheme(t: Theme) {
    theme.value = t
  }

  function toggleTheme() {
    if (theme.value === 'light') {
      theme.value = 'dark'
    } else if (theme.value === 'dark') {
      theme.value = 'system'
    } else {
      theme.value = 'light'
    }
  }

  return { theme, setTheme, toggleTheme }
})
