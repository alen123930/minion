import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DEFAULT_WORKSPACE_ID } from "@legion/core/issues";
import { IssueDetailPage } from "./issue-detail-page";

const WS = DEFAULT_WORKSPACE_ID;
const ISSUE_ID = "11111111-1111-1111-1111-111111111111";

const issueWire = {
  id: ISSUE_ID,
  workspace_id: WS,
  title: "修登录白屏",
  description: "步骤：打开 /login",
  status: "in_progress",
  priority: "high",
  assignee_type: "agent",
  assignee_id: "22222222-2222-2222-2222-222222222222",
  creator_type: "member",
  creator_id: "00000000-0000-0000-0000-000000000002",
  created_at: "2026-08-22T10:00:00Z",
  updated_at: "2026-08-22T10:05:00Z",
};

const commentWire = {
  id: "33333333-3333-3333-3333-333333333333",
  issue_id: ISSUE_ID,
  author_type: "member",
  author_id: "00000000-0000-0000-0000-000000000002",
  body: "复现了",
  created_at: "2026-08-22T10:01:00Z",
};

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  url: string;
  onmessage: ((ev: { data: string }) => void) | null = null;
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
}

const fetchMock = vi.fn<typeof fetch>();

function renderDetailPage() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const router = createMemoryRouter(
    [{ path: "/issues/:issueId", element: <IssueDetailPage /> }],
    { initialEntries: [`/issues/${ISSUE_ID}`] },
  );
  return {
    qc,
    ...render(
      <QueryClientProvider client={qc}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    ),
  };
}

function detailResponse(comments: unknown[], issue = issueWire) {
  return new Response(JSON.stringify({ issue, comments }), { status: 200 });
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
  FakeEventSource.instances = [];
  vi.stubGlobal("EventSource", FakeEventSource as unknown as typeof EventSource);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("IssueDetailPage", () => {
  it("渲染详情：标题/状态/描述/评论流/返回入口", async () => {
    fetchMock.mockResolvedValueOnce(detailResponse([commentWire]));
    renderDetailPage();

    expect(await screen.findByRole("heading", { name: "修登录白屏" })).toBeTruthy();
    expect(screen.getByText("in_progress")).toBeTruthy();
    expect(screen.getByText("步骤：打开 /login")).toBeTruthy();
    expect(screen.getByText("复现了")).toBeTruthy();
    expect(screen.getByRole("link", { name: "返回列表" })).toBeTruthy();
  });

  it("评论提交走 POST，失效后刷新出的新评论上屏", async () => {
    fetchMock.mockResolvedValueOnce(detailResponse([commentWire]));
    renderDetailPage();
    await screen.findByText("复现了");

    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify(commentWire), { status: 201 }),
    );
    fetchMock.mockResolvedValueOnce(
      detailResponse([
        commentWire,
        { ...commentWire, id: "66666666-6666-6666-6666-666666666666", body: "已定位", created_at: "2026-08-22T10:03:00Z" },
      ]),
    );

    fireEvent.change(screen.getByLabelText("评论"), { target: { value: "已定位" } });
    fireEvent.click(screen.getByRole("button", { name: "发表评论" }));

    await waitFor(() => {
      expect(screen.getAllByText("已定位").length).toBeGreaterThan(0);
    });
    const postCall = fetchMock.mock.calls.find(
      ([url, init]) =>
        url === `/api/issues/${ISSUE_ID}/comments` && init?.method === "POST",
    );
    expect(JSON.parse(String(postCall?.[1]?.body))).toEqual({ body: "已定位" });
    // 提交成功后清空输入框，防同文重复提交
    await waitFor(() => {
      expect((screen.getByLabelText("评论") as HTMLTextAreaElement).value).toBe("");
    });
  });

  it("SSE 实时区：事件上屏且绝不触发 refetch", async () => {
    fetchMock.mockResolvedValueOnce(detailResponse([]));
    renderDetailPage();
    await screen.findByRole("heading", { name: "修登录白屏" });
    expect(FakeEventSource.instances).toHaveLength(1);
    const source = FakeEventSource.instances[0];
    expect(source.url).toBe(`/api/issues/${ISSUE_ID}/stream`);

    source.deliver(JSON.stringify({ type: "connected", payload: { issue_id: ISSUE_ID } }));
    expect(await screen.findByText("实时流已连接")).toBeTruthy();

    source.deliver(
      JSON.stringify({
        type: "comment:created",
        payload: {
          id: "77777777-7777-7777-7777-777777777777",
          issue_id: ISSUE_ID,
          author_type: "agent",
          author_id: "22222222-2222-2222-2222-222222222222",
          body: "SSE 直达评论",
          created_at: "2026-08-22T10:04:00Z",
        },
      }),
    );
    expect(await screen.findByText("SSE 直达评论")).toBeTruthy();

    source.deliver(
      JSON.stringify({
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
      }),
    );
    await waitFor(() => {
      expect(screen.getByText(/agent 任务入队：queued/)).toBeTruthy();
    });

    // 手术 patch 纪律：事件打缓存，不引发任何额外网络请求
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("卸载时关闭 SSE 订阅", async () => {
    fetchMock.mockResolvedValueOnce(detailResponse([]));
    const view = renderDetailPage();
    await screen.findByRole("heading", { name: "修登录白屏" });
    const source = FakeEventSource.instances[0];
    view.unmount();
    expect(source.closed).toBe(true);
  });

  it("404 渲染错误信息", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ error: "issue not found" }), { status: 404 }),
    );
    renderDetailPage();
    expect(await screen.findByText(/issue not found/)).toBeTruthy();
  });
});
