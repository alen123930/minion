# 原项目 Prompt 设计哲学（深读笔记）

> 原项目机制深读，2026-08-22。服务于复刻详细设计 §6（Agent 适配层）与 M0 任务 3/4 之间的 prompt 渲染部分。
> 所有路径相对 /home/alen/legion-reference。票据号（MUL-xxxx / #xxxx）是原仓库注释里自带的出处。

读的三个文件：

- `server/internal/daemon/prompt.go`（710 行）—— 逐 turn prompt 构建
- `server/internal/daemon/execenv/runtime_config.go`（405 行）—— brief 注入 workdir 的机制
- `server/internal/daemon/execenv/runtime_config_sections.go`（888 行）—— runtime brief 本体（CLAUDE.md / AGENTS.md 模板）

这块的注释密度是全仓库最高的，几乎每个设计决定背后有一张票据。

---

## 总纲

**prompt 是易失层（这一轮变了什么），brief 是缓存层（永远不变的规则），两者以"字节稳定 + 单发射点 + 事实内嵌"三条纪律耦合；每句幸存的指令要么是模型推导不出的平台事实，要么有事故证明不加不行。**

## 一、两层分工：prompt 极小，brief 极大

BuildPrompt 的第一句注释是总纲（prompt.go:125）："Keep this minimal — detailed instructions live in CLAUDE.md / AGENTS.md"。逐 turn prompt 只放"这轮变了什么"：触发评论原文、turn 模式标记、发起人、附件 id。全部持久指令（工作流、格式规则、命令清单）写进 workdir 的 CLAUDE.md（claude）/ AGENTS.md（codex、hermes 等 19 个 runtime）/ CODEBUDDY.md / QWEN.md，由 CLI 自己从 cwd 加载（runtime_config.go runtimeConfigPath）。

SystemPrompt 参数只为极少数不读磁盘文件的 provider 填充（daemon.go:5604 providerNeedsInlineSystemPrompt），注释专门警告：新 backend 不得假设它有值——"adding a new backend that only reads SystemPrompt will silently receive nothing"。

## 二、字节稳定性是硬通货（prompt cache 经济学）

Brief 必须 "byte-identical on every run"（prompt.go:51）。这不是洁癖，是钱：Claude Code 把 CLAUDE.md 载入 messages[0]，位于整个会话历史之前——brief 里任何一个逐 run 变化的字都会打掉全部历史的 prompt cache（MUL-5377）。所以逐 run 上下文（发起人是谁、会话连续性通知、connected apps、并行兄弟任务）全部移到逐 turn 消息，而且**追加不前置**——放在缓存前缀之后，变化只花本轮自己的 token（prompt.go:66 perTurnContextBlocks，注释明说这个搬家就是 MUL-5377）。

这套约束是 prompt 系统最核心的架构纪律，多处注释引用它来否决"顺手写进 brief"的方案。

## 三、每条规则只有一个发射点

重复即漂移，漂移即事故。三个证据：

1. autopilot prompt 明确不复述 issue-command 边界——"brief 是它唯一的发射点，第二份手维护的逐 turn 拷贝正是当年两个表面互相冲突的原因"（MUL-5696，prompt.go:668）。
2. 会话连续性通知曾经 daemon 和 backend 各发一份，同一段落一轮付两次钱。修法：backend 的副本从 daemon 的版本派生、prompt 已携带时精确抑制——"让重复在结构上不可能，而不是仅仅不太可能"（MUL-5722，prompt.go:32 backendResumeContinuityNotice）。
3. 平台显示名映射收敛到一个函数 channelDisplayName，"逐 turn prompt 和 brief 不能把同一个平台叫成两个名字"（prompt.go:629）。

## 四、模式路由：brief 静态，标记动态

Brief 字节稳定，所以它**说不了**这轮是什么触发的。解法：逐 turn prompt 发模式标记（prompt.go:61），brief 里的 workflow 按标记选分支：

- `Turn mode: Reply` —— 回应触发评论；状态弧只在本轮对该 agent 名下 issue 做实质工作时打开（MUL-6300）
- `Turn mode: Ownership` —— 指派/状态变更启动的运行；无条件拥有状态弧

标记从选择代码路径的**同一个分支**无条件发出，"两者永远不会对不上"。且不许以评论内容为条件——空评论会让这轮没有标记，agent 落回 Ownership 模式去动 issue 状态。

## 五、事实内嵌，读取有界

- 触发评论**原文**直接嵌进 prompt，"agent 不可能错过它，即使复用的 workdir 里有陈旧输出文件"（buildCommentPrompt）。回复指令（含 --parent UUID）每轮重发，防 resume 会话把上一轮的 parent 带过来。
- 合并进来的旧评论（MUL-4195）同样原文嵌入，各带自己的 thread 和作者标签；老服务器只给 id 时降级为逐 id 取回指引。
- 读历史永远两步有界："先 --roots-only --summary 扫描线程，再只展开要紧的 --thread <id> --tail 30"，绝不一次全量拉（MUL-5372）。MUL-5442 修过一个自相矛盾：平台在 prompt 里推荐的取回方式（--recent 30，它数的是线程不是评论，会拖回全部历史）恰好是 brief 里禁止的 bulk 拉法。
- 冷/暖路径各有专属提示：暖路径有新评论走 since 增量；resume 且无新评论不强制重读（trigger 已注入）；冷路径读触发线程而非平铺时间线（prompt.go:385-399）。

