/**
 * M0 单工作区：与 server 端 WorkspaceDefaults 的种子值保持一致
 * （DefaultWorkspaceInitializer 启动时幂等写入该 workspace 行）。
 * M1 引入多工作区后由 bootstrap 接口取代，勿在组件里散落引用。
 */
export const DEFAULT_WORKSPACE_ID = "00000000-0000-0000-0000-000000000001";
