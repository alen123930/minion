package io.legion.contracts.agent;

import java.time.Duration;
import java.util.Map;

/**
 * 一次 agent 执行的协商面（设计 §6.1，参照仓库 agent.go ExecOptions 注释全文精读）。
 * 三个超时是三独立生命期，各自 null = 不启用（MUL-3064：Timeout=0 表示无硬性墙钟、
 * 活性交给不活动看门狗）——独立配置、独立触发，不许合并成一个"超时"。
 *
 * @param workDir             子进程 cwd；null = 继承 daemon cwd
 * @param model               模型选择；null = CLI 默认
 * @param resumeSessionId     续会话指针；null = 新会话
 * @param totalTimeout        总墙钟
 * @param inactivityTimeout   单轮静默（每收到一条事件重排）
 * @param firstOutputTimeout  首轮零产出（收到第一条事件后取消）
 * @param extraEnv            追加环境变量（覆盖同名继承值）
 */
public record ExecOptions(String workDir,
                          String model,
                          String resumeSessionId,
                          Duration totalTimeout,
                          Duration inactivityTimeout,
                          Duration firstOutputTimeout,
                          Map<String, String> extraEnv) {

    public static ExecOptions defaults() {
        return new ExecOptions(null, null, null, null, null, null, Map.of());
    }
}
