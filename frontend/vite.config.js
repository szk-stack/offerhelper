import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/** VITE_BASE 传 "interview" 或 "/interview/" 都行，统一成 "/interview/" */
function normalizeBase(value) {
  if (!value || value === '/') return '/'
  return `/${value.replace(/^\/+|\/+$/g, '')}/`
}

export default defineConfig({
  // 部署到子路径时用 VITE_BASE=interview 构建；默认根路径
  base: normalizeBase(process.env.VITE_BASE),
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        timeout: 180000,  // 3 分钟，复盘用 glm-4-flash 一般 1-2 分钟内完成
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes, req) => {
            if (proxyRes.statusCode >= 400) {
              console.log('[Proxy]', req.method, req.url, '->', proxyRes.statusCode)
            }
          })
        }
      }
    }
  }
})
