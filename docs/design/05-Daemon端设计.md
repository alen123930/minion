> 详细设计 · 五、Daemon 端设计 · [返回索引](README.md)

# 五、Daemon 端设计

## 5.1 线程模型

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

## 5.2 任务生命周期（M1 形态）

claim → 领取 prepare lease（此后每 15s 续期，硬超时 5 分钟，与 agent 执行超时完全独立）→ execenv.Prepare（§5.4）→ POST start（只在环境落盘后）→ spawn agent CLI（§6）→ 流式 POST messages → 终态：先 POST usage（先于任何 early return，计费纪律），再 complete/fail → 释放槽 → 写 GC 元数据 → nudge poll。

取消监视每 5s 轮询任务状态：只有 terminal 状态或 404 才杀本地进程组；瞬态网络错误、5xx 永不取消。杀进程用整组终止协议（§6.3）。

## 5.3 结算纪律（从原项目逐条照抄）

usage 先报；fail-closed（只有显式 completed 算成功）；session_id 与 work_dir 在每条上报路径都携带（chat resume 依赖）；终态上报失败按计划重试（带退避）。

## 5.4 execenv 磁盘契约

目录形态：`{workspacesRoot}/{workspaceId}/{taskId 前 8 位}/`，内含 `workdir/`、`output/`、`logs/` 与三个点文件。契约的核心是**写入时机**：

- `.managed_env.json`（managed_by/workspace/issue/agent 四元组）——Prepare 时刻写。它是"同 issue 后续任务可复用此环境"的资格证明。必须在诞生时写而不是完成时写：后续任务可能在上一任务完成后的瞬间被认领，早于上一任务写 `.gc_meta.json`（原项目 MUL-4886 的竞态）。
- `.gc_meta.json`（kind: issue/chat、completed_at、local_directory 标记）——终态时写，GC 据此决策。
- `.legion_sidecar_manifest.json`——写进 workdir 的所有文件清单；Prepare 失败时按它回滚（回滚 defer 在第一次写之前武装，MUL-6132）。

M0 简化：只做 workdir + `.agent_context/issue_context.md`（任务简报）+ `.gc_meta.json` + 72h 孤儿 TTL 扫描。manifest 与 managed_env 从 M1 加。

GC 决策树 M1 版按原项目规则移植：活跃任务（内存引用计数）无条件跳过 → 读 meta 失败走 mtime 孤儿兜底 → 按 kind 问父记录（issue 终态+TTL、chat 404 即收）→ local_directory 降级只清 artifact。原则性纪律：可再生缓存（可重建的副本）和不可再生数据走不同回收路径；删前的每一步昂贵检查（dirSize 遍历）之后要复查一次"还活着吗"。

## 5.5 进程树终止（Java 实现）

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
