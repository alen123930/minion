import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import { issueDetailQueryOptions, issueKeys, issueListQueryOptions } from "./queries";
import { applyIssueStreamEvent, appendStreamStatus } from "./stream-updaters";
import type { Comment, Issue, IssueDetail } from "./schemas";

const WS = "00000000-0000-0000-0000-000000000001";
const ISSUE_ID = "11111111-1111-1111-1111-111111111111";
const OTHER_ISSUE_ID = "99999999-9999-9999-9999-999999999999";

function makeIssue(overrides: Partial<Issue> = {}): Issue {
  return {
    id: ISSUE_ID,
    workspace_id: WS,
    title: "修登录白屏",
    description: null,
    status: "todo",
    priority: "none",
    assignee_type: null,
    assignee_id: null,
    creator_type: "member",
    creator_id: "00000000-0000-0000-0000-000000000002",
    created_at: "2026-08-22T10:00:00Z",
    updated_at: "2026-08-22T10:00:00Z",
    ...overrides,
  };
}

function makeComment(overrides: Partial<Comment> = {}): Comment {
  return {
    id: "33333333-3333-3333-3333-333333333333",
    issue_id: ISSUE_ID,
    author_type: "member",
    author_id: "00000000-0000-0000-0000-000000000002",
    body: "复现了",
    created_at: "2026-08-22T10:01:00Z",
    ...overrides,
  };
}

function seedCaches(qc: QueryClient, issue: Issue, comments: Comment[], list: Issue[]) {
  qc.setQueryData<IssueDetail>(issueKeys.detail(WS, issue.id), { issue, comments });
  qc.setQueryData<Issue[]>(issueKeys.list(WS), list);
}

describe("applyIssueStreamEvent: issue:updated", () => {
  it("手术 patch detail 与 list 快照，不触发列表 refetch", () => {
    const qc = new QueryClient();
    const updated = makeIssue({ status: "in_progress", updated_at: "2026-08-22T10:05:00Z" });
    seedCaches(qc, makeIssue(), [], [makeIssue(), { ...makeIssue(), id: OTHER_ISSUE_ID }]);

    const invalidateSpy = vi.spyOn(qc, "invalidateQueries");
    applyIssueStreamEvent(qc, WS, ISSUE_ID, { type: "issue:updated", payload: updated });

    const detail = qc.getQueryData<IssueDetail>(issueKeys.detail(WS, ISSUE_ID));
    expect(detail?.issue.status).toBe("in_progress");
    const list = qc.getQueryData<Issue[]>(issueKeys.list(WS));
    expect(list?.find((i) => i.id === ISSUE_ID)?.status).toBe("in_progress");
    expect(list?.find((i) => i.id === OTHER_ISSUE_ID)?.status).toBe("todo");
    expect(invalidateSpy).not.toHaveBeenCalled();
  });

  it("列表里不存在的 issue 不硬插入（正确槽位是服务器知识）", () => {
    const qc = new QueryClient();
    const other = { ...makeIssue(), id: OTHER_ISSUE_ID };
    qc.setQueryData<Issue[]>(issueKeys.list(WS), [other]);

    applyIssueStreamEvent(qc, WS, ISSUE_ID, {
      type: "issue:updated",
      payload: makeIssue({ status: "done" }),
    });

    const list = qc.getQueryData<Issue[]>(issueKeys.list(WS));
    expect(list).toHaveLength(1);
  });

  it("payload 契约漂移时跳过（不抛异常）", () => {
    const qc = new QueryClient();
    seedCaches(qc, makeIssue(), [], [makeIssue()]);
    expect(() =>
      applyIssueStreamEvent(qc, WS, ISSUE_ID, { type: "issue:updated", payload: { junk: true } }),
    ).not.toThrow();
    expect(
      qc.getQueryData<IssueDetail>(issueKeys.detail(WS, ISSUE_ID))?.issue.status,
    ).toBe("todo");
  });
});

describe("applyIssueStreamEvent: comment:created", () => {
  it("追加到 detail 评论流并保持 created_at 升序", () => {
    const qc = new QueryClient();
    const late = makeComment({
      id: "55555555-5555-5555-5555-555555555555",
      created_at: "2026-08-22T10:00:30Z",
      body: "晚了但时间戳早",
    });
    seedCaches(qc, makeIssue(), [makeComment()], []);

    applyIssueStreamEvent(qc, WS, ISSUE_ID, { type: "comment:created", payload: late });

    const comments = qc.getQueryData<IssueDetail>(issueKeys.detail(WS, ISSUE_ID))?.comments;
    expect(comments).toHaveLength(2);
    expect(comments?.[0].id).toBe(late.id);
  });

  it("同一事件重复投递按 id 去重", () => {
    const qc = new QueryClient();
    seedCaches(qc, makeIssue(), [makeComment()], []);

    applyIssueStreamEvent(qc, WS, ISSUE_ID, { type: "comment:created", payload: makeComment() });

    expect(
      qc.getQueryData<IssueDetail>(issueKeys.detail(WS, ISSUE_ID))?.comments,
    ).toHaveLength(1);
  });

  it("他人 issue 的评论不落本 issue 缓存", () => {
    const qc = new QueryClient();
    seedCaches(qc, makeIssue(), [], []);

    applyIssueStreamEvent(qc, WS, ISSUE_ID, {
      type: "comment:created",
      payload: makeComment({ issue_id: OTHER_ISSUE_ID }),
    });

    expect(
      qc.getQueryData<IssueDetail>(issueKeys.detail(WS, ISSUE_ID))?.comments,
    ).toHaveLength(0);
  });
});

