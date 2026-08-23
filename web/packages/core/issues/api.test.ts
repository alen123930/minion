import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  createComment,
  createIssue,
  getIssueDetail,
  listIssues,
} from "./api";

const issueWire = {
  id: "11111111-1111-1111-1111-111111111111",
  workspace_id: "00000000-0000-0000-0000-000000000001",
  title: "修登录白屏",
  description: null as string | null,
  status: "todo",
  priority: "none",
  assignee_type: null as string | null,
  assignee_id: null as string | null,
  creator_type: "member",
  creator_id: "00000000-0000-0000-0000-000000000002",
  created_at: "2026-08-22T10:00:00Z",
  updated_at: "2026-08-22T10:00:00Z",
};

const commentWire = {
  id: "33333333-3333-3333-3333-333333333333",
  issue_id: issueWire.id,
  author_type: "member",
  author_id: "00000000-0000-0000-0000-000000000002",
  body: "复现了",
  created_at: "2026-08-22T10:01:00Z",
};

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  fetchMock.mockReset();
});

describe("listIssues", () => {
  it("GET /api/issues 并返回解析后的列表", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, [issueWire]));
    const issues = await listIssues();
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/issues",
      expect.objectContaining({ method: "GET" }),
    );
    expect(issues).toHaveLength(1);
    expect(issues[0].title).toBe("修登录白屏");
  });

  it("契约漂移时降级为空数组而非抛异常", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, [{ nope: 1 }]));
    const issues = await listIssues();
    expect(issues).toEqual([]);
  });

  it("500 时抛 ApiError", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(500, { error: "internal error" }),
    );
    await expect(listIssues()).rejects.toBeInstanceOf(ApiError);
  });
});

describe("getIssueDetail", () => {
  it("GET /api/issues/{id} 并返回 {issue, comments}", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, { issue: issueWire, comments: [commentWire] }),
    );
    const detail = await getIssueDetail(issueWire.id);
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/issues/${issueWire.id}`,
      expect.objectContaining({ method: "GET" }),
    );
    expect(detail?.issue.id).toBe(issueWire.id);
    expect(detail?.comments).toHaveLength(1);
  });

  it("404 时抛携带状态码与服务器 error 消息的 ApiError", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(404, { error: "issue not found" }),
    );
    const err = await getIssueDetail("nope").catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(404);
    expect((err as ApiError).message).toBe("issue not found");
  });

  it("契约漂移时降级为 null", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { issue: "garbage" }));
    expect(await getIssueDetail(issueWire.id)).toBeNull();
  });
});

describe("createIssue", () => {
  it("POST /api/issues 携带 JSON body 并返回新 issue", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(201, issueWire));
    const issue = await createIssue({ title: "修登录白屏", description: "步骤" });
    const [url, init] = fetchMock.mock.calls[0] as Parameters<typeof fetch>;
    expect(url).toBe("/api/issues");
    expect(init?.method).toBe("POST");
    expect(init?.headers).toMatchObject({ "Content-Type": "application/json" });
    expect(JSON.parse(String(init?.body))).toEqual({
      title: "修登录白屏",
      description: "步骤",
    });
    expect(issue?.title).toBe("修登录白屏");
  });

  it("400（标题缺失）时抛 ApiError", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(400, { error: "title is required" }),
    );
    const err = await createIssue({ title: "" }).catch((e: unknown) => e);
    expect((err as ApiError).status).toBe(400);
    expect((err as ApiError).message).toBe("title is required");
  });
});

describe("createComment", () => {
  it("POST /api/issues/{issueId}/comments 携带 body 并返回新 comment", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(201, commentWire));
    const comment = await createComment(issueWire.id, { body: "复现了" });
    const [url, init] = fetchMock.mock.calls[0] as Parameters<typeof fetch>;
    expect(url).toBe(`/api/issues/${issueWire.id}/comments`);
    expect(JSON.parse(String(init?.body))).toEqual({ body: "复现了" });
    expect(comment?.body).toBe("复现了");
  });
});
