# legion 复刻：任务拆解与里程碑方案

基于参照原项目开源仓库（Go）的代码结构、changelog 史实与事故注释的完整分析。
分析仓库位于 /home/alen/legion-reference（持久位置，含 changelog 提取件）。

---

## 一、事实基础与分析方法

开源仓库不是完整开发史：50 个提交全部压在 2026-08-17/18 两天，是私有主仓库的同步快照，无 merge 记录。每个 commit 对应一个 squash 后的 PR（编号已到 #7150），Jira 工单号已用到 MUL-6355。真实开发史通过两处还原：

1. web 端 changelog 数据（apps/web/features/landing/i18n/en.ts）——107 个版本逐条记录，从 v0.1.0（2026-03-22 "Foundation"）到 v0.4.29（2026-08-18），正好 5 个月，平均 1.4 天一版。
2. 代码注释中的 2422 处 MUL 工单引用（303 个不同 ID），多数带复现条件和被否决的替代方案，等于免费的踩坑档案。

代码规模（非 vendor）：

- Go 约 55 万行：internal/handler 161K、internal/daemon 93K、internal/integrations 71K、pkg/agent 66K、pkg/db 38K
- TypeScript 约 39 万行：packages/views 244K（最大单包）、packages/core 64K、mobile 30K、web 24K、desktop 21K
- 749 个 Go 测试文件，其中 15+ 个专门的并发/race 测试
- 774 个 SQL 迁移文件（server/migrations/，001-387 各带 up/down）
- 核心贡献者 2-3 人 + 长尾社区，5 个月约 15 人月

changelog 每月构成（新功能/改进/修复行数统计）：4 月修复占 40%，5 月 39%，6 月 43%，7 月 48%，8 月 47%。修复占比逐月上升而非下降——系统复杂度增长快于功能增长，这是排期时必须预留加固缓冲的硬证据。

## 二、原项目开发阶段还原

第 1 周（3/22-3/31，v0.1.0→v0.1.3）：Foundation → Core Platform → Collaboration → Agent Intelligence。agent 在第 9 天就能干活。垂直切片极早——不是先完善 issue 系统，而是立刻打通"issue → agent 执行 → 回写"全链路。

4 月上旬（v0.1.13→v0.1.33）：协作功能铺开——mentions/权限、编辑器、OAuth、sub-issues、projects、monorepo、self-hosting、ACP、Windows 支持、Gemini CLI。顺序：先 web 协作面，再逐个接 runtime。

4 月中（v0.2.0）：Desktop App + Autopilot + 邀请制。

4-5 月（v0.2.x）：runtime 扩张潮（Kiro/Kimi/自定义 env）、Chat V2、local skills、orphan-task recovery、daemon identity、MCP、GitHub 集成。

5 月中（v0.3.0）：Squads（多 agent 协作）。

5-7 月（v0.3.x）：IM 集成落地顺序 Lark（6/4）→ Feishu 群聊（6/17）→ Slack（6/25）→ DingTalk（7/20）→ WeCom（8/6）。iOS、Helm 同期。

7-8 月（v0.4.x）：autopilot scheduler 整体重写（v0.4.4，注意是推倒重做）、custom fields、Analytics、持续接入新 runtime（Qwen/DeepSeek/Oh-My-Pi/MiniMax）。plugin system 到 8/18 仍在 rebuild（1/4），未收敛。

## 三、复刻范围界定

做：任务调度内核（daemon + agent backend + db 队列）、web 协作面、2-3 个 runtime、1 个 IM 渠道、GitHub 集成。

明确不做（原项目均在第 2-4 个月才做，且不构成产品内核）：desktop app、mobile/iOS、多语言 i18n、Helm 多副本、plugin system（原项目未收敛）、Analytics/计费图表、entitlement 体系。

## 四、里程碑总览

### M0：垂直切片（对照原项目第 1 周 / 建议耗时 2-3 周）

目标：一条 issue 从创建到 agent 回复的全链路，单机单体跑通。允许全程硬编码、单用户、无认证。

任务：
1. 数据库 schema 骨架 + 迁移机制
2. 单体 HTTP server（原项目就是单体 + 少量二进制入口，不要拆微服务）
3. 最简任务队列：一张表 + 轮询 worker，不做 SKIP LOCKED 不做租约
4. 第一个 backend：claude -p stream-json 一次性模式（两个协议家族里简单的那个，claude.go 1172 行 vs hermes.go 2914 行）
5. 结果写回 comment + usage 落库

验收判据：web 上建 issue，60 秒内看到 agent 的执行流和结论。

