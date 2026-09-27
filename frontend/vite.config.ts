import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
export default defineConfig({
  base: '/admin/',
  plugins: [vue()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: { '/api': process.env.DOCQUERY_API_TARGET || 'http://127.0.0.1:8080' },
  },
  test: {
    maxWorkers: 1,
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    exclude: ['e2e/**', '**/node_modules/**', '**/dist/**'],
  },
})