## 六、模型能推导的删掉，事故证明拦不住的点名工具形状

最反直觉的一条。MUL-5442 阶段2 刻意**删除**大量执行细则——"run-owned 工作枚举、工具承诺枚举、wait/collect 拆分规则、持久服务适用范围……前沿模型从平台事实自己能推导出来的都删了"，对应测试锚点同批退役。

但幸存下来的句子各有事故背书：光说"别阻塞等外部系统"拦不住 agent 盯 CI——`gh pr checks --watch` 长得就像合规的阻塞前台调用，"边界作为概念陈述 + 唯一的'怎么等'例子是个阻塞调用"等于教它违规（MUL-5223）。所以必须**点名禁掉 `--watch` / `watch` / `poll` 这几个形状**；"验收标准要求"的逃生门曾被仓库自己的合并门禁满足（"CI 过了才能合"），所以要写明分支保护不是 agent 的验收标准，并给出替代话术（本地测试输出 + PR 链接）。持久服务交接段落逐字锁定，"不经新一轮 review 决策不许改词"。

哲学：给平台事实 + 点名被证明危险的工具形状，其余交给模型判断。sections 文件里每个函数头部的注释就是这段删改史。

## 七、诚实原则：话术精确对应实际损失

SessionContinuityNotice 三个变体（runtime_config_sections.go，MUL-4424 / MUL-5722）。区分问题是"**对话还能不能被读回**"，不是"这是不是聊天"：

- issue：对话就是 issue 正文和评论，原封不动——宣布"上文丢了"是描述一个没发生的损失
- Slack：对话在 channel，原项目 CLI 的 `chat history/thread` 读得回
- web / Feishu / WeCom / DingTalk：原项目存了 transcript，读得回

三种情况真正丢的只有 agent 自己没落盘的工作记忆（试过什么、排除了什么、走到哪了），三个变体都说**只**丢这个，并叮嘱重新推导、别声称记录撑不起的连续性、别拿这个开场。只有无法读回的表面（防御性兜底，当前不存在此类表面）才要求向用户明说是新会话。"准确地说比大声地说重要"。

## 八、角色来自 wire 字段，不来自文本嗅探

taskIsSquadLeader（prompt.go:705）：leader 是**逐 task 角色**，同一 agent 这轮当 leader 下轮当 worker。以前靠嗅探 Instructions 里有没有 briefing 标题——任何 agent 的自定义指令碰巧含这个标题就被晋升为 leader、拿到 leader 规则。现在是 claim 响应的显式字段（is_leader_task / squad_id）+ capability gate（leader_role_resolved），注释详细论证了老服务器两个群体的不可判定性："capability gate 是承重的，不是仪式"（MUL-5811 待最低服务器版本后删掉 legacy 分支）。

配套的 no_action 规则同样典型：判定无需行动就调原项目 CLI 的 `squad activity no_action` 记录然后退出，"**不许**发评论——连'无需行动'这种评论都不许"，评论是冗余噪音。

## 九、防自毁细节（每条背后一次事故）

- quick-create：原项目 CLI 的 `issue create` 绝不因任何原因重试——issue 可能已建，重试就是重复（prompt.go:270）
- 富文本必须走 `--description-file`，文件必须在 cwd、绝不许 /tmp——"别的 run 可能留下陈旧文件，静默变成这个 issue 的描述"；内联传富文本会被 shell 改写/截断（MUL-2904）
- 匹配 assignee / project / parent 一律 UUID 优先于名字——抗重名、抗 workspace 自定义前缀；"不要假设 MUL- 这类前缀"
- 聊天附件列 id 而非 URL——签名 CDN 短 TTL，"等 agent 想完，markdown 里的 URL 已过期"，原项目 CLI 的 `attachment download <id>` 取时重签（prompt.go:582）
- brief 注入用 marker 块包裹，绝不覆盖用户已有文件：文件缺失→只写 marker 块；已存在→追加带字节边界的分隔符；已有 marker 块→原位替换。Cleanup 按 separator 字节边界还原用户原始字节。当年无条件 os.WriteFile 把用户仓库的 CLAUDE.md 截断过（MUL-2753）

---

## 复刻落点

M0 只需要 assignment 触发一种 prompt 形态：issue id + Ownership 模式标记 + `Start by running ... issue get` 指针；brief 极薄（header + 最简 workflow）。claude 适配器是一次性 CLI 模式，无跨任务 cache 复用。

第一天就值得照抄的纪律（成本为零，晚期补很贵）：

1. **两层分离**——逐 turn 变化的东西永远不进 brief，即使 M0 还没有 resume/缓存问题
2. **单发射点**——同一条规则不许在 prompt 和 brief 各写一份
3. **事实内嵌**——触发内容原文进 prompt，不靠 agent 自己去找
4. **marker 块注入**——写用户的 CLAUDE.md/AGENTS.md 必须可精确还原（local_directory 模式 M2 引入时是硬要求）

M1 起（会话 resume、多触发形态）才需要：模式标记路由、连续性通知、合并评论嵌入、有界读取提示、追加不前置的缓存纪律。

## 后续深读方向（未做）

- brief 的 `### Workflow` 四分支本体（writeWorkflowIssue 等，runtime_config_sections.go:576 起）——agent 每轮的标准动作序列
- builtin_skills（server/internal/service/builtin_skills/，9 个目录）——产品文档做成 agent 可加载技能的机制；根 CLAUDE.md 有"改 CLI 必须同步改 SKILL.md"的联动规则
