<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="flex flex-col items-center justify-center h-full text-[var(--muted-foreground)] py-20">
    <p class="text-lg font-medium text-[var(--foreground)] mb-2">权限不足</p>
    <p class="text-sm mb-4">您没有访问此页面的权限</p>
    <button
      class="rounded-lg bg-[var(--accent)] text-[var(--accent-foreground)] px-4 py-2 text-sm font-medium hover:bg-[var(--accent)]/90 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
      @click="router.push('/')"
    >
      返回首页
    </button>
  </div>
</template>
