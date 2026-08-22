# Multica 复刻：详细设计（Java Spring Boot + React）

定位：里程碑文档（《Multica复刻-任务拆解与里程碑方案.md》）管"做什么、什么顺序、多少工作量"；本文管"具体怎么做"。两者共用同一套 M0-M4 里程碑，本文不改变顺序与验收判据，只把落点从 Go 参照栈翻译到 Java Spring Boot + React 栈。

所有 server/... pkg/... 参照路径仍相对 /home/alen/multica-reference（Go 原仓库），它们的作用是"语义锚点"——移植时以测试锚定语义，不靠目测。

---

## 一、技术栈与选型理由

**JDK 21 + Spring Boot 3.5.x（WebMvc）**。JDK 21 是虚拟线程的首个 LTS；本系统两类并发形态（HTTP 请求处理、daemon 长循环 + 每任务一个执行单元）用虚拟线程最省心。团队现有 JDK 17 环境也可运行（Spring Boot 3.x 支持 17），代价是任务执行单元退回固定线程池——M0 阶段差异无感，上 M1 并发任务多了再统一到 21。

不用 WebFlux：整个系统的 IO 并发用"每请求/每任务一个虚拟线程"的阻塞式写法即可覆盖，WebFlux 的响应式链条会显著抬高 daemon 子进程管理这种命令式代码的维护成本。仅 SSE 和 WebSocket 两处天然异步，Spring 对这两者都提供了阻塞式友好 API（SseEmitter / TextWebSocketHandler）。

**MyBatis（XML mapper），不用 JPA**。这是本设计最重要的一条否决。原项目的核心 SQL 全是 PostgreSQL 方言一等公民：`FOR UPDATE SKIP LOCKED` 认领、partial unique index 去重、`make_interval` 租约计算、advisory lock、JSONB。sqlc 的哲学是 SQL 原文即代码，JPA 的哲学是把 SQL 藏起来——两者在这套系统里正面冲突。MyBatis 保留 SQL 原文，review 时看到的就是执行的。jOOQ 是更接近 sqlc 的选择（SQL-first + 代码生成），但团队熟悉度和国内生态都倾向 MyBatis，此处从众。

**Flyway，不自研迁移 runner**。原项目自研 runner 的两条教训（回滚 fail-closed MUL-6305、并发建索引 retry MUL-6288）在 Flyway 里表现为纪律而非代码：见 §3.1 的 CONCURRENTLY 坑。

**PostgreSQL 17 原样保留**。整个调度设计内生于 PG 方言，换库等于重新设计。

**原生 Jakarta WebSocket（spring-boot-starter-websocket），不用 STOMP**。帧协议是自定义 JSON（task:queued、issue:updated 等），STOMP 的语义层帮不上忙反而添乱。认证在 HandshakeInterceptor 读 Authorization 头完成——对应原项目"身份在 upgrade 前由 HTTP 头钉死"的设计。

**Jackson** 全线 JSON；**cron-utils**（M3 scheduler 用）；**Caffeine**（空认领缓存用）。

前端：**Vite + React 18 + TypeScript**，**React Query v5**（服务端状态）+ **Zustand**（视图状态）+ **zod**（API 边界校验）+ Tailwind（原子样式，shadcn 形态）。pnpm workspace 管多包。不用 Next.js：复刻范围明确不含 SSR/SEO 面（原产品是应用不是站点），Vite SPA 少一整层运行时复杂度；路由用 react-router。

测试：JUnit 5 + Testcontainers（Postgres）+ Vitest + Testing Library。

## 二、模块划分

```
multica/
├── pom.xml                      # Maven 父 POM
├── contracts/                   # multica-contracts：协议 DTO + 事件类型枚举 + reason code
│                                 #    server/daemon 共用；前端类型由此手抄对齐（或后续 codegen）
├── server/                      # multica-server：Spring Boot API server（WebMvc + SSE/WS + MyBatis + Flyway）
├── daemon/                      # multica-daemon：Spring Boot 应用（无 web 容器，仅 actuator）
│   └── src/main/java/io/multica/daemon/
│       ├── loop/                # poll/heartbeat/wakeup 循环（§5）
│       ├── env/                 # execenv 磁盘契约 + GC（§5.5）
│       └── agent/               # Agent 适配层（§6）
│   └── src/main/resources/mappers/
└── web/                         # pnpm workspace（§8）
    ├── apps/web/                # Vite + React SPA
    └── packages/
        ├── core/                # 无头业务层：api client + React Query hooks + Zustand stores
        └── ui/                  # 原子组件，禁止 import core
```

