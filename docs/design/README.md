# legion 复刻：详细设计（Java Spring Boot + React）

定位：里程碑文档（《legion复刻-任务拆解与里程碑方案.md》）管"做什么、什么顺序、多少工作量"；本文管"具体怎么做"。两者共用同一套 M0-M4 里程碑，本文不改变顺序与验收判据，只把落点从 Go 参照栈翻译到 Java Spring Boot + React 栈。

所有 server/... pkg/... 参照路径仍相对 /home/alen/legion-reference（Go 原仓库），它们的作用是"语义锚点"——移植时以测试锚定语义，不靠目测。

---

## 章节索引

- [01 一、技术栈与选型理由](<01-技术栈与选型理由.md>) —— JDK21 / Spring Boot 3.5 WebMvc / MyBatis / Flyway / PG17；否决 WebFlux、JPA、STOMP、Next.js、自研迁移 runner
- [02 二、模块划分](<02-模块划分.md>) —— contracts / server / daemon / web 四模块；daemon↔server 从 M0 起走 HTTP（协议即契约）
- [03 三、数据库设计](<03-数据库设计.md>) —— Flyway 约定、M0 六表、agent_task_queue、认领 SQL、终态幂等
- [04 四、Server 端设计](<04-Server端设计.md>) —— 分层、API 面、入队路径、空认领缓存、reason code、实时通道、认证演进、MyBatis 基线
- [05 五、Daemon 端设计](<05-Daemon端设计.md>) —— 线程模型、任务生命周期、结算纪律、execenv 磁盘契约、进程树终止
- [06 六、Agent 适配层](<06-Agent适配层.md>) —— Backend 接口、claude 适配器、hermes 适配器（M2）、假 agent
- [07 七、Scheduler（M3/延后，设计预留）](<07-Scheduler.md>) —— M3/延后，sys_cron_executions 内核的设计预留
- [08 八、前端设计](<08-前端设计.md>) —— pnpm workspace 结构与域约定、API 边界、状态纪律、M0 视图清单
- [09 九、测试策略](<09-测试策略.md>) —— 测试分层、竞态测试移植手法、必移植竞态场景清单（= M1 验收标准）、前端测试
- [10 十、运行与部署形态](<10-运行与部署形态.md>) —— docker compose 开发形态、self-host 目标形态、daemon 分发
- [11 十一、Java 栈特有风险](<11-Java栈特有风险.md>) —— 虚拟线程 pinning、Windows 进程树、Flyway+CONCURRENTLY、MyBatis 类型映射、语义漂移、JVM 开销
- [12 十二、与里程碑文档的任务对照（M0 落点）](<12-里程碑任务对照.md>) —— 里程碑文档 M0 六项任务的落点映射与周计划

---

参照仓库：/home/alen/legion-reference（所有 server/... pkg/... 路径相对此目录）。
姊妹文档：《legion复刻-任务拆解与里程碑方案.md》（里程碑、工作配比、M1/M2 参照文件速查、风险清单——本文不重复其内容）。
