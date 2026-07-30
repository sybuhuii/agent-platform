<script setup lang="ts">
import { cn } from '@/utils'

withDefaults(defineProps<{
  class?: string
}>(), {
  class: ''
})
</script>

<template>
  <div :class="cn('animate-pulse rounded-md bg-[var(--muted)]', $props.class)" />
</template>
