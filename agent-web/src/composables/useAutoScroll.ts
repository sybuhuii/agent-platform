/**
 * 自动滚动 composable。
 * - 首次进入滚动到底部
 * - 用户位于底部附近时，消息更新自动跟随
 * - 用户主动向上滚动后，不强制拉回底部
 * - 出现新消息时显示"回到底部"按钮
 */
import { ref, watch, nextTick, type Ref } from 'vue'

export function useAutoScroll(container: Ref<HTMLElement | null>) {
  const isAtBottom = ref(true)
  const showScrollButton = ref(false)

  const SCROLL_THRESHOLD = 80

  function checkBottom() {
    const el = container.value
    if (!el) return
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight
    isAtBottom.value = distance <= SCROLL_THRESHOLD
    showScrollButton.value = !isAtBottom.value
  }

  function scrollToBottom(smooth = true) {
    const el = container.value
    if (!el) return
    el.scrollTo({
      top: el.scrollHeight,
      behavior: smooth ? 'smooth' : 'instant'
    })
    isAtBottom.value = true
    showScrollButton.value = false
  }

  function onScroll() {
    checkBottom()
  }

  /** 监听消息变化，自动跟随到底部 */
  function watchMessages(messages: Ref<unknown[]>) {
    watch(
      () => messages.value.length,
      async () => {
        await nextTick()
        if (isAtBottom.value) {
          scrollToBottom()
        }
      }
    )
  }

  return {
    isAtBottom,
    showScrollButton,
    scrollToBottom,
    onScroll,
    watchMessages
  }
}
