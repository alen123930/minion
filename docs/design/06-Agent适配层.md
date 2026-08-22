> 详细设计 · 六、Agent 适配层 · [返回索引](README.md)

# 六、Agent 适配层

## 6.1 接口

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

## 6.2 claude 适配器（M0 唯一实现）

一次性 CLI 模式：`claude -p --output-format stream-json --verbose --permission-mode bypassPermissions`，prompt 走 stdin，stdout 按行读、Jackson 逐行解析。事件类型：assistant / user / system（取 sessionID）/ result（取 usage 与 outcome）/ log。进程退出即会话结束。

四个必须照抄的决策（源自里程碑文档任务 4，出处注释写进代码）：

1. **异步任务禁令**：事件流里观察到后台任务启动（sawAsyncLaunch）直接判任务失败——后台任务逃逸生命周期控制。
2. **进程组终止协议**（§5.5）。
3. **行长度上限 32MiB 定义在一处**：`StreamScanner` 类全局唯一定义（原项目 GH #4520：上限曾被各适配器复制粘贴后漂移，修复只到达其中一个）。
4. **成本请求级记录**：从 result 事件的 usage 原样取，禁止 token 数乘费率折算（阶梯计费无法从聚合数复现）。

watchdog 三独立生命期用三个 ScheduledFuture：total 定时到点杀树；inactivity 每收到一条事件重排；firstOutput 收到第一条事件后取消。三个问题独立配置、独立触发，不许合并成一个"超时"。

## 6.3 hermes 适配器（M2，ACP 家族）

实现清单（语义锚点 hermes.go 2914 行，此处只列移植要点）：

- ACP JSON-RPC over `hermes acp` stdio：initialize（声明支持的 MCP 传输，把 runtime 不支持的条目滤掉再 session/new；resume 请求也带 mcpServers，否则续会话丢工具）→ session/new|resume → session/set_model（仅当指定且与当前不同）→ session/prompt → session/update 通知流至 stopReason → 关 stdin；stdout 读线程 + stderr 拷贝线程先确定性 join（2s 宽限再杀）再判定结果。
- **stderr 嗅探器**：ACP 协议缺陷是底层 LLM 4xx/5xx 时 session/prompt 仍报 end_turn，真实原因只在 stderr。嗅探规则：区分 INFO/DEBUG 回显（回显里可能包含"HTTP 429"字样，不得误报）、跨行 JSON 按花括号深度拼接、长度上限只作用于存储消息、绝不作为归类前提。嗅出的 provider 错误升级为任务失败。
- **streamingCurrentTurn 闸门**：session/resume 会重放整个历史 transcript，闸门在 prompt 发出前关闭，历史事件全部丢弃，否则上一轮答案污染本轮输出。
- **resume 三态**：返回的 sessionID 与请求不一致 → 注入 continuity notice（措辞在 daemon 侧，因为只有调用方知道丢了什么）；session-not-found → ResumeRejected，daemon 换新会话重试；set_model 失败必须失败任务（用户在 UI 选了模型，静默 fallback 等于欺骗），但"已在请求模型上"时跳过（重选当前模型触发 provider 误路由，MUL-5029）。
- **argv 纪律**：`acp` 子命令对用户 custom_args 封锁；值旗标表镜像 Hermes 自己的 argv 扫描，保证 `-p research` 两侧解析一致。

## 6.4 假 agent

M0 联调用 `FakeBackend`（contracts 提供接口，daemon 里做一个 echo 实现：分几条事件、延时、可注入失败与超时）。所有默认测试禁止执行真实 agent CLI；真 CLI 冒烟测试用环境变量门控（`MULTICA_RUN_REAL_AGENT_SMOKE=1`），对应原项目 agentintegration build tag 的隔离纪律。
