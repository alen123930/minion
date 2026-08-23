import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen } from "@testing-library/react";
import { RouterProvider, createMemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { routes } from "./app";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const routerFuture = { v7_relativeSplatPath: true };
const providerFuture = { v7_startTransition: true };

function renderRoute(path: string) {
  const router = createMemoryRouter(routes, {
    initialEntries: [path],
    future: routerFuture,
  });
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <RouterProvider router={router} future={providerFuture} />
    </QueryClientProvider>,
  );
}

describe("app 路由骨架", () => {
  beforeEach(() => {
    const fetchMock = vi.fn<typeof fetch>();
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ error: "issue not found" }), { status: 404 }),
    );
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal(
      "EventSource",
      class {
        close() {}
      } as unknown as typeof EventSource,
    );
  });

  it("在 / 渲染首页占位", () => {
    renderRoute("/");
    expect(screen.getByRole("heading", { name: "legion" })).toBeTruthy();
  });

  it("在 /issues 渲染列表页", async () => {
    renderRoute("/issues");
    expect(await screen.findByRole("heading", { name: "任务列表" })).toBeTruthy();
  });

  it("在 /issues/:issueId 渲染详情页（404 时给出错误面）", async () => {
    renderRoute("/issues/123");
    expect(await screen.findByText(/issue not found/)).toBeTruthy();
  });
});
