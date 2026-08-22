import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  DEFAULT_WORKSPACE_ID,
  useIssueListViewStore,
} from "@legion/core/issues";
import { IssueListPage } from "./issue-list-page";

const WS = DEFAULT_WORKSPACE_ID;

function issueWire(overrides: Record<string, unknown> = {}) {
  return {
    id: "11111111-1111-1111-1111-111111111111",
    workspace_id: WS,
    title: "修登录白屏",
    description: null,
    status: "todo",
    priority: "high",
    assignee_type: null,
    assignee_id: null,
    creator_type: "member",
    creator_id: "00000000-0000-0000-0000-000000000002",
    created_at: "2026-08-22T10:00:00Z",
    updated_at: "2026-08-22T10:00:00Z",
    ...overrides,
  };
}

const fetchMock = vi.fn<typeof fetch>();

function renderPage() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const router = createMemoryRouter(
    [
      { path: "/issues", element: <IssueListPage /> },
      { path: "/issues/:issueId", element: <div>detail-page</div> },
    ],
    { initialEntries: ["/issues"] },
  );
  return render(
    <QueryClientProvider client={qc}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  fetchMock.mockReset();
  useIssueListViewStore.getState().setStatusFilter("all");
});

describe("IssueListPage", () => {
  it("渲染 issue 列表：标题、状态、优先级、指派", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(
        JSON.stringify([
          issueWire(),
          issueWire({
            id: "99999999-9999-9999-9999-999999999999",
            title: "第二个任务",
            status: "in_progress",
            assignee_type: "agent",
            priority: "none",
          }),
        ]),
        { status: 200 },
      ),
    );
    renderPage();
    expect(await screen.findByText("修登录白屏")).toBeTruthy();
    expect(screen.getByText("第二个任务")).toBeTruthy();
    // 状态文本同时出现在过滤按钮与行内徽章，断言存在即可
    expect(screen.getAllByText("todo").length).toBeGreaterThan(0);
    expect(screen.getAllByText("in_progress").length).toBeGreaterThan(0);
    expect(screen.getAllByText("high").length).toBeGreaterThan(0);
    expect(screen.getByText("未指派")).toBeTruthy();
    expect(screen.getByText("agent")).toBeTruthy();
  });

  it("点击状态过滤只显示该状态的 issue", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify([issueWire(), issueWire({ id: "99999999-9999-9999-9999-999999999999", title: "第二个任务", status: "in_progress" })]), {
        status: 200,
      }),
    );
    renderPage();
    await screen.findByText("修登录白屏");

    fireEvent.click(screen.getByRole("button", { name: "in_progress" }));
    expect(screen.queryByText("修登录白屏")).toBeNull();
    expect(screen.getByText("第二个任务")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "all" }));
    expect(screen.getByText("修登录白屏")).toBeTruthy();
  });

  it("建单：填标题提交后调 POST，成功后跳转详情", async () => {
    fetchMock.mockImplementationOnce(() =>
      Promise.resolve(new Response(JSON.stringify([issueWire()]), { status: 200 })),
    );
    renderPage();
    await screen.findByText("修登录白屏");

    fetchMock.mockImplementationOnce(() =>
      Promise.resolve(
        new Response(
          JSON.stringify(issueWire({ id: "55555555-5555-5555-5555-555555555555", title: "新任务" })),
          { status: 201 },
        ),
      ),
    );
    fetchMock.mockImplementationOnce(() =>
      Promise.resolve(new Response(JSON.stringify([]), { status: 200 })),
    );

    fireEvent.click(screen.getByRole("button", { name: "新建 issue" }));
    fireEvent.change(screen.getByLabelText("标题"), {
      target: { value: "新任务" },
    });
    fireEvent.click(screen.getByRole("button", { name: "提交" }));

    await waitFor(() => {
      expect(screen.getByText("detail-page")).toBeTruthy();
    });
    const postCall = fetchMock.mock.calls.find(
      ([url, init]) => url === "/api/issues" && init?.method === "POST",
    );
    expect(JSON.parse(String(postCall?.[1]?.body))).toEqual({ title: "新任务" });
  });

  it("空列表渲染空态", async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }));
    renderPage();
    expect(await screen.findByText("暂无 issue")).toBeTruthy();
  });

  it("请求失败渲染错误信息", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ error: "internal error" }), { status: 500 }),
    );
    renderPage();
    expect(await screen.findByText(/internal error/)).toBeTruthy();
  });
});
