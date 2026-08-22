import { cleanup, render, screen } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { afterEach, describe, expect, it } from "vitest";
import { routes } from "./app";

afterEach(() => {
  cleanup();
});

const routerFuture = { v7_relativeSplatPath: true };
const providerFuture = { v7_startTransition: true };

describe("app 路由骨架", () => {
  it("在 / 渲染首页占位", () => {
    const router = createMemoryRouter(routes, {
      initialEntries: ["/"],
      future: routerFuture,
    });
    render(<RouterProvider router={router} future={providerFuture} />);
    expect(screen.getByRole("heading", { name: "legion" })).toBeTruthy();
  });

  it("在 /issues 渲染列表页占位", () => {
    const router = createMemoryRouter(routes, {
      initialEntries: ["/issues"],
      future: routerFuture,
    });
    render(<RouterProvider router={router} future={providerFuture} />);
    expect(screen.getByText("IssueListPage（占位）")).toBeTruthy();
  });

  it("在 /issues/:issueId 渲染详情页占位", () => {
    const router = createMemoryRouter(routes, {
      initialEntries: ["/issues/123"],
      future: routerFuture,
    });
    render(<RouterProvider router={router} future={providerFuture} />);
    expect(screen.getByText("IssueDetailPage（占位）")).toBeTruthy();
  });
});