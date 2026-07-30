<script setup lang="ts">
import { cn } from '@/utils'

withDefaults(defineProps<{
  class?: string
}>(), {
  class: ''
})
</script>

<template>
  <div
    :class="cn('rounded-lg border border-[var(--card-border)] bg-[var(--card)] text-[var(--card-foreground)] shadow-sm', $props.class)"
  >
    <slot />
  </div>
</template>
