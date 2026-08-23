import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import {
  DEFAULT_WORKSPACE_ID,
  issueKeys,
  type IssueDetail,
} from "@legion/core/issues";
import { useIssueStream } from "./use-issue-stream";

const ISSUE_ID = "11111111-1111-1111-1111-111111111111";

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  // WHATWG EventSource 常量镜像：0 CONNECTING / 1 OPEN / 2 CLOSED
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSED = 2;
  url: string;
  onmessage: ((ev: { data: string }) => void) | null = null;
  onerror: ((ev: unknown) => void) | null = null;
  readyState = FakeEventSource.OPEN;
  closed = false;

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  close() {
    this.closed = true;
  }

  deliver(data: string) {
    this.onmessage?.({ data });
  }

  /** 以指定 readyState 触发 error 事件，模拟断线/致命错误。 */
  fail(readyState: number) {
    this.readyState = readyState;
    this.onerror?.({});
  }
}

beforeEach(() => {
  FakeEventSource.instances = [];
  vi.stubGlobal("EventSource", FakeEventSource as unknown as typeof EventSource);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function makeWrapper(qc: QueryClient) {
  return ({ children }: { children?: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
}

describe("useIssueStream", () => {
  it("订阅 /api/issues/{id}/stream，卸载时关闭", () => {
    const qc = new QueryClient();
    const { unmount } = renderHook(() => useIssueStream(ISSUE_ID), {
      wrapper: makeWrapper(qc),
    });
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(FakeEventSource.instances[0].url).toBe(`/api/issues/${ISSUE_ID}/stream`);
    unmount();
    expect(FakeEventSource.instances[0].closed).toBe(true);
  });

  it("issueId 为空时不建立订阅", () => {
    const qc = new QueryClient();
    renderHook(() => useIssueStream(undefined), { wrapper: makeWrapper(qc) });
    expect(FakeEventSource.instances).toHaveLength(0);
  });

  it("事件经解析打进 Query 缓存（connected 留痕、comment:created 追评）", () => {
    const qc = new QueryClient();
    qc.setQueryData<IssueDetail>(issueKeys.detail(DEFAULT_WORKSPACE_ID, ISSUE_ID), {
      issue: {
        id: ISSUE_ID,
        workspace_id: DEFAULT_WORKSPACE_ID,
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
      },
      comments: [],
    });

    renderHook(() => useIssueStream(ISSUE_ID), { wrapper: makeWrapper(qc) });
    const source = FakeEventSource.instances[0];

    source.deliver(JSON.stringify({ type: "connected", payload: { issue_id: ISSUE_ID } }));
    expect(
      qc.getQueryData<unknown[]>(issueKeys.transcript(DEFAULT_WORKSPACE_ID, ISSUE_ID)),
    ).toHaveLength(1);

    source.deliver(
      JSON.stringify({
        type: "comment:created",
        payload: {
          id: "33333333-3333-3333-3333-333333333333",
          issue_id: ISSUE_ID,
          author_type: "agent",
          author_id: "22222222-2222-2222-2222-222222222222",
          body: "已定位",
          created_at: "2026-08-22T10:01:00Z",
        },
      }),
    );
    expect(
      qc.getQueryData<IssueDetail>(issueKeys.detail(DEFAULT_WORKSPACE_ID, ISSUE_ID))
        ?.comments,
    ).toHaveLength(1);
  });

  it("非法 JSON 帧被跳过，不炸订阅", () => {
    const qc = new QueryClient();
    renderHook(() => useIssueStream(ISSUE_ID), { wrapper: makeWrapper(qc) });
    const source = FakeEventSource.instances[0];
    expect(() => source.deliver("garbage{")).not.toThrow();
    expect(
      qc.getQueryData<unknown[]>(issueKeys.transcript(DEFAULT_WORKSPACE_ID, ISSUE_ID)),
    ).toBeUndefined();
  });

  it("致命错误（readyState=CLOSED）留痕且不再重连", () => {
    const qc = new QueryClient();
    renderHook(() => useIssueStream(ISSUE_ID), { wrapper: makeWrapper(qc) });
    const source = FakeEventSource.instances[0];
    source.fail(FakeEventSource.CLOSED);
    const transcript = qc.getQueryData<
      { type: string; text: string }[]
    >(issueKeys.transcript(DEFAULT_WORKSPACE_ID, ISSUE_ID));
    expect(transcript).toHaveLength(1);
    expect(transcript?.[0].type).toBe("stream:error");
    expect(transcript?.[0].text).toContain("不再重连");
  });

  it("瞬断（readyState=CONNECTING）留痕重连中", () => {
    const qc = new QueryClient();
    renderHook(() => useIssueStream(ISSUE_ID), { wrapper: makeWrapper(qc) });
    const source = FakeEventSource.instances[0];
    source.fail(FakeEventSource.CONNECTING);
    const transcript = qc.getQueryData<
      { type: string; text: string }[]
    >(issueKeys.transcript(DEFAULT_WORKSPACE_ID, ISSUE_ID));
    expect(transcript).toHaveLength(1);
    expect(transcript?.[0].text).toContain("重连");
  });
});