**关键决策：daemon 与 server 之间从 M0 起就走 HTTP，即使两者同进程。** M0 阶段 daemon 模块由 server 以库形式内嵌启动，但它对 server 的所有调用走 `localhost` HTTP（client 层照原项目 client.go 的协议形态写）。理由：daemon↔server 协议本身就是产品契约，M1 拆出独立进程时代码零改动，避免"内嵌直调函数、拆分时重写一遍"的欠账。

包根统一 `io.multica`。

## 三、数据库设计

### 3.1 Flyway 约定

- `V<n>__<name>.sql` 单向迁移，不写 down（原项目 up/down 成对但实践里 down 几乎只用于开发；团队需要 down 时按里程碑文档的 fail-closed 纪律单写）。
- **含 `CREATE INDEX CONCURRENTLY` 的迁移必须单语句单文件**，并在该文件头注释 `-- flyway:executeInTransaction=false`（Flyway 需要此设置才能在事务外执行 CONCURRENTLY）。这条纪律对应原项目"每个并发索引独立迁移文件"的规则。
- M0 不需要 CONCURRENTLY（空库直接建），从 M1 第一次线上加索引起生效。

### 3.2 M0 六表

沿用里程碑文档 §5.1 的 DDL 草案（user/workspace/member/agent/issue/comment，带 FK CASCADE——M0 刻意简化，接受）。一处词汇修正：该文档中 agent_task 的终态叫 `done`，本设计统一为 `completed` 并新增 `dispatched` 中间态，与原仓库一致，避免 M1 演进时改枚举值。

### 3.3 agent_task_queue（M1 完整形态，M0 建表时即用此结构）

```sql
CREATE TABLE agent_task_queue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL,
    issue_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    runtime_id UUID,                       -- M1 起 NOT NULL；M0 内嵌 worker 可为 NULL
    status TEXT NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued','dispatched','running','waiting_local_directory',
                          'completed','failed','cancelled')),
    priority INT NOT NULL DEFAULT 0,
    context JSONB NOT NULL DEFAULT '{}',   -- prompt、归因快照（M1）、MCP overlay 等
    trigger_comment_id UUID,
    coalesced_comment_ids JSONB NOT NULL DEFAULT '[]',  -- 用 JSONB 而非 UUID[]，MyBatis TypeHandler 统一
    session_id TEXT,                       -- agent CLI 的会话 id，resume 用
    work_dir TEXT,
    result JSONB,
    error TEXT,
    failure_class TEXT,                    -- 稳定失败类别（provider_network 等），非自由文本
    input_tokens INT,
    output_tokens INT,
    cost_usd_ticks BIGINT NOT NULL DEFAULT 0,  -- 1e-10 美元刻度；成本必须请求级上报，禁止 token×费率折算
    prepare_lease_expires_at TIMESTAMPTZ,  -- M1：准备阶段租约
    dispatched_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_task_queue_claim
    ON agent_task_queue (priority DESC, created_at, id)
    WHERE status = 'queued';

-- 第一天就建：同一 (issue, agent) 至多一个 pending。M0 就能防重复入队，成本为零。
-- 原项目教训：最初是 per-issue 全局唯一（不同 agent 互相挡），改成 per-(issue,agent) 让
-- "一个 issue 多 agent 并行"成为一等公民（migration 037）。
CREATE UNIQUE INDEX idx_one_pending_task_per_issue_agent
    ON agent_task_queue (issue_id, agent_id)
    WHERE status IN ('queued','dispatched');

CREATE TABLE agent_runtime (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL,
    daemon_id UUID,                        -- 同一物理机的多个 runtime 共享
    name TEXT NOT NULL,
    provider TEXT NOT NULL,                -- claude / hermes / ...
    status TEXT NOT NULL DEFAULT 'offline',
    last_seen_at TIMESTAMPTZ,              -- 心跳新鲜度判据
    device_info JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

外键演进：M0 沿用六表的 CASCADE 简化；M1 迁移 `V?__queue_fence.sql` 移除 queue 表上的 workspace 外键，改为应用层 fence——入队/认领事务内先 `SELECT id FROM workspace WHERE id = #{wsId} FOR UPDATE`，行不存在或正在删除则整个事务放弃（对应原项目 `lock_task_owner_rows` + migration 284 的 teardown fence，MUL-5999）。

