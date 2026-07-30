/**
 * 自动滚动核心判断测试
 */
import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import { useAutoScroll } from '@/composables/useAutoScroll'

describe('useAutoScroll', () => {
  it('should initialize with isAtBottom true and showScrollButton false', () => {
    const container = ref<HTMLElement | null>(null)
    const { isAtBottom, showScrollButton } = useAutoScroll(container)
    expect(isAtBottom.value).toBe(true)
    expect(showScrollButton.value).toBe(false)
  })

  it('should expose scrollToBottom function', () => {
    const container = ref<HTMLElement | null>(null)
    const { scrollToBottom } = useAutoScroll(container)
    expect(typeof scrollToBottom).toBe('function')
  })
})
