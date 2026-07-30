import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

const srcDir = fileURLToPath(new URL('./src', import.meta.url))

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': srcDir
    }
  },
  test: {
    globals: true,
    environment: 'happy-dom',
    exclude: ['node_modules', 'dist', 'e2e']
  }
})
