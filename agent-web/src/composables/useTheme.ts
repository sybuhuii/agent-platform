/**
 * 主题 composable — 便捷访问主题状态
 */
import { useThemeStore } from '@/stores/theme'

export function useTheme() {
  return useThemeStore()
}
