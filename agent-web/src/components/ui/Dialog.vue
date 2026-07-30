/**
 * Dialog 组件 — 基于 Reka UI DialogRoot。
 * 支持 Esc 关闭、焦点锁定、无障碍标题。
 */
<script setup lang="ts">
import {
  DialogRoot,
  DialogTrigger,
  DialogPortal,
  DialogOverlay,
  DialogContent,
  DialogTitle,
  DialogDescription,
  DialogClose
} from 'reka-ui'
import { cn } from '@/utils'

withDefaults(defineProps<{
  open?: boolean
  class?: string
}>(), {
  open: undefined,
  class: ''
})

const emit = defineEmits<{
  'update:open': [value: boolean]
}>()
</script>

<template>
  <DialogRoot :open="open" @update:open="emit('update:open', $event)">
    <slot />
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 z-50 bg-black/50 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />
      <DialogContent
        :class="cn(
          'fixed left-1/2 top-1/2 z-50 w-full max-w-lg -translate-x-1/2 -translate-y-1/2 rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-6 shadow-lg',
          'data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95',
          $props.class
        )"
        @pointer-down-outside="emit('update:open', false)"
      >
        <DialogTitle v-if="$slots.title" class="text-lg font-semibold mb-4">
          <slot name="title" />
        </DialogTitle>
        <DialogTitle v-else class="sr-only">对话框</DialogTitle>
        <DialogDescription v-if="$slots.description" class="text-sm text-[var(--muted-foreground)] mb-4">
          <slot name="description" />
        </DialogDescription>
        <slot name="content" />
        <DialogClose
          class="absolute right-4 top-4 rounded-sm opacity-70 hover:opacity-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] transition-opacity"
          aria-label="关闭"
        >
          ✕
        </DialogClose>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