核心纪律：原项目第 9 天打通垂直切片。拖到第 9 周才打通的团队，后面全部失调。

### M1：任务系统可靠化（对照 v0.1.x 末期 / 3-4 周）

目标：daemon 崩了、agent 卡了、网络断了，任务不丢不重。

任务：
1. daemon 独立进程 + 认领循环：SKIP LOCKED 批量认领、slot-before-claim、负缓存版本号
2. 租约与心跳：prepare 阶段租约续期、不活动看门狗（区分总时长/单轮静默/首轮零产出三个独立超时）
3. 结算路径：usage 先报、fail-closed、GC 元数据落盘
4. coalesce 与去重：唯一索引 + 归因 fail-closed（MUL-4302 规范，被引用 133 次）
5. 并发测试先行：原仓库 15+ 个 race 测试就是验收标准清单

验收判据：kill -9 daemon 和 agent 进程，任务自动恢复；两个 daemon 抢同一批任务无重复执行；race 测试全绿。

### M2：多 runtime 抽象（对照 v0.2.1 / 3-4 周，可与 M3 并行）

目标：加一个新 agent CLI 是小活而不是项目。

任务：
1. Backend 接口 + Session 抽象 + ExecOptions 协商面（哪些字段谁消费谁忽略）
2. 第二协议家族：ACP JSON-RPC 长连接（initialize → session/new → prompt → update 流 → EOF 退出）
3. launch.go 的 Command 抽象（prefix-first 参数序）+ 统一 stream scanner（32MiB 行上限一处定义）
4. 三个硬化件：stderr 错误嗅探器、resume 三层失败语义、进程组终止协议
5. 用两个 runtime 验证抽象（一个 stream-json 型 + 一个 ACP 型），再接第三个验证边际成本递减

验收判据：新接一个 runtime ≤ 3 天；resume 后上下文丢失时 agent 收到 continuity notice；provider 报 429 时任务正确失败而非空输出成功。

### M3：协作表面（对照 4 月功能潮 / 4-6 周，M1 中途进场）

目标：真人能日用。

任务：
1. web 前端：issue 看板/详情/评论流/transcript 实时视图（原项目 views 包 244K 行是全项目最大包，工作量别低估）
2. chat 对话式调用（原项目 Chat V2 在 v0.2.16 才稳定，第一版 chat 一定会重做，第一版刻意做薄）
3. daemonws：server→daemon 的 WebSocket hub + 心跳（原项目 v0.2.20 才补 heartbeat，直接纳入初版）
4. 认证与多成员、mentions 与权限（v0.1.14）

验收判据：两个真人 + 两个 agent 在同一 workspace 协作一天，无需碰数据库。

### M4：生态集成（对照 v0.3.x / 4 周+，按需裁剪）

任务（按原项目落地顺序排优先级）：
1. GitHub/GitLab：PR 触发、worktree 准备（execenv）、结果回贴
2. 一个 IM 渠道（用户在哪就先接哪个；原项目 IM 顺序说明渠道间高度同构，做好第一个后面都是快活）
3. skills 系统（原项目从 local skills 到 skill imports 渐进了两个月）

### M5（可选/延后）：Squads 多 agent、autopilot 定时任务

警示：原项目 autopilot scheduler 在 v0.4.4 整个重写过。计划性任务 + 触发器系统是这类系统最难设计对的部分，放到有真实用户反馈之后再做，第一版按"cron + webhook"最简形态。

同样延后：Analytics、usage 计费图表、entitlement、Helm、plugin system。

## 五、M0 实施计划（文件路径级）

### 5.1 schema DDL 草案

以原项目 server/migrations/001_init.up.sql 为蓝本裁剪（该文件含 user/workspace/member/agent/issue/issue_label 等表），M0 只取六张核心表：

