> 详细设计 · 十一、Java 栈特有风险 · [返回索引](README.md)

# 十一、Java 栈特有风险

1. **虚拟线程 pinning**：JDK 21 里 synchronized 块内的 IO（典型如 BufferedInputStream.read）会 pin 住 carrier 线程。daemon 子进程的 stdout/stderr 泵必须用固定平台线程池，不要放虚拟线程；HTTP/DB/一般任务编排用虚拟线程无碍。JDK 24（JEP 491）后才可全面虚拟线程化。
2. **Windows 进程树**：ProcessHandle.descendants() 覆盖不全的场景靠 taskkill /T 兜底；原项目 Windows 支持的坑集中在进程组、路径、stdin 保持，M2 起 Windows CI 必上。
3. **Flyway + CONCURRENTLY**：忘记 executeInTransaction=false 会直接迁移失败，且 CONCURRENTLY 失败会留 INVALID 索引——按"单语句单文件"纪律写，失败重跑同版本号。
4. **MyBatis 类型映射**：UUID/JSONB/timestamptz 的 TypeHandler 第一天集中建好；散装 handler 是后期最烦的 drift 源。
5. **Go→Java 语义漂移**：最大隐性成本。对策只有一条——以竞态测试清单锚定语义（§9.3 即验收标准），每移植一块先写它的测试。
6. **JVM 常驻开销**：数百 MB 内存 + 秒级启动，对比 Go 单二进制确实差，但对本系统规模（单机、任务量级几十并发）完全无虞。不要为省这点内存做架构妥协。
