import { QueryClient } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  createCommentMutationOptions,
  createIssueMutationOptions,
} from "./mutations";
import { issueKeys } from "./queries";

const WS = "00000000-0000-0000-0000-000000000001";
const ISSUE_ID = "11111111-1111-1111-1111-111111111111";

const issueWire = {
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
};

const commentWire = {
  id: "33333333-3333-3333-3333-333333333333",
  issue_id: ISSUE_ID,
  author_type: "member",
  author_id: "00000000-0000-0000-0000-000000000002",
  body: "复现了",
  created_at: "2026-08-22T10:01:00Z",
};

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  fetchMock.mockReset();
});

describe("createIssueMutationOptions", () => {
  it("mutationFn 走 POST /api/issues", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify(issueWire), { status: 201 }),
    );
    const qc = new QueryClient();
    const options = createIssueMutationOptions(qc, WS);
    const created = await options.mutationFn!(
      { title: "修登录白屏" },
      {} as never,
    );
    expect(created?.id).toBe(ISSUE_ID);
  });

  it("成功后失效列表（延迟到 onSettled）", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify(issueWire), { status: 201 }),
    );
    const qc = new QueryClient();
    const invalidateSpy = vi.spyOn(qc, "invalidateQueries");
    const options = createIssueMutationOptions(qc, WS);
    await options.mutationFn!({ title: "修登录白屏" }, {} as never);
    await (options.onSettled as (a: unknown, e: unknown, v: unknown, c: unknown) => unknown)?.(
      issueWire,
      null,
      { title: "修登录白屏" },
      undefined,
    );
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: issueKeys.list(WS) });
  });

  it("失败后同样失效列表（onSettled 而非 onSuccess）", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ error: "title is required" }), { status: 400 }),
    );
    const qc = new QueryClient();
    const invalidateSpy = vi.spyOn(qc, "invalidateQueries");
    const options = createIssueMutationOptions(qc, WS);
    await options.mutationFn!({ title: "" }, {} as never).catch(() => undefined);
    await (options.onSettled as (a: unknown, e: unknown, v: unknown, c: unknown) => unknown)?.(
      null,
      new Error("bad request"),
      { title: "" },
      undefined,
    );
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: issueKeys.list(WS) });
  });
});

describe("createCommentMutationOptions", () => {
  it("mutationFn 走 POST /api/issues/{id}/comments，onSettled 失效 detail", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify(commentWire), { status: 201 }),
    );
    const qc = new QueryClient();
    const invalidateSpy = vi.spyOn(qc, "invalidateQueries");
    const options = createCommentMutationOptions(qc, WS, ISSUE_ID);
    const created = await options.mutationFn!({ body: "复现了" }, {} as never);
    expect(created?.body).toBe("复现了");
    await (options.onSettled as (a: unknown, e: unknown, v: unknown, c: unknown) => unknown)?.(
      created,
      null,
      { body: "复现了" },
      undefined,
    );
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: issueKeys.detail(WS, ISSUE_ID),
    });
  });
});
