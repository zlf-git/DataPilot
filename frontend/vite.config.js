import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 后端地址：dev / preview 都把 /api 反代过去，页面代码里永远用相对路径。
// 这样「同源免 CORS」在开发期就已经成立，不用等到上线才发现跨域。
const target = process.env.DATAPILOT_API || 'http://127.0.0.1:18080'
const proxy = { '/api': { target, changeOrigin: true } }

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy },
  preview: { port: 5174, proxy },
  build: {
    outDir: 'dist',
    sourcemap: false,
    // antd 单包 1.1MB 会让首次加载等一个 350KB(gzip) 的大块；按 react/antd 拆开后
    // 业务代码改动不会让用户重新下载整个组件库
    rollupOptions: {
      output: {
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          antd: ['antd', '@ant-design/icons'],
        },
      },
    },
  },
})
