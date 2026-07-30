/**
 * Sheet 组件 — 基于 Reka UI Dialog，侧边抽屉。
 * 用于移动端侧边栏。
 * 支持遮罩、Esc 关闭、焦点锁定、无障碍语义。
 */
<script setup lang="ts">
import {
  DialogRoot,
  DialogPortal,
  DialogOverlay,
  DialogContent,
  DialogTitle,
  DialogClose
} from 'reka-ui'

withDefaults(defineProps<{
  open?: boolean
  side?: 'left' | 'right'
}>(), {
  open: undefined,
  side: 'left'
})

const emit = defineEmits<{
  'update:open': [value: boolean]
}>()
</script>

<template>
  <DialogRoot :open="open" @update:open="emit('update:open', $event)">
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 z-40 bg-black/50 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />
      <DialogContent
        :class="[
          'fixed inset-y-0 z-50 w-[280px] bg-[var(--sidebar)] border-r border-[var(--sidebar-border)] p-0 shadow-lg transition-transform',
          side === 'left' ? 'left-0 data-[state=open]:slide-in-from-left data-[state=closed]:slide-out-to-left' : 'right-0 data-[state=open]:slide-in-from-right data-[state=closed]:slide-out-to-right'
        ]"
        @pointer-down-outside="emit('update:open', false)"
      >
        <DialogTitle class="sr-only">侧边栏</DialogTitle>
        <slot />
        <DialogClose
          class="absolute right-2 top-2 rounded-sm opacity-70 hover:opacity-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] transition-opacity p-1"
          aria-label="关闭侧边栏"
        >
          ✕
        </DialogClose>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
