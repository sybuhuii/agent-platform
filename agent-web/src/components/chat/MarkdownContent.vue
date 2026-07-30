/**
 * Markdown 渲染组件。
 * - 使用 markdown-it 解析
 * - 代码围栏由 Vue 组件（CodeBlock）接管
 * - 模型输出进入 DOM 前必须经过 DOMPurify
 * - 原始 HTML 保持禁用
 * - 不允许通过字符串拼接注入事件处理器
 * - 不允许直接把不可信模型输出交给 v-html（代码块除外，由 Shiki 生成并已转义）
 */
<script setup lang="ts">
import { computed, ref } from 'vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import CodeBlock from './CodeBlock.vue'

const props = defineProps<{
  content: string
}>()

const md = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: false,
  breaks: true
})

// 外部链接安全打开
md.renderer.rules.link_open = (tokens, idx, options, _env, self) => {
  const token = tokens[idx]!
  token.attrSet('target', '_blank')
  token.attrSet('rel', 'noopener noreferrer')
  return self.renderToken(tokens, idx, options)
}

/** 解析 markdown-it token，提取代码围栏信息 */
interface CodeFenceInfo {
  index: number
  language: string
  code: string
}

const fenceBlocks = computed<CodeFenceInfo[]>(() => {
  const tokens = md.parse(props.content, {})
  const blocks: CodeFenceInfo[] = []
  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i]!
    if (token.type === 'fence' && token.tag === 'code') {
      blocks.push({
        index: i,
        language: token.info || 'text',
        code: token.content
      })
    }
  }
  return blocks
})

/** 渲染非代码围栏内容 */
const inlineHtml = computed(() => {
  const tokens = md.parse(props.content, {})
  const parts: string[] = []
  const fenceIndices = new Set(fenceBlocks.value.map(f => f.index))

  // 处理 fence token — 替换为占位符
  for (let i = 0; i < tokens.length; i++) {
    if (fenceIndices.has(i)) continue // 代码围栏由 Vue 组件渲染

    const token = tokens[i]!
    if (token.type === 'fence' && token.tag === 'code') {
      // 跳过开闭标签
      continue
    }
    // 非围栏 token 用 markdown-it 正常渲染
  }

  // 更简单的方法：渲染整个 markdown，但把代码围栏替换为占位符
  // 使用 markdown-it fence 自定义 renderer 输出占位标记
  return props.content
})

// 使用自定义 fence renderer 输出安全占位符
const PLACEHOLDER_PREFIX = '<!--CODE_BLOCK_'

const mdWithPlaceholders = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: false,
  breaks: true
})

// 外部链接安全打开
mdWithPlaceholders.renderer.rules.link_open = (tokens, idx, options, _env, self) => {
  const token = tokens[idx]!
  token.attrSet('target', '_blank')
  token.attrSet('rel', 'noopener noreferrer')
  return self.renderToken(tokens, idx, options)
}

// 代码围栏输出安全占位符
mdWithPlaceholders.renderer.rules.fence = (tokens, idx) => {
  const token = tokens[idx]!
  const lang = token.info || 'text'
  // 使用 data 属性而非事件处理器，安全且可被 DOMPurify 保留
  const code = escapeAttr(token.content)
  return `<div data-code-block data-lang="${lang}" data-code="${code}"></div>`
}

function escapeAttr(str: string): string {
  return str
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\n/g, '&#10;')
}

const rawHtml = computed(() => mdWithPlaceholders.render(props.content))

const sanitizedHtml = computed(() =>
  DOMPurify.sanitize(rawHtml.value, {
    ADD_ATTR: ['target', 'rel', 'data-code-block', 'data-lang', 'data-code']
  })
)

/** 从 sanitized HTML 中提取代码块信息，用于 Vue 组件渲染 */
interface ParsedBlock {
  language: string
  code: string
}

const codeBlocks = computed<ParsedBlock[]>(() => {
  const blocks: ParsedBlock[] = []
  // 解析占位符
  const regex = /data-code-block[^>]*data-lang="([^"]*)"[^>]*data-code="([^"]*)"/g
  let match: RegExpExecArray | null
  while ((match = regex.exec(sanitizedHtml.value)) !== null) {
    const lang = match[1]!
    const encoded = match[2]!
    // 反转义
    const code = encoded
      .replace(/&#10;/g, '\n')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"')
      .replace(/&amp;/g, '&')
    blocks.push({ language: lang, code })
  }
  return blocks
})

/** 最终渲染的段落（非代码 HTML + 代码组件） */
const hasCodeBlocks = computed(() => codeBlocks.value.length > 0)

/** 清理占位符后的纯 HTML（非代码部分） */
const nonCodeHtml = computed(() => {
  if (!hasCodeBlocks.value) return sanitizedHtml.value
  // 移除代码占位符
  return sanitizedHtml.value.replace(/<div data-code-block[^>]*><\/div>/g, '')
})
</script>

<template>
  <div class="markdown-content">
    <!-- 无代码块：直接渲染 HTML -->
    <div v-if="!hasCodeBlocks" v-html="sanitizedHtml" />
    <!-- 有代码块：分段渲染 -->
    <template v-else>
      <div v-html="nonCodeHtml" />
      <CodeBlock
        v-for="(block, index) in codeBlocks"
        :key="index"
        :code="block.code"
        :language="block.language"
      />
    </template>
  </div>
</template>
