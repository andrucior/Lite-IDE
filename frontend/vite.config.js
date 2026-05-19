import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev proxy lets the frontend hit `/api` and `/ws` against the Vite origin so the
// browser doesn't have to know the backend's host:port and so CORS stays simple.
// `ws: true` is required for the collab WebSocket upgrade.
// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws':  { target: 'ws://localhost:8080',  ws: true, changeOrigin: true },
    },
  },
})