### 3.4 认领 SQL（M1 形态，协议核心，MyBatis XML 原样保留）

```xml
<update id="claimNextTask" parameterType="map" resultType="io.multica.contracts.AgentTaskRow">
    UPDATE agent_task_queue t
    SET status = 'dispatched', dispatched_at = now(), updated_at = now()
    WHERE t.id = (
        SELECT c.id
        FROM agent_task_queue c
        JOIN agent_runtime r ON r.id = c.runtime_id
        WHERE c.status = 'queued'
          AND r.status = 'online'
          AND r.last_seen_at > now() - interval '150 seconds'
          AND NOT EXISTS (
              SELECT 1 FROM agent_task_queue a
              WHERE a.issue_id = c.issue_id
                AND a.agent_id = c.agent_id
                AND a.status IN ('dispatched','running')
          )
        ORDER BY c.priority DESC, c.created_at ASC, c.id ASC
        LIMIT 1
        FOR UPDATE OF c SKIP LOCKED
    )
    RETURNING t.id, t.workspace_id, t.issue_id, t.agent_id, t.runtime_id,
              t.status, t.context, t.trigger_comment_id, t.priority, t.created_at
</update>
```

**每 agent 容量上限是应用层约束，不是 DB 约束**（照抄原项目）。容量检查与认领必须在同一事务内，且先对 agent 行加锁串行化同一 agent 的并发认领：

```java
@Transactional
public AgentTaskRow claim(String agentId, String runnerId) {
    agentMapper.lockById(agentId);                 // SELECT id FROM agent WHERE id=#{id} FOR UPDATE
    var agent = agentMapper.findById(agentId);
    int active = taskMapper.countActiveByAgent(agentId);  // status IN ('dispatched','running','waiting_local_directory')
    if (active >= agent.maxConcurrentTasks()) return null;
    return taskMapper.claimNextTask(agentId, runnerId);
}
```

这个"agent 行锁 + 容量 + 认领同事务"的属性由竞态测试证明（§9.3 的 pg_sleep 触发器手法，即原项目 TestClaimTaskConcurrentCapacityRespected 的移植）。

M0 内嵌 worker 版：去掉 runtime join（没有 runtime 概念），`WHERE status='queued'` 直接认领，其余结构一致——M1 换完整 SQL，Java 代码不动。

### 3.5 终态幂等

```xml
<update id="completeTask">
    UPDATE agent_task_queue
    SET status='completed', result=#{result}::jsonb, completed_at=now(), updated_at=now()
    WHERE id=#{id} AND status='running'
</update>
```

0 行受影响 = 任务已被终态化（取消路径或重试路径抢先），方法返回成功而非报错——终态上报的 HTTP 重试因此天然幂等。fail-closed 纪律：只有显式 `completed` 算成功，缺 result 不许补成功。

## 四、Server 端设计

### 4.1 分层

controller（REST 薄层）→ service（业务与事务边界）→ mapper（MyBatis）。DTO 全部在 contracts 模块，controller 不出现实体行对象。事件广播用 Spring `ApplicationEventPublisher`（M0 进程内），M1 realtime WS hub 订阅同一事件流——事件路径不因传输层变化而重写。

### 4.2 API 面

M0（web 面，全部免认证、单 workspace 硬编码）：

```
GET    /api/issues                      列表（排序 updated_at DESC）
POST   /api/issues                      创建（title, description, assignee 可选）
GET    /api/issues/{id}                 详情（含 comments）
POST   /api/issues/{id}/comments        发评论
GET    /api/issues/{id}/stream          SSE：任务事件流（progress/message/terminal）
POST   /api/agents/{id}/tasks           手动触发一次 agent 运行（M0 的入队入口）
```

M1 起（daemon protocol，路径与语义照原项目 client.go 契约）：

```
POST /api/daemon/tasks/claim                     批量认领（请求携带 runtime 集合）
POST /api/daemon/tasks/{id}/prepare-lease        领取/续期准备租约（15s 心跳节奏）
POST /api/daemon/tasks/{id}/start                环境就绪、进程已 spawn（之后才计时执行超时）
POST /api/daemon/tasks/{id}/progress             阶段性进度（无业务语义，UI 用）
POST /api/daemon/tasks/{id}/messages             流式消息批次（transcript 增量）
POST /api/daemon/tasks/{id}/usage                usage 上报（先于一切 early return）
POST /api/daemon/tasks/{id}/complete | /fail     终态（幂等，见 §3.5）
POST /api/daemon/tasks/{id}/cancel-ack           取消确认
POST /api/daemon/heartbeat                       runtime 心跳（150s 新鲜度）
```

