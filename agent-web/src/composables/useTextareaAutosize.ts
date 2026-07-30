/**
 * Textarea 自动增长 composable。
 * - 自动增长高度
 * - 设置最大高度，超出后内部滚动
 */
import { ref, watch, nextTick, type Ref } from 'vue'

export function useTextareaAutosize(
  textarea: Ref<HTMLTextAreaElement | null>,
  modelValue: Ref<string>,
  options: { minHeight?: number; maxHeight?: number } = {}
) {
  const { minHeight = 40, maxHeight = 200 } = options

  function resize() {
    const el = textarea.value
    if (!el) return
    el.style.height = 'auto'
    const scrollHeight = el.scrollHeight
    const height = Math.min(Math.max(scrollHeight, minHeight), maxHeight)
    el.style.height = `${height}px`
    el.style.overflowY = scrollHeight > maxHeight ? 'auto' : 'hidden'
  }

  watch(modelValue, async () => {
    await nextTick()
    resize()
  })

  return { resize }
}
