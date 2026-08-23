package io.legion.contracts.agent;

/**
 * agent backend 接口（设计 §6.1；参照仓库 agent.go Backend）。
 * 放 contracts 使 server/daemon 共用（§6.4：FakeBackend 的接口出处）。
 *
 * <p>execute 立即返回会话句柄；启动阶段即失败（可执行文件缺失、spawn 失败）
 * 抛 {@link BackendException}，携带稳定 reason。
 */
public interface AgentBackend {

    String provider();

    Session execute(ExecRequest request) throws BackendException;
}