### 4.3 入队路径

单一谓词 `WillEnqueueRun(issue, change)` 决定一次写操作是否入队——create/assignee 变更/状态迁移/评论触发全部走它，预览接口也走它（原项目 MUL-3375 的教训：谓词散落各处后 drift）。M0 只实现"assign 给 agent 且状态非 backlog"一条规则；M1 加自环抑制（agent 自己的状态变更不再触发自己的任务）与 backlog 停车场语义。

入队后顺序契约：先广播 `task:queued` 事件、再唤醒 daemon（M0 无 WS，跳过唤醒；M1 起 bump 空认领缓存版本号在 WS 唤醒之前——顺序反了被唤醒的 claim 会读到未失效的"空"判定，白唤醒）。

M1 归因门（MUL-4302 精神，不必全量）：入队前解析 originator/accountable，accountable 不许为 NULL；解析失败按 fail-closed 拒绝入队而不是静默落库。M0 可以全部记为单用户。

### 4.4 空认领缓存（M1）

Caffeine 缓存"runtime 无任务"的否定判定（3 分钟 TTL），配 per-runtime 单调版本号：入队侧先 `version.incr(runtimeId)` 再唤醒；缓存值携带判定时的版本号，读侧版本不匹配视为 miss。只缓存否定结果，命中肯定结果的永远打 DB。最坏情况是并发入队时多一次 SELECT，不会卡任务——这条注释原文抄进代码。

### 4.5 Reason code

```java
public enum DispatchReason {
    QUEUED, DEFERRED, RUNTIME_OFFLINE, RUNTIME_UNUSABLE,
    INVOCATION_NOT_ALLOWED, CAPACITY_EXCEEDED, COALESCED, ATTRIBUTION_BLOCKED
}
```

纪律照抄：在阻塞分支处一次决定、逐层透传原值、禁止从错误字符串解析、不泄露私有 agent 的存在性（对外只见 runtime_offline，不见"某 agent 不存在"）。

### 4.6 实时通道

M0：SseEmitter。server 持有 `Map<issueId, List<SseEmitter>>`，daemon 的 messages/progress 上报映射成 SSE 事件转发；每 30s 发一条注释帧保活（代理会掐静默连接）。连接断开从注册表移除。

M1：realtime WebSocket hub（Web 前端用）+ daemonws hub（daemon 用）分开两个端点。daemonws 移植原项目的三条不变量：帧只是 best-effort 提示、HTTP 轮询是正确性路径；投递永不阻塞（每 session 有界队列 16，满即驱逐断连）；有副作用不可回退的操作（认领事务）不做中途取消。三级索引 `ConcurrentHashMap<runtimeId/workspaceId/userId, Set<Session>>` 结构平移。

### 4.7 认证演进

M0 免。M1 双轨：daemon 用 PAT（`X-Daemon-Token` 头，注册时签发）；web 用 JWT（登录签发，内存持有，M2 升级 HttpOnly cookie + refresh）。workspace 隔离从 M1 起全部查询带 workspace_id 条件 + `X-Workspace-ID` 头选择。

### 4.8 MyBatis 配置基线

统一 TypeHandler 集中注册（UUID↔uuid、Jsonb↔Jackson JsonNode/record、OffsetDateTime↔timestamptz），放在 contracts 或 server 的 config 包一次性注册，禁止各 mapper 各写各的。mapUnderscoreToCamelCase 开启。

## 五、Daemon 端设计

### 5.1 线程模型

```
主调度线程（ScheduledExecutorService, 5s 间隔）
  └─ poll：acquire slot → claim → 提交任务
任务执行：每任务一个虚拟线程
  ├─ 子进程 stdout 泵：固定平台线程池（见 §11.1 pinning 说明）
  ├─ 子进程 stderr 泵：同上
  └─ 取消监视：单独 ScheduledExecutor（5s）
心跳循环：独立调度线程（M1）
环境准备：prepare 阶段 15s 租约续期定时器
```

**slot-before-claim**：`Semaphore maxConcurrent`（默认 20）先 acquire 再发 claim 请求。被认领的任务绝不允许在 server 侧停留在 dispatched 而本地没有容量。任务结束释放槽位并立刻 nudge poll 循环（不等下一个周期）——继任任务立刻可被认领。

