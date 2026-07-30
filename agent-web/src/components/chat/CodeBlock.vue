/**
 * 代码块组件 — 支持语言标签、复制按钮和按需高亮。
 * 使用 shiki/bundle/web 按需加载语言和主题，不导入完整集合到首屏。
 */
<script setup lang="ts">
import { ref, onMounted, computed, watch, onUnmounted } from 'vue'
import { createHighlighter, type Highlighter } from 'shiki/bundle/web'

const props = defineProps<{
  code: string
  language?: string
}>()

const highlighted = ref('')
const loading = ref(false)
const copied = ref(false)
const copyFailed = ref(false)
let copyTimer: ReturnType<typeof setTimeout> | null = null

/** 单例 Highlighter — 延迟创建，按需加载语言 */
let highlighterInstance: Highlighter | null = null
let highlighterReady: Promise<Highlighter> | null = null

async function getHighlighter(): Promise<Highlighter> {
  if (highlighterInstance) return highlighterInstance
  if (highlighterReady) return highlighterReady

  highlighterReady = (async () => {
    const hl = await createHighlighter({
      themes: ['github-dark'],
      langs: [] // 空初始化，按需加载
    })
    highlighterInstance = hl
    return hl
  })()

  return highlighterReady
}

/** 安全语言映射 */
const LANG_ALIASES: Record<string, string> = {
  js: 'javascript',
  ts: 'typescript',
  py: 'python',
  sh: 'bash',
  zsh: 'bash',
  yml: 'yaml',
  md: 'markdown'
}

const SUPPORTED_LANGS = new Set([
  'javascript', 'typescript', 'python', 'java', 'json', 'html', 'css',
  'bash', 'shell', 'sql', 'markdown', 'yaml', 'xml', 'go', 'rust',
  'c', 'cpp', 'csharp', 'ruby', 'php', 'swift', 'kotlin', 'scala',
  'diff', 'plaintext', 'text'
])

function mapLang(raw: string): string {
  const lower = raw.toLowerCase()
  if (SUPPORTED_LANGS.has(lower)) return lower
  if (LANG_ALIASES[lower]) return LANG_ALIASES[lower]!
  return 'text'
}

const lang = computed(() => mapLang(props.language || 'text'))
const displayLang = computed(() => (props.language || 'text').toLowerCase())

async function highlight() {
  if (!props.code) return
  loading.value = true
  try {
    const hl = await getHighlighter()
    const resolvedLang = lang.value

    // 按需加载语言
    if (resolvedLang !== 'text' && !hl.getLoadedLanguages().includes(resolvedLang)) {
      try {
        // Shiki 的 loadLanguage 接受 BuiltinLanguage 类型
        // 使用类型断言绕过限制：resolvedLang 已通过白名单验证
        const loaded = await hl.loadLanguage(resolvedLang as Parameters<typeof hl.loadLanguage>[0])
      } catch {
        // 语言不支持，回退
        highlighted.value = `<pre><code>${escapeHtml(props.code)}</code></pre>`
        return
      }
    }

    if (resolvedLang === 'text') {
      highlighted.value = `<pre><code>${escapeHtml(props.code)}</code></pre>`
      return
    }

    highlighted.value = hl.codeToHtml(props.code, {
      lang: resolvedLang,
      theme: 'github-dark'
    })
  } catch {
    highlighted.value = `<pre><code>${escapeHtml(props.code)}</code></pre>`
  } finally {
    loading.value = false
  }
}

function escapeHtml(str: string): string {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

onMounted(highlight)
watch(() => props.code, highlight)

onUnmounted(() => {
  if (copyTimer) {
    clearTimeout(copyTimer)
    copyTimer = null
  }
})

async function copyCode() {
  copyFailed.value = false
  try {
    await navigator.clipboard.writeText(props.code)
    copied.value = true
    copyFailed.value = false
    copyTimer = setTimeout(() => {
      copied.value = false
      copyTimer = null
    }, 2000)
  } catch {
    copyFailed.value = true
    copied.value = false
    copyTimer = setTimeout(() => {
      copyFailed.value = false
      copyTimer = null
    }, 2000)
  }
}
</script>

<template>
  <div class="code-block-wrapper">
    <div class="code-block-header">
      <span class="code-block-lang">{{ displayLang }}</span>
      <button
        class="code-block-copy"
        :aria-label="copyFailed ? '复制失败' : copied ? '已复制' : '复制代码'"
        @click="copyCode"
      >
        {{ copyFailed ? '复制失败' : copied ? '已复制' : '复制' }}
      </button>
    </div>
    <div v-if="highlighted" v-html="highlighted" />
    <pre v-else><code>{{ code }}</code></pre>
  </div>
</template>
