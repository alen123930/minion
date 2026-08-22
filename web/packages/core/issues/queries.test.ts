import { QueryClient } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { issueDetailQueryOptions, issueKeys, issueListQueryOptions } from "./queries";

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

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  fetchMock.mockReset();
});

describe("issueKeys", () => {
  it("detail 键以 workspace 域前缀开头（可整域失效）", () => {
    const key = issueKeys.detail(WS, ISSUE_ID);
    expect(key.slice(0, 3)).toEqual(["issues", WS, "detail"]);
    expect(key[3]).toBe(ISSUE_ID);
  });

  it("list 键是 detail 键的前缀兄弟（互不误伤）", () => {
    expect(issueKeys.list(WS)).toEqual(["issues", WS, "list"]);
  });

  it("transcript 键挂在本 issue 域下", () => {
    expect(issueKeys.transcript(WS, ISSUE_ID)).toEqual([
      "issues",
      WS,
      "transcript",
      ISSUE_ID,
    ]);
  });

  it("all 返回最短前缀（失效传播入口）", () => {
    expect(issueKeys.all(WS)).toEqual(["issues", WS]);
  });
});

describe("issueListQueryOptions", () => {
  it("queryFn 走 GET /api/issues 并返回数组", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify([issueWire]), { status: 200 }),
    );
    const options = issueListQueryOptions(WS);
    const data = await options.queryFn!({} as never);
    expect(data).toHaveLength(1);
    expect(data[0].id).toBe(ISSUE_ID);
  });

  it("queryKey 用 list 工厂（缓存补丁可按前缀寻址）", () => {
    expect(issueListQueryOptions(WS).queryKey).toEqual(issueKeys.list(WS));
  });
});

describe("issueDetailQueryOptions", () => {
  it("queryFn 走 GET /api/issues/{id} 并返回详情结构", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          issue: issueWire,
          comments: [
            {
              id: "33333333-3333-3333-3333-333333333333",
              issue_id: ISSUE_ID,
              author_type: "member",
              author_id: "00000000-0000-0000-0000-000000000002",
              body: "复现了",
              created_at: "2026-08-22T10:01:00Z",
            },
          ],
        }),
        { status: 200 },
      ),
    );
    const options = issueDetailQueryOptions(WS, ISSUE_ID);
    const data = await options.queryFn!({} as never);
    expect(data?.issue.id).toBe(ISSUE_ID);
    expect(data?.comments).toHaveLength(1);
  });

  it("可直接被 QueryClient 预取（结构合法）", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ issue: issueWire, comments: [] }), {
        status: 200,
      }),
    );
    const qc = new QueryClient();
    await qc.prefetchQuery(issueDetailQueryOptions(WS, ISSUE_ID));
    expect(qc.getQueryData(issueKeys.detail(WS, ISSUE_ID))).not.toBeUndefined();
  });
});