describe("applyIssueStreamEvent: transcript", () => {
  it("connected 事件追加连接条目", () => {
    const qc = new QueryClient();
    applyIssueStreamEvent(qc, WS, ISSUE_ID, {
      type: "connected",
      payload: { issue_id: ISSUE_ID },
    });
    const transcript = qc.getQueryData<unknown[]>(issueKeys.transcript(WS, ISSUE_ID));
    expect(transcript).toHaveLength(1);
  });

  it("task:queued 事件追加执行条目且 append-only", () => {
    const qc = new QueryClient();
    applyIssueStreamEvent(qc, WS, ISSUE_ID, {
      type: "connected",
      payload: { issue_id: ISSUE_ID },
    });
    applyIssueStreamEvent(qc, WS, ISSUE_ID, {
      type: "task:queued",
      payload: {
        id: "44444444-4444-4444-4444-444444444444",
        workspace_id: WS,
        issue_id: ISSUE_ID,
        agent_id: "22222222-2222-2222-2222-222222222222",
        status: "queued",
        priority: 0,
        created_at: "2026-08-22T10:02:00Z",
      },
    });
    const transcript = qc.getQueryData<{ seq: number; type: string; text: string }[]>(
      issueKeys.transcript(WS, ISSUE_ID),
    );
    expect(transcript).toHaveLength(2);
    expect(transcript?.[0].seq).toBe(1);
    expect(transcript?.[1].seq).toBe(2);
    expect(transcript?.[1].type).toBe("task:queued");
    expect(transcript?.[1].text).toContain("queued");
  });

  it("未知事件类型也留痕（向前兼容）", () => {
    const qc = new QueryClient();
    applyIssueStreamEvent(qc, WS, ISSUE_ID, { type: "future:event", payload: {} });
    expect(
      qc.getQueryData<unknown[]>(issueKeys.transcript(WS, ISSUE_ID)),
    ).toHaveLength(1);
  });
});

describe("applyIssueStreamEvent: 无缓存时", () => {
  it("事件页未加载（无 detail/list 缓存）不新建实体缓存，只留 transcript", () => {
    const qc = new QueryClient();
    applyIssueStreamEvent(qc, WS, ISSUE_ID, {
      type: "issue:updated",
      payload: makeIssue(),
    });
    expect(qc.getQueryData(issueKeys.detail(WS, ISSUE_ID))).toBeUndefined();
    expect(qc.getQueryData(issueKeys.list(WS))).toBeUndefined();
    expect(
      qc.getQueryData<unknown[]>(issueKeys.transcript(WS, ISSUE_ID)),
    ).toHaveLength(1);
  });
});

describe("appendStreamStatus: EventSource 错误留痕", () => {
  it("closed：致命错误（如 404）记录不再重连", () => {
    const qc = new QueryClient();
    appendStreamStatus(qc, WS, ISSUE_ID, "closed");
    const transcript = qc.getQueryData<{ seq: number; type: string; text: string }[]>(
      issueKeys.transcript(WS, ISSUE_ID),
    );
    expect(transcript).toHaveLength(1);
    expect(transcript?.[0].type).toBe("stream:error");
    expect(transcript?.[0].text).toContain("不再重连");
  });

  it("reconnecting：瞬断记录浏览器自动重连中", () => {
    const qc = new QueryClient();
    appendStreamStatus(qc, WS, ISSUE_ID, "reconnecting");
    const transcript = qc.getQueryData<{ type: string; text: string }[]>(
      issueKeys.transcript(WS, ISSUE_ID),
    );
    expect(transcript).toHaveLength(1);
    expect(transcript?.[0].text).toContain("重连");
  });

  it("seq 顺延既有 transcript", () => {
    const qc = new QueryClient();
    applyIssueStreamEvent(qc, WS, ISSUE_ID, { type: "connected", payload: { issue_id: ISSUE_ID } });
    appendStreamStatus(qc, WS, ISSUE_ID, "reconnecting");
    const transcript = qc.getQueryData<{ seq: number }[]>(
      issueKeys.transcript(WS, ISSUE_ID),
    );
    expect(transcript).toHaveLength(2);
    expect(transcript?.[1].seq).toBe(2);
  });
});
