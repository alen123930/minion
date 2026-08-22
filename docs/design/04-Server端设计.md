> 详细设计 · 四、Server 端设计 · [返回索引](README.md)

# 四、Server 端设计

## 4.1 分层

controller（REST 薄层）→ service（业务与事务边界）→ mapper（MyBatis）。DTO 全部在 contracts 模块，controller 不出现实体行对象。事件广播用 Spring `ApplicationEventPublisher`（M0 进程内），M1 realtime WS hub 订阅同一事件流——事件路径不因传输层变化而重写。

## 4.2 API 面

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

## 4.3 入队路径

单一谓词 `WillEnqueueRun(issue, change)` 决定一次写操作是否入队——create/assignee 变更/状态迁移/评论触发全部走它，预览接口也走它（原项目 MUL-3375 的教训：谓词散落各处后 drift）。M0 只实现"assign 给 agent 且状态非 backlog"一条规则；M1 加自环抑制（agent 自己的状态变更不再触发自己的任务）与 backlog 停车场语义。

入队后顺序契约：先广播 `task:queued` 事件、再唤醒 daemon（M0 无 WS，跳过唤醒；M1 起 bump 空认领缓存版本号在 WS 唤醒之前——顺序反了被唤醒的 claim 会读到未失效的"空"判定，白唤醒）。

M1 归因门（MUL-4302 精神，不必全量）：入队前解析 originator/accountable，accountable 不许为 NULL；解析失败按 fail-closed 拒绝入队而不是静默落库。M0 可以全部记为单用户。

## 4.4 空认领缓存（M1）

Caffeine 缓存"runtime 无任务"的否定判定（3 分钟 TTL），配 per-runtime 单调版本号：入队侧先 `version.incr(runtimeId)` 再唤醒；缓存值携带判定时的版本号，读侧版本不匹配视为 miss。只缓存否定结果，命中肯定结果的永远打 DB。最坏情况是并发入队时多一次 SELECT，不会卡任务——这条注释原文抄进代码。

## 4.5 Reason code

```java
public enum DispatchReason {
    QUEUED, DEFERRED, RUNTIME_OFFLINE, RUNTIME_UNUSABLE,
    INVOCATION_NOT_ALLOWED, CAPACITY_EXCEEDED, COALESCED, ATTRIBUTION_BLOCKED
}
```

纪律照抄：在阻塞分支处一次决定、逐层透传原值、禁止从错误字符串解析、不泄露私有 agent 的存在性（对外只见 runtime_offline，不见"某 agent 不存在"）。

## 4.6 实时通道

M0：SseEmitter。server 持有 `Map<issueId, List<SseEmitter>>`，daemon 的 messages/progress 上报映射成 SSE 事件转发；每 30s 发一条注释帧保活（代理会掐静默连接）。连接断开从注册表移除。

M1：realtime WebSocket hub（Web 前端用）+ daemonws hub（daemon 用）分开两个端点。daemonws 移植原项目的三条不变量：帧只是 best-effort 提示、HTTP 轮询是正确性路径；投递永不阻塞（每 session 有界队列 16，满即驱逐断连）；有副作用不可回退的操作（认领事务）不做中途取消。三级索引 `ConcurrentHashMap<runtimeId/workspaceId/userId, Set<Session>>` 结构平移。

## 4.7 认证演进

M0 免。M1 双轨：daemon 用 PAT（`X-Daemon-Token` 头，注册时签发）；web 用 JWT（登录签发，内存持有，M2 升级 HttpOnly cookie + refresh）。workspace 隔离从 M1 起全部查询带 workspace_id 条件 + `X-Workspace-ID` 头选择。

## 4.8 MyBatis 配置基线

统一 TypeHandler 集中注册（UUID↔uuid、Jsonb↔Jackson JsonNode/record、OffsetDateTime↔timestamptz），放在 contracts 或 server 的 config 包一次性注册，禁止各 mapper 各写各的。mapUnderscoreToCamelCase 开启。