### 5.2 任务生命周期（M1 形态）

claim → 领取 prepare lease（此后每 15s 续期，硬超时 5 分钟，与 agent 执行超时完全独立）→ execenv.Prepare（§5.4）→ POST start（只在环境落盘后）→ spawn agent CLI（§6）→ 流式 POST messages → 终态：先 POST usage（先于任何 early return，计费纪律），再 complete/fail → 释放槽 → 写 GC 元数据 → nudge poll。

取消监视每 5s 轮询任务状态：只有 terminal 状态或 404 才杀本地进程组；瞬态网络错误、5xx 永不取消。杀进程用整组终止协议（§6.3）。

### 5.3 结算纪律（从原项目逐条照抄）

usage 先报；fail-closed（只有显式 completed 算成功）；session_id 与 work_dir 在每条上报路径都携带（chat resume 依赖）；终态上报失败按计划重试（带退避）。

### 5.4 execenv 磁盘契约

目录形态：`{workspacesRoot}/{workspaceId}/{taskId 前 8 位}/`，内含 `workdir/`、`output/`、`logs/` 与三个点文件。契约的核心是**写入时机**：

- `.managed_env.json`（managed_by/workspace/issue/agent 四元组）——Prepare 时刻写。它是"同 issue 后续任务可复用此环境"的资格证明。必须在诞生时写而不是完成时写：后续任务可能在上一任务完成后的瞬间被认领，早于上一任务写 `.gc_meta.json`（原项目 MUL-4886 的竞态）。
- `.gc_meta.json`（kind: issue/chat、completed_at、local_directory 标记）——终态时写，GC 据此决策。
- `.multica_sidecar_manifest.json`——写进 workdir 的所有文件清单；Prepare 失败时按它回滚（回滚 defer 在第一次写之前武装，MUL-6132）。

M0 简化：只做 workdir + `.agent_context/issue_context.md`（任务简报）+ `.gc_meta.json` + 72h 孤儿 TTL 扫描。manifest 与 managed_env 从 M1 加。

GC 决策树 M1 版按原项目规则移植：活跃任务（内存引用计数）无条件跳过 → 读 meta 失败走 mtime 孤儿兜底 → 按 kind 问父记录（issue 终态+TTL、chat 404 即收）→ local_directory 降级只清 artifact。原则性纪律：可再生缓存（可重建的副本）和不可再生数据走不同回收路径；删前的每一步昂贵检查（dirSize 遍历）之后要复查一次"还活着吗"。

### 5.5 进程树终止（Java 实现）

```java
static void destroyTree(Process p, Duration grace) throws InterruptedException {
    p.descendants().forEach(ProcessHandle::destroy);   // 先 SIGTERM 全部后代
    p.destroy();
    if (!awaitTreeExit(p, grace)) {                    // 判定"整组是否退出"，不是 leader
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }
}
```

原项目 GH #5918 的教训照搬：`Process.waitFor()` 返回只代表 leader 死了；一个不持 stdout 的 SIGTERM 免疫子进程会让进程树判定误通过，恰恰放过孤儿。Windows 上 descendants() 有覆盖不全的场景，兜底 `taskkill /PID <pid> /T /F`；原项目 Windows 支持踩了一个月（v0.1.28→0.2.11），M2 阶段就引入 Windows CI。

## 六、Agent 适配层

### 6.1 接口

```java
public interface AgentBackend {
    String provider();                       // "claude" / "hermes" / ...
    Session execute(ExecRequest request) throws BackendException;
}

public record ExecOptions(
    Path workDir,
    String systemPrompt,                     // 通常为空：runtime 简报写进 workdir 的 CLAUDE.md/AGENTS.md，
                                             // 由 CLI 从 cwd 自行加载；显式传参会与文件内容重复
    String model,                            // null = 默认
    String resumeSessionId,                  // null = 新会话
    Duration totalTimeout,                   // 三独立生命期，各自 null = 不启用
    Duration inactivityTimeout,              // 单轮静默
    Duration firstOutputTimeout,             // 首轮零产出
    Map<String, String> extraEnv
) {}

public record Session(
    BlockingQueue<StreamEvent> events,       // 流式事件；END 哨兵收尾（BlockingQueue 不能 close）
    CompletableFuture<Outcome> outcome        // 终态
) {}
```

