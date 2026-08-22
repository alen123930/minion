-- M0-3 数据库骨架（NIMI-9）：六张核心表 + agent_task_queue + agent_runtime。
-- DDL 出处：docs/legion复刻-任务拆解与里程碑方案.md §5.1（六表）与 docs/design/03-数据库设计.md §3.3（队列/运行时）。
-- 与里程碑文档冲突处以设计文档为准（AGENTS.md 权威顺序）：里程碑草案中的 agent_task（终态 done）
-- 被 §3.2 词汇修正废止，由 agent_task_queue（M1 完整形态）取代——本迁移不建 agent_task。

-- gen_random_uuid() 依赖 pgcrypto（参照仓库 001_init 同款起手）
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ---------------------------------------------------------------------------
-- 六张核心表：FK CASCADE 是 M0 刻意简化（与原项目"永不用 FK、关系在应用层维护"相反）。
-- 不要"修复"它，也不要把此简化扩展到六表之外（AGENTS.md 数据库硬规则）。
-- ---------------------------------------------------------------------------

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

-- ---------------------------------------------------------------------------
-- agent_task_queue：按设计 §3.3 的 M1 完整形态建表（M0 只留字段，不实现 M1 认领/租约逻辑）。
-- 词汇铁律：终态是 completed（不是 done），dispatched 是中间态——M0 就用最终词汇表，
-- 避免 M1 演进时改枚举值（AGENTS.md 协议与任务生命周期）。
-- ---------------------------------------------------------------------------
CREATE TABLE agent_task_queue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- workspace FK CASCADE 仅限 M0 沿用六表简化；M1 迁移 queue_fence 会移除它，
    -- 改为入队/认领事务内 SELECT ... FOR UPDATE 的应用层 fence（设计 §3.3，MUL-5999 / migration 284）
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    issue_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    runtime_id UUID,                       -- M1 起 NOT NULL；M0 内嵌 worker 可为 NULL
    status TEXT NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued','dispatched','running','waiting_local_directory',
                          'completed','failed','cancelled')),
    priority INT NOT NULL DEFAULT 0,
    context JSONB NOT NULL DEFAULT '{}',   -- prompt、归因快照（M1）、MCP overlay 等
    trigger_comment_id UUID,
    coalesced_comment_ids JSONB NOT NULL DEFAULT '[]',  -- JSONB 而非 UUID[]，MyBatis TypeHandler 统一
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

-- 认领扫描路径（M1 认领 SQL 的 ORDER BY 与此一致，设计 §3.4）
CREATE INDEX idx_agent_task_queue_claim
    ON agent_task_queue (priority DESC, created_at, id)
    WHERE status = 'queued';

-- 第一天就建：同一 (issue, agent) 至多一个 pending（queued/dispatched）。
-- 原项目教训：最初是 per-issue 全局唯一（不同 agent 互相挡），migration 037 改成
-- per-(issue,agent)，让"一个 issue 多 agent 并行"成为一等公民——此处直接落终态形态。
CREATE UNIQUE INDEX idx_one_pending_task_per_issue_agent
    ON agent_task_queue (issue_id, agent_id)
    WHERE status IN ('queued','dispatched');

-- agent_runtime：daemon 侧心跳注册表（设计 §3.3）；关系在应用层，不加 FK
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
