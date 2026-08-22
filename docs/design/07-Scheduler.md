> 详细设计 · 七、Scheduler（M3/延后，设计预留） · [返回索引](README.md)

# 七、Scheduler（M3/延后，设计预留）

sys_cron_executions 内核整体移植：tryClaim 两段 SQL（INSERT ON CONFLICT DO NOTHING + 单条 UPDATE 同时编码 retry-after-FAILED 与 stale-steal 分支）、lease_token 守卫的终态写入、DB 时钟、每 tick 先清扫过期 RUNNING。SQL 原样照抄（§3 的方言同为 PG），Java 侧只剩 30s tick 循环 + JobSpec record + PlansForScope 接口（cron-utils 计算 (anchor, now] 的触发点，只取最新一个，迟到超 5 分钟放弃）。

两条必须记住的坑直接进代码注释：游标推进逻辑必须显式与重试逻辑和解（半开区间枚举天然跳过 FAILED-with-retry 的那一格，原项目 MUL-3551 验收③）；用户可配置的调度必须配独立熔断器（24h 扫描 / 7 天回看 / ≥50 次 / ≥90% 失败 → 自动暂停 + 通知创建者，原项目 MUL-1336：1475/1476 次失败烧了 7 天没人管）。
