# AGENTS.md

给 AI 编码代理（以及未来的自己）的项目规则。保持简短权威：只写"从代码里不容易推断"和"容易做错"的规则。完整论证不在本文件，在 docs/ 的两份文档里。

## 项目定位

Multica 复刻：AI 原生任务管理平台，agent 是一等公民受理人。参照原项目（Go）位于 /home/alen/multica-reference —— 下文所有"参照原项目"路径相对该目录。参照仓库的作用是**语义锚点**：移植任何一块行为，先读它的源码与测试，以测试锚定语义，不靠目测。

两份权威文档（冲突时以详细设计为准，再以里程碑文档为序）：

- `docs/Multica复刻-任务拆解与里程碑方案.md` —— 做什么、什么顺序、多少工作量（M0-M4）
- `docs/design/` —— 详细设计，按章节拆分（README.md 为索引，01-12 对应 §一-§十二；"详细设计 §N"即文件 `NN-*.md`）
- `docs/reference/` —— 原项目机制深读笔记（prompt 设计哲学等；设计决策的"为什么"出处，含 MUL 票据号）

注意：参照仓库的 git log 是 squash 快照，不是开发历史；任何时间线论断以其 i18n changelog 为准。

## 技术栈（否决项与选型同等有效）

JDK 21 + Spring Boot 3.5（WebMvc）+ MyBatis（XML mapper）+ Flyway + PostgreSQL 17 + 原生 Jakarta WebSocket + Jackson；前端 Vite + React 18 + TypeScript + React Query v5 + Zustand + zod + Tailwind，pnpm workspace。

已明确否决，不要以"更先进/更常见"为由重新引入：

- **WebFlux** —— 阻塞式 + 虚拟线程覆盖全部 IO 并发；仅 SSE/WebSocket 两处天然异步，用 SseEmitter / TextWebSocketHandler
- **JPA** —— 核心 SQL 全是 PG 方言一等公民（FOR UPDATE SKIP LOCKED、partial unique index、JSONB），MyBatis 保留 SQL 原文，review 看到的就是执行的
- **STOMP** —— 帧协议是自定义 JSON（task:queued、issue:updated 等）
- **Next.js** —— 复刻范围不含 SSR/SEO 面，Vite SPA
- **自研迁移 runner** —— Flyway 的纪律替代原项目两条自研教训（回滚 fail-closed、并发建索引 retry）

## 模块划分（M0 脚手架落地）

```
contracts/        协议 DTO + 事件类型枚举 + reason code；server/daemon 共用
server/           multica-server：Spring Boot API（WebMvc + SSE/WS + MyBatis + Flyway）
daemon/           multica-daemon：Spring Boot（无 web 容器）loop/ env/ agent/
web/apps/web/     Vite React SPA
web/packages/core/  无头业务层：api client + React Query hooks + Zustand stores
web/packages/ui/    原子组件，禁止 import core
```

包根 `io.multica`。

## 硬规则

数据库：

- Flyway 单向迁移 `V<n>__<name>.sql`，不写 down；需要回滚时按 fail-closed 纪律单写
- 含 `CREATE INDEX CONCURRENTLY` 的迁移必须单语句单文件，头注释 `-- flyway:executeInTransaction=false`（M0 空库不需要，M1 第一次线上加索引起生效）
- **M0 六表带 FK CASCADE 是刻意简化**（与原项目"永不用 FK、关系在应用层维护"相反）。不要"修复"它，也不要把此简化扩展到 M0 之外的新表
- `idx_one_pending_task_per_issue_agent` 第一天就建：per-(issue, agent) 而非 per-issue 全局（原项目 migration 037 的教训——不同 agent 的 pending 任务不该互挡）

协议与任务生命周期：

- **daemon↔server 从 M0 起走 HTTP（localhost），即使同进程内嵌**——协议即产品契约，M1 拆独立进程时代码零改动
- 任务终态叫 `completed`（不是 done），`dispatched` 是中间态——M0 就用最终词汇表，避免 M1 改枚举值
- usage 先于任何 early-return 上报（计费不可漏）；只有显式 completed 算成功（fail-closed）
- `cost_usd_ticks` 为 1e-10 美元刻度的 int64；成本必须请求级上报，禁止 token 数 × 费率折算
- 失败归因用稳定 reason code 枚举，在阻塞分支决定、原样携带，禁止从错误字符串解析

并发：

- 子进程 stdout/stderr 泵用固定平台线程池（JDK 21 synchronized 块内 IO 会 pin 住 carrier 线程）；HTTP/DB/任务编排用虚拟线程无碍
- 认领前先占本地并发槽：已认领任务绝不能停在服务端 dispatched 而本地无容量

前端：

- React Query 拥有服务端状态；Zustand 只放视图状态；WS 事件只 patch Query 缓存，绝不把服务端 payload 镜像进 store
- API 响应必须过 zod + parseWithFallback（失败降级不抛白屏），禁止把网络 JSON 直接 cast 成 T
- `web/packages/core/` 禁止 react-dom、localStorage、process.env；`web/packages/ui/` 禁止业务逻辑与 import core

## 移植纪律

- Go→Java 最大隐性成本是**语义漂移**。每移植一块，先写它的测试；竞态场景清单（详细设计 §9.3）就是 M1 验收标准
- 注释写"为什么"和"不这样会怎样"，可引用原项目票据号（MUL-xxxx）或 migration 编号作为出处——注释是事故史，不是说明书
- 修复预算 40%：原项目每月 fix 类产出占 40-48%，每个功能里程碑按此排期，不要全部排新功能
- 遇到原项目代码里"看不懂为什么这么写"的分支，先搜它的 MUL 票据引用再动手简化——通常是事故修的

## 环境（Windows 侧）

开发/构建/验证全部在 Windows 侧执行。当前工具链（2026-08 核对）：JDK 17.0.12、Node 24.15.0、pnpm 10.34.5（corepack）、Maven 3.9.16（choco）、Docker 29.4.0、gh 2.98.0。

当前 JDK 17 可跑 M0（Spring Boot 3 支持）；虚拟线程全量启用需 JDK 21（M1 并发任务上量前统一）。

开发库凭据可用仓库根 `.env` 覆盖（模板 `.env.example`，默认 `multica`，仅数据卷首次初始化时生效）；compose 端口仅绑定 `127.0.0.1`，开发库不得经局域网 IP 直连。

```bash
# M0 脚手架后生效
docker compose up -d postgres    # postgres:17，仅 127.0.0.1:5432
mvn spring-boot:run -pl server   # daemon M0 内嵌于 server
mvn spring-boot:run -pl daemon   # M1 起独立进程
pnpm dev                         # web/
mvn test                         # 后端（Testcontainers 需 docker）
pnpm test                        # 前端 Vitest
```

参照仓库（multica-reference）是外部只读克隆，不属于本仓库任何构建；经 `wsl -d Ubuntu` 访问（`/home/alen/multica-reference`），仅用于参考仓库读取。

## 提交

conventional commits：`feat(scope)` / `fix(scope)` / `docs` / `refactor(scope)` / `test(scope)` / `chore(scope)`。
