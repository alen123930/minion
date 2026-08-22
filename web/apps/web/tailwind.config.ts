import type { Config } from "tailwindcss";

export default {
  // content 必须显式包含 packages/ui 源码：Tailwind 按扫描到的类名生成 CSS，
// 与 import 图无关——漏扫时 ui 组件类名会静默缺失（源码看着正常，产物无样式）。
content: [
  "./index.html",
  "./src/**/*.{ts,tsx}",
  "../../packages/ui/src/**/*.{ts,tsx}",
],
  theme: {
    extend: {},
  },
  plugins: [],
} satisfies Config;