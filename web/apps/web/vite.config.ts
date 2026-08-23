import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  server: {
    // server 无 CORS，dev 经代理同源访问 API；SSE 长连接由 http-proxy 透传。
    // 同机多 run 开发环境 8080 可能被其它服务占用，允许 LEGION_API_TARGET 覆盖（默认 server 端口）。
    proxy: {
      "/api": {
        target: process.env.LEGION_API_TARGET ?? "http://127.0.0.1:8080",
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
  },
});
