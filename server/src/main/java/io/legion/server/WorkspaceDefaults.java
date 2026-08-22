package io.legion.server;

import java.util.UUID;

/**
 * M0 单 workspace 硬编码常量（设计 §4.2 免认证、单 workspace）。
 * 默认 workspace 由 DefaultWorkspaceInitializer 幂等种子。
 */
public final class WorkspaceDefaults {

    public static final UUID DEFAULT_WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** M0 无认证：issue/comment 缺省 creator/author 身份（member 假体）。 */
    public static final UUID DEFAULT_MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private WorkspaceDefaults() {
    }
}