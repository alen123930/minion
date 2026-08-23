import type { QueryClient } from "@tanstack/react-query";
import { parseWithFallback } from "../api/schema";
import { issueKeys } from "./queries";
import {
  agentTaskSchema,
  commentSchema,
  issueSchema,
  type AgentTask,
  type Comment,
  type Issue,
  type IssueDetail,
  type StreamEvent,
} from "./schemas";
import { sortCommentsAsc } from "./timeline-sort";

/** SSE 实时区条目：append-only，住在 Query 缓存合成键下。 */
export interface TranscriptEntry {
  seq: number;
  at: string;
  type: string;
  text: string;
}

/**
 * M0 版"事件 → 缓存补丁"单入口（详细设计 §8.3 cache-coordinator 的雏形，
 * M1 落地完整收敛规则时在此扩展）。纪律：
 * - 手术 patch 优先，绝不 refetch 可见列表（拖拽闪烁的根源）
 * - 列表里不存在的 issue 不硬插入（sort+filter 下的正确槽位是服务器知识）
 * - payload 契约漂移经 parseWithFallback 降级跳过，不抛异常
 */
export function applyIssueStreamEvent(
  qc: QueryClient,
  wsId: string,
  issueId: string,
  event: StreamEvent,
): void {
  switch (event.type) {
    case "issue:updated": {
      const issue = parseWithFallback<Issue | null>(
        event.payload,
        issueSchema,
        null,
        { endpoint: "stream issue:updated" },
      );
      if (issue) {
        patchIssueInDetail(qc, wsId, issueId, issue);
        patchIssueInList(qc, wsId, issue);
      }
      appendTranscript(qc, wsId, issueId, {
        at: issue?.updated_at ?? nowIso(),
        type: event.type,
        text: issue ? `issue 更新：${issue.title} · ${issue.status}` : "issue 更新（载荷无法解析）",
      });
      break;
    }
    case "comment:created": {
      const comment = parseWithFallback<Comment | null>(
        event.payload,
        commentSchema,
        null,
        { endpoint: "stream comment:created" },
      );
      if (comment && comment.issue_id === issueId) {
        appendCommentToDetail(qc, wsId, issueId, comment);
      }
      appendTranscript(qc, wsId, issueId, {
        at: comment?.created_at ?? nowIso(),
        type: event.type,
        text: comment
          ? `新评论（${comment.author_type}）：${comment.body}`
          : "新评论（载荷无法解析）",
      });
      break;
    }
    case "task:queued": {
      const task = parseWithFallback<AgentTask | null>(
        event.payload,
        agentTaskSchema,
        null,
        { endpoint: "stream task:queued" },
      );
      appendTranscript(qc, wsId, issueId, {
        at: task?.created_at ?? nowIso(),
        type: event.type,
        text: task
          ? `agent 任务入队：${task.status}（agent ${task.agent_id.slice(0, 8)}…）`
          : "agent 任务入队（载荷无法解析）",
      });
      break;
    }
    case "connected": {
      appendTranscript(qc, wsId, issueId, {
        at: nowIso(),
        type: event.type,
        text: "实时流已连接",
      });
      break;
    }
    default: {
      // 未知事件类型留痕不解读——服务器比客户端新时不丢线索。
      appendTranscript(qc, wsId, issueId, {
        at: nowIso(),
        type: event.type,
        text: `未识别事件：${event.type}`,
      });
    }
  }
}

function nowIso(): string {
  return new Date().toISOString();
}

/**
 * EventSource onerror 留痕（本地状态，非服务端事件）。
 * WHATWG 语义：非 200 响应（如 issue 不存在的 404）→ 连接永久失败，
 * readyState=CLOSED，浏览器不再重连；网络瞬断/服务重启 → readyState=CONNECTING，
 * 浏览器按默认间隔自动重连，不在此人工重连。
 */
export function appendStreamStatus(
  qc: QueryClient,
  wsId: string,
  issueId: string,
  status: "reconnecting" | "closed",
): void {
  appendTranscript(qc, wsId, issueId, {
    at: nowIso(),
    type: "stream:error",
    text:
      status === "closed"
        ? "连接已关闭（非 200 响应或致命错误），浏览器不再重连"
        : "连接断开，浏览器自动重连中",
  });
}

function patchIssueInDetail(
  qc: QueryClient,
  wsId: string,
  issueId: string,
  issue: Issue,
): void {
  const key = issueKeys.detail(wsId, issueId);
  const detail = qc.getQueryData<IssueDetail>(key);
  if (!detail || detail.issue.id !== issue.id) return;
  qc.setQueryData<IssueDetail>(key, { ...detail, issue });
}

function patchIssueInList(qc: QueryClient, wsId: string, issue: Issue): void {
  const key = issueKeys.list(wsId);
  const list = qc.getQueryData<Issue[]>(key);
  if (!list?.some((row) => row.id === issue.id)) return;
  qc.setQueryData<Issue[]>(
    key,
    list.map((row) => (row.id === issue.id ? issue : row)),
  );
}

function appendCommentToDetail(
  qc: QueryClient,
  wsId: string,
  issueId: string,
  comment: Comment,
): void {
  const key = issueKeys.detail(wsId, issueId);
  const detail = qc.getQueryData<IssueDetail>(key);
  if (!detail || detail.issue.id !== comment.issue_id) return;
  if (detail.comments.some((row) => row.id === comment.id)) return;
  const comments = sortCommentsAsc([...detail.comments, comment]);
  qc.setQueryData<IssueDetail>(key, { ...detail, comments });
}

function appendTranscript(
  qc: QueryClient,
  wsId: string,
  issueId: string,
  entry: Omit<TranscriptEntry, "seq">,
): void {
  const key = issueKeys.transcript(wsId, issueId);
  const current = qc.getQueryData<TranscriptEntry[]>(key) ?? [];
  qc.setQueryData<TranscriptEntry[]>(key, [...current, { seq: current.length + 1, ...entry }]);
}
