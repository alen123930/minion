package io.legion.contracts;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * daemon 认领协议的返回载荷（设计 §3.4 认领 SQL 的 RETURNING 列）。
 * 用可变 bean 而非 record：MyBatis 结果映射走 setter（mapUnderscoreToCamelCase），
 * record 没有 setter 会引入构造器映射的额外复杂度。
 */
public class AgentTaskRow {

    private UUID id;
    private UUID workspaceId;
    private UUID issueId;
    private UUID agentId;
    private UUID runtimeId;
    private String status;
    private JsonNode context;
    private UUID triggerCommentId;
    private int priority;
    private OffsetDateTime createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(UUID workspaceId) {
        this.workspaceId = workspaceId;
    }

    public UUID getIssueId() {
        return issueId;
    }

    public void setIssueId(UUID issueId) {
        this.issueId = issueId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public UUID getRuntimeId() {
        return runtimeId;
    }

    public void setRuntimeId(UUID runtimeId) {
        this.runtimeId = runtimeId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public JsonNode getContext() {
        return context;
    }

    public void setContext(JsonNode context) {
        this.context = context;
    }

    public UUID getTriggerCommentId() {
        return triggerCommentId;
    }

    public void setTriggerCommentId(UUID triggerCommentId) {
        this.triggerCommentId = triggerCommentId;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}