<script setup lang="ts">
import { cn } from '@/utils'

const props = withDefaults(defineProps<{
  class?: string
  modelValue?: string
  placeholder?: string
  disabled?: boolean
  rows?: number
  maxlength?: number
}>(), {
  modelValue: '',
  placeholder: '',
  disabled: false,
  rows: 1
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'keydown': [event: KeyboardEvent]
}>()

function onInput(event: Event) {
  const target = event.target as HTMLTextAreaElement
  emit('update:modelValue', target.value)
}
</script>

<template>
  <textarea
    :value="modelValue"
    :placeholder="placeholder"
    :disabled="disabled"
    :rows="rows"
    :maxlength="maxlength"
    :class="cn(
      'flex w-full rounded-md border border-[var(--border)] bg-transparent px-3 py-2 text-sm placeholder:text-[var(--muted-foreground)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50 resize-none',
      props.class
    )"
    @input="onInput"
    @keydown="emit('keydown', $event)"
  />
</template>
