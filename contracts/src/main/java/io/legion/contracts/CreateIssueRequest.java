package io.legion.contracts;

import java.util.UUID;

/**
 * 创建 issue 请求。creator 缺省由 server 补默认值（M0 免认证、单 workspace
 * 硬编码，前端不感知身份）。
 */
public record CreateIssueRequest(
        String title,
        String description,
        String status,
        String priority,
        String assigneeType,
        UUID assigneeId) {
}