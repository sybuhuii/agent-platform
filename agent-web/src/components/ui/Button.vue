<script setup lang="ts">
import { computed } from 'vue'
import { cn } from '@/utils'

const props = withDefaults(defineProps<{
  variant?: 'default' | 'secondary' | 'destructive' | 'outline' | 'ghost'
  size?: 'default' | 'sm' | 'lg' | 'icon'
  disabled?: boolean
  class?: string
}>(), {
  variant: 'default',
  size: 'default',
  disabled: false
})

const classes = computed(() =>
  cn(
    'inline-flex items-center justify-center whitespace-nowrap rounded-md text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50',
    {
      'bg-[var(--accent)] text-[var(--accent-foreground)] hover:bg-[var(--accent)]/90':
        props.variant === 'default',
      'bg-[var(--muted)] text-[var(--muted-foreground)] hover:bg-[var(--muted)]/80':
        props.variant === 'secondary',
      'bg-[var(--destructive)] text-[var(--destructive-foreground)] hover:bg-[var(--destructive)]/90':
        props.variant === 'destructive',
      'border border-[var(--border)] bg-transparent hover:bg-[var(--muted)] hover:text-[var(--muted-foreground)]':
        props.variant === 'outline',
      'hover:bg-[var(--muted)] hover:text-[var(--muted-foreground)]':
        props.variant === 'ghost',
    },
    {
      'h-9 px-4 py-2': props.size === 'default',
      'h-8 rounded-md px-3 text-xs': props.size === 'sm',
      'h-10 rounded-md px-8': props.size === 'lg',
      'h-9 w-9': props.size === 'icon',
    },
    props.class
  )
)
</script>

<template>
  <button :class="classes" :disabled="disabled">
    <slot />
  </button>
</template>