`StreamEvent` 用 sealed interface：`AssistantText(text)`、`ToolUse(...)`、`SystemInfo(sessionId)`、`Usage(...)`、`End`。`Outcome` 是 `Success(output, usage, sessionId)` | `Failure(BackendFailureReason, message)`。不引入 reactive 框架—— BlockingQueue + CompletableFuture 足够，且与虚拟线程配合最直白。

### 6.2 claude 适配器（M0 唯一实现）

一次性 CLI 模式：`claude -p --output-format stream-json --verbose --permission-mode bypassPermissions`，prompt 走 stdin，stdout 按行读、Jackson 逐行解析。事件类型：assistant / user / system（取 sessionID）/ result（取 usage 与 outcome）/ log。进程退出即会话结束。

四个必须照抄的决策（源自里程碑文档任务 4，出处注释写进代码）：

1. **异步任务禁令**：事件流里观察到后台任务启动（sawAsyncLaunch）直接判任务失败——后台任务逃逸生命周期控制。
2. **进程组终止协议**（§5.5）。
3. **行长度上限 32MiB 定义在一处**：`StreamScanner` 类全局唯一定义（原项目 GH #4520：上限曾被各适配器复制粘贴后漂移，修复只到达其中一个）。
4. **成本请求级记录**：从 result 事件的 usage 原样取，禁止 token 数乘费率折算（阶梯计费无法从聚合数复现）。

watchdog 三独立生命期用三个 ScheduledFuture：total 定时到点杀树；inactivity 每收到一条事件重排；firstOutput 收到第一条事件后取消。三个问题独立配置、独立触发，不许合并成一个"超时"。

### 6.3 hermes 适配器（M2，ACP 家族）

实现清单（语义锚点 hermes.go 2914 行，此处只列移植要点）：

- ACP JSON-RPC over `hermes acp` stdio：initialize（声明支持的 MCP 传输，把 runtime 不支持的条目滤掉再 session/new；resume 请求也带 mcpServers，否则续会话丢工具）→ session/new|resume → session/set_model（仅当指定且与当前不同）→ session/prompt → session/update 通知流至 stopReason → 关 stdin；stdout 读线程 + stderr 拷贝线程先确定性 join（2s 宽限再杀）再判定结果。
- **stderr 嗅探器**：ACP 协议缺陷是底层 LLM 4xx/5xx 时 session/prompt 仍报 end_turn，真实原因只在 stderr。嗅探规则：区分 INFO/DEBUG 回显（回显里可能包含"HTTP 429"字样，不得误报）、跨行 JSON 按花括号深度拼接、长度上限只作用于存储消息、绝不作为归类前提。嗅出的 provider 错误升级为任务失败。
- **streamingCurrentTurn 闸门**：session/resume 会重放整个历史 transcript，闸门在 prompt 发出前关闭，历史事件全部丢弃，否则上一轮答案污染本轮输出。
- **resume 三态**：返回的 sessionID 与请求不一致 → 注入 continuity notice（措辞在 daemon 侧，因为只有调用方知道丢了什么）；session-not-found → ResumeRejected，daemon 换新会话重试；set_model 失败必须失败任务（用户在 UI 选了模型，静默 fallback 等于欺骗），但"已在请求模型上"时跳过（重选当前模型触发 provider 误路由，MUL-5029）。
- **argv 纪律**：`acp` 子命令对用户 custom_args 封锁；值旗标表镜像 Hermes 自己的 argv 扫描，保证 `-p research` 两侧解析一致。

### 6.4 假 agent

M0 联调用 `FakeBackend`（contracts 提供接口，daemon 里做一个 echo 实现：分几条事件、延时、可注入失败与超时）。所有默认测试禁止执行真实 agent CLI；真 CLI 冒烟测试用环境变量门控（`MULTICA_RUN_REAL_AGENT_SMOKE=1`），对应原项目 agentintegration build tag 的隔离纪律。

## 七、Scheduler（M3/延后，设计预留）

sys_cron_executions 内核整体移植：tryClaim 两段 SQL（INSERT ON CONFLICT DO NOTHING + 单条 UPDATE 同时编码 retry-after-FAILED 与 stale-steal 分支）、lease_token 守卫的终态写入、DB 时钟、每 tick 先清扫过期 RUNNING。SQL 原样照抄（§3 的方言同为 PG），Java 侧只剩 30s tick 循环 + JobSpec record + PlansForScope 接口（cron-utils 计算 (anchor, now] 的触发点，只取最新一个，迟到超 5 分钟放弃）。