```sql
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE "user" (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    email TEXT UNIQUE NOT NULL,
    avatar_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workspace (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    slug TEXT UNIQUE NOT NULL,
    settings JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE member (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('owner', 'admin', 'member')),
    UNIQUE(workspace_id, user_id)
);

CREATE TABLE agent (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    runtime_mode TEXT NOT NULL CHECK (runtime_mode IN ('local', 'cloud')),
    runtime_config JSONB NOT NULL DEFAULT '{}',
    status TEXT NOT NULL DEFAULT 'offline',
    max_concurrent_tasks INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE issue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    description TEXT,
    status TEXT NOT NULL DEFAULT 'backlog',
    priority TEXT NOT NULL DEFAULT 'none',
    assignee_type TEXT CHECK (assignee_type IN ('member', 'agent')),
    assignee_id UUID,
    creator_type TEXT NOT NULL CHECK (creator_type IN ('member', 'agent')),
    creator_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE comment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    issue_id UUID NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    author_type TEXT NOT NULL CHECK (author_type IN ('member', 'agent')),
    author_id UUID NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- M0 核心：任务队列表。M1 再演进为租约/认领字段
CREATE TABLE agent_task (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL REFERENCES agent(id),
    issue_id UUID NOT NULL REFERENCES issue(id),
    status TEXT NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'running', 'done', 'failed', 'cancelled')),
    prompt TEXT NOT NULL,
    output TEXT,
    session_id TEXT,
    input_tokens INT DEFAULT 0,
    output_tokens INT DEFAULT 0,
    cost_usd_ticks BIGINT DEFAULT 0,  -- 1e-10 美元刻度，成本必须请求级记录
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

CREATE INDEX idx_agent_task_status ON agent_task(status) WHERE status IN ('queued', 'running');
```

迁移机制照抄原项目形态：server/migrations/ 下 NNN_name.up.sql + NNN_name.down.sql 成对，runner 参照 server/cmd/migrate/main.go 与 server/internal/migrations/migrations.go。两条原项目教训直接写进 runner：(1) 回滚必须 fail-closed，不许删数据碰运气（MUL-6305：341 号迁移回滚差点删掉 actor 属性数据）；(2) 并发建索引要带 retry cleanup（MUL-6288）。

### 5.2 任务清单与参照文件

任务 1：schema + 迁移 runner（2 天）
参照：server/migrations/001_init.up.sql、004_agent_runtime_loop.up.sql（看带数据回填的迁移怎么写）、server/cmd/migrate/main.go、server/internal/migrations/migrations.go

任务 2：单体 server + issue/comment CRUD + SSE（3 天）
参照：server/cmd/server/（入口 21K 行里只看启动装配部分）、server/internal/handler/（挑 comment.go 与 admission.go 读入队路径）；CRUD 模式参照 server/pkg/db/queries/（sqlc 生成的查询）与 generated/

任务 3：队列 worker 轮询 + 状态机（3 天）
参照：server/internal/daemon/daemon.go、reconcile.go（对账循环的形态）；M0 用 SELECT ... FOR UPDATE SKIP LOCKED 的最简版，M1 再补租约——但表结构现在就留好字段

任务 4：claude backend stream-json（4 天）
参照：server/pkg/agent/agent.go（Backend 接口与 ExecOptions 协商面注释，全文精读）、claude.go（一次性 CLI 模式全流程）、stream_json_result.go、launch.go（Command 抽象与 prefix-first 参数序）、stream_scanner.go（36 行，统一行长度上限的治理故事）
四个必须原样抄走的决策：
- 进程组终止协议（GH #5918）：取消时 SIGTERM 整组，升级 SIGKILL 的判定看"整组是否退出"而非 leader 是否退出——cmd.Wait() 返回只代表 leader 死了
- 异步任务禁令：事件流里观察到后台任务启动直接判失败
- 行长度上限 32MiB 统一在一处定义（GH #4520：上限曾在各 backend 复制粘贴漂移）
- 成本用请求级记录而非"token 数乘费率"（xAI 阶梯计费复现不了）

任务 5：结果回写 + usage 落库（2 天）
参照：server/internal/handler/daemon.go（结算上报路径）、claude.go 中 result 事件的 usage 解析

任务 6：联调 + 最简 web 页（3 天）
一个页面：issue 列表 + 详情 + 评论流 + SSE 实时 transcript。参照 apps/web 的最小可用形态即可，不碰 packages/views 的完整实现

### 5.3 M0 周计划

第 1 周：任务 1-3（schema、server、队列雏形）。周五演示：手动往 agent_task 插一行，worker 领走、调用 echo 假 agent、结果落库。
第 2 周：任务 4-5（claude backend、回写）。周五演示：真 issue → 真 agent → 网页看到流式输出。
第 3 周：任务 6 + 边界打磨（超时、取消、进程组清理）。周五演示：验收判据达成。

## 六、M1/M2 参照文件速查