两条必须记住的坑直接进代码注释：游标推进逻辑必须显式与重试逻辑和解（半开区间枚举天然跳过 FAILED-with-retry 的那一格，原项目 MUL-3551 验收③）；用户可配置的调度必须配独立熔断器（24h 扫描 / 7 天回看 / ≥50 次 / ≥90% 失败 → 自动暂停 + 通知创建者，原项目 MUL-1336：1475/1476 次失败烧了 7 天没人管）。

## 八、前端设计

### 8.1 workspace 结构与域约定

```
web/packages/core/issues/
├── api.ts            # 本域端点的 fetch 封装（薄）
├── queries.ts        # React Query options + queryKey 工厂（issueKeys.list(wsId) 等）
├── mutations.ts      # 写操作（乐观更新在此）
├── ws-updaters.ts    # M1：WS 事件 → Query 缓存补丁
└── store.ts          # Zustand：纯视图状态，按实体 id 键控
```

域目录平铺在 core 包根（不设 src/），每域同一套文件名——这是原项目 core 包的形态，约定比层级重要。`packages/ui` 禁止 import core；core 禁 react-dom/localStorage（不依赖 DOM 的测试与 SSR 自由）。

### 8.2 API 边界

`parseWithFallback` 从原项目 packages/core/api/schema.ts **逐字照抄**（55 行）：schema 校验失败永不抛异常，记带 endpoint 标识的警告、返回 fallback；返回类型锚定 fallback 的 T 而非 z.infer；schema 故意宽松（枚举写 z.string()）——将来客户端比服务器旧时不会白屏。这条第一天就带上。

### 8.3 状态纪律

React Query 管一切服务端状态；Zustand 只放视图状态且**按实体 id 键控**（如 `resolvedExpand: Record<issueId, Set<commentId>>`），切实体自动归零，不写 reset effect。WS/SSE 事件只打 React Query 缓存，绝不把 payload 镜像进 store。

M1/M2 落地 cache-coordinator（缓存传播规则单点）：所有写入路径（mutation 乐观更新、SSE/WS 事件）收敛到同一个 `applyIssueChange`；规则四条——手术 patch 优先、绝不 refetch 可见列表（拖拽闪烁的根源）；不再匹配过滤器的卡片手术移除；判不准成员关系就标 stale；绝不硬插入（sort+filter 下的正确槽位是服务器知识）。失效时机契约：mutation 延迟到 onSettled，WS/SSE 立即。

### 8.4 M0 视图清单（砍到骨头）

IssueListPage（列表 + 状态过滤）、IssueDetailPage（标题/描述/评论流 + SSE transcript 实时区 + usage 尾注）。不做看板、不做拖拽、不做虚拟化。M0 的 transcript 用 SSE 收流式消息直接 append。

## 九、测试策略

### 9.1 分层

单元（无 DB，mock mapper/backend）→ 集成（Testcontainers PostgreSQL，@SpringBootTest）→ 竞态（真 PG + 多线程，见下）→ 端到端（FakeBackend 全链路）。Testcontainers 每个 test class 一个 PG 实例，migration 由 Flyway 自动跑。

### 9.2 竞态测试手法（原项目 15+ race 测试的移植法）

Go 有 `-race`，Java 没有——竞态属性全靠"真 PG + 并发线程 + DB 终态断言"证明。两个核心手法照抄：

**手法一：pg_sleep 触发器强制打开竞态窗口。** 测试内 DDL 在 agent_task_queue 上装 BEFORE UPDATE 触发器，`OLD.status='queued' AND NEW.status='dispatched'` 时 `PERFORM pg_sleep(0.2)`。两个认领请求必然在窗口内重叠，测的就是窗口期的正确性：

```java
@Test
void concurrentClaimsRespectCapacity() {
    // fixture：agent max_concurrent_tasks=1，两条 queued 任务，装 pg_sleep 触发器
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    var futures = IntStream.range(0, 2).mapToObj(i -> pool.submit(() -> {
        start.await();  // CountDownLatch 对齐起跑（原项目 close(start) 手势）
        return service.claim(agentId, runner("r" + i));
    })).toList();
    start.countDown();
    // 断言：恰好 1 个非 null；DB 中 status IN ('dispatched','running') 恰好 1 行
}
```

**手法二：N 路单赢家。** 8 个线程同 plan_time 抢 scheduler 租约（或同任务抢认领），断言恰好 1 个 Won、其余 Conflicted、DB 恰好 1 行且归属 winner——结果不全信内存返回值，以 DB 查询为证。