M1 认领与租约：server/internal/daemon/client.go、health.go、wakeup.go；并发测试清单（验收标准直接抄）：client_batch_claim_test.go、daemon_batch_claim_test.go、daemon_batch_claim_finalize_test.go、daemon_claim_cancelled_session_test.go、daemon_claim_channel_type_test.go、daemon_claim_continuity_gap_test.go、channel_lease_config_test.go、comment_duplicate_enqueue_race_test.go、workdir_race_test.go、agent_concurrency_test.go、runtime_sweeper_race_test.go、migrate_concurrent_test.go
（测试文件在 server/internal/daemon/ 与 server/pkg/agent/ 下，find server -name "*_test.go" | grep -E "race|claim|lease" 可列全）

M1 看门狗与超时三分法：server/pkg/agent/agent.go 的 ExecOptions 注释（MUL-3064：Timeout=0 表示无硬性墙钟、活性交给不活动看门狗；总时长/单轮静默/首轮零产出是三个独立问题独立设置）

M2 ACP 协议：server/pkg/agent/hermes.go（2914 行，M2 最大的单块工作）。四个必读设计：
- stderr 嗅探器：ACP 的 session/prompt 在底层 LLM 4xx/5xx 时仍报 end_turn，真实原因只在 stderr；嗅探器区分 INFO/DEBUG 回显与真 ERROR，长度上限只作用于存储消息、绝不作为归类前提（GH #5862）；StderrPipe + 显式拷贝 goroutine + join channel 的并发教训
- streamingCurrentTurn 闸门：session/resume 会重放历史 transcript，闸门在 prompt 发出前关闭，否则上一轮答案污染本轮输出
- resume 三层失败语义：sessionID 不一致→注入 continuity notice（MUL-5722）；session-not-found→ResumeRejected 让 daemon 换新会话重试；set_model 失败必须失败任务，但"已在请求模型上"时跳过（MUL-5029：重选当前模型会触发 provider 误路由）
- 能力协商：initialize 响应声明支持的 MCP 传输，把 runtime 不支持的条目滤掉再发 session/new

M2 验收测试参照：hermes_resume_test.go、hermes_integration_test.go、claude_cancel_unix_test.go、claude_deadlock_test.go、launch_test.go、agent_test_executable_test.go（TestOnlyLaunchGoSpawnsRuntimeProcesses 强制所有 backend 走统一 launch 路径）

M3 daemonws：server/internal/daemonws/（hub + 心跳）；chat 第一版做薄的证据：v0.2.16 才有 Chat V2
M4 execenv：server/internal/daemon/execenv/（worktree/GC/环境准备）；repocache/ 同目录

## 七、工作量与节奏

配比：2 后端（M0→M2 串行做内核）+ 1 前端（M1 中途进场做 M3）+ 0.5 DevOps（docker compose self-host 起步）。

总量：原项目 5 个月 3 人核心 ≈ 15 人月做出全量。复刻砍掉 desktop/mobile/IM 全家桶/autopilot 重做后，M0-M4 约 6-8 人月。另省掉一笔最大隐性成本——设计试错：2422 处事故注释等于 6355 个工单换来的教训免费获得，复刻时把这些约束直接抄进自己的代码注释。

节奏照抄两条：
1. 1-2 天一版持续发布（107 版/5 个月，小步快跑是节奏本身不是结果）
2. 给修复预算——原项目修复占比稳定在 40-48%，每个功能里程碑后面跟 30-40% 加固缓冲，不排满 feature

## 八、风险清单（从事故考古提取）

1. 最大风险不是技术，是顺序：忍不住先完善 issue 系统、先做好看前端、先把三个 runtime 都接上。证据说：先让一条 issue 在第 9 天得到 agent 回答，其他一切从骨架上长出来。
2. 第二大风险是把 M1 的可靠性推迟：原项目 4 月就做了 orphan-task recovery、daemon identity，40% 修复占比里大半是这类问题。调度内核的并发正确性欠账利滚利。
3. autopilot/触发器类功能第一版一定做错（原项目整个重写过一次），刻意最小化并推迟。
4. chat 第一版一定重做，刻意做薄。
5. 前端工作量容易低估：原项目 views 包 244K 行是全项目最大单包，M3 的 4-6 周里前端只有一个人时考虑再砍范围（比如先不做看板只做列表）。
6. Windows 支持的坑（进程组、路径、stdin 保持）原项目踩了一整月（v0.1.28→0.2.11），若目标用户含 Windows，M2 阶段就引入 Windows CI。

---

分析产物位置：
- 分析仓库（持久）：/home/alen/legion-reference
- changelog 全文提取件：/home/alen/legion-reference/changelog_full.txt（107 版本完整条目）
- 文档中所有 server/... pkg/... 相对路径均相对 /home/alen/legion-reference
- 原仓库 changelog 源：apps/web/features/landing/i18n/en.ts