### 9.3 必须移植的竞态场景清单（= M1 验收标准）

对照原仓库：并发容量受 respect（task_claim_race_test）、终态双写幂等（complete/fail 两路并发，各恰好一次生效）、8 路单赢家（scheduler concurrent_claim）、stale-steal 后旧持有者终态被忽略（lease_token 守卫）、评论合并竞态矩阵（queued 赢家折叠败者 / dispatched 赢家覆盖败者 / 不同 head 不合并 / 归因重盖章——comment_duplicate_enqueue_race_test 的 10 个场景）、取消与完成竞争（取消到达时进程已退出，终态以先落库者为准）。

### 9.4 前端测试

Vitest + Testing Library：parseWithFallback 行为、cache-coordinator 规则表（每条规则一个用例）、组件冒烟。M0 不追求覆盖率，规则表的用例不许省。

## 十、运行与部署形态

开发环境 docker compose：`postgres:17` + server（`mvn spring-boot:run -pl server`）+ daemon（M0 内嵌于 server，M1 独立 `mvn spring-boot:run -pl daemon`）+ web（`pnpm dev`）。self-host 目标形态同 compose 三容器。

daemon 分发：M0/M1 阶段 fat jar + 启动脚本（目标机需 JDK 21）；需要分发给外部用户时再评估 jpackage（捆绑 JRE 的自包含安装包）。GraalVM native-image 列为备选不承诺——反射/动态代理面需要逐一验证，不值得为体积提前还债。

## 十一、Java 栈特有风险

1. **虚拟线程 pinning**：JDK 21 里 synchronized 块内的 IO（典型如 BufferedInputStream.read）会 pin 住 carrier 线程。daemon 子进程的 stdout/stderr 泵必须用固定平台线程池，不要放虚拟线程；HTTP/DB/一般任务编排用虚拟线程无碍。JDK 24（JEP 491）后才可全面虚拟线程化。
2. **Windows 进程树**：ProcessHandle.descendants() 覆盖不全的场景靠 taskkill /T 兜底；原项目 Windows 支持的坑集中在进程组、路径、stdin 保持，M2 起 Windows CI 必上。
3. **Flyway + CONCURRENTLY**：忘记 executeInTransaction=false 会直接迁移失败，且 CONCURRENTLY 失败会留 INVALID 索引——按"单语句单文件"纪律写，失败重跑同版本号。
4. **MyBatis 类型映射**：UUID/JSONB/timestamptz 的 TypeHandler 第一天集中建好；散装 handler 是后期最烦的 drift 源。
5. **Go→Java 语义漂移**：最大隐性成本。对策只有一条——以竞态测试清单锚定语义（§9.3 即验收标准），每移植一块先写它的测试。
6. **JVM 常驻开销**：数百 MB 内存 + 秒级启动，对比 Go 单二进制确实差，但对本系统规模（单机、任务量级几十并发）完全无虞。不要为省这点内存做架构妥协。

## 十二、与里程碑文档的任务对照（M0 落点）

| 里程碑文档任务 | 新栈落点 |
|---|---|
| 1. schema + 迁移 runner | Flyway `V1__init.sql`（六表 + agent_task_queue + agent_runtime + partial unique index） |
| 2. 单体 server + CRUD + SSE | multica-server（WebMvc + MyBatis + SseEmitter），§4.2 M0 API |
| 3. 队列 worker 轮询 + 状态机 | multica-daemon 内嵌启动，协议走 localhost HTTP，§3.4 M0 版认领 SQL |
| 4. claude backend stream-json | daemon 模块 `agent/ClaudeBackend`，§6.2 |
| 5. 结果回写 + usage 落库 | daemon 结算路径（usage 先报 → complete/fail 幂等），§5.3 |
| 6. 联调 + 最简 web 页 | web/apps（Vite React，列表 + 详情 + SSE transcript），§8.4 |

M0 周计划（2-3 周）与验收判据（建 issue 后 60 秒内看到 agent 执行流）不变。任务 1-3 第一周、任务 4-5 第二周、任务 6 与边界打磨第三周。

---

参照仓库：/home/alen/multica-reference（所有 server/... pkg/... 路径相对此目录）。
姊妹文档：《Multica复刻-任务拆解与里程碑方案.md》（里程碑、工作配比、M1/M2 参照文件速查、风险清单——本文不重复其内容）。
