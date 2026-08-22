import { describe, expect, it } from "vitest";
import {
  agentTaskSchema,
  commentSchema,
  issueDetailSchema,
  issueSchema,
  parseStreamEvent,
  streamEventSchema,
} from "./schemas";
import { DEFAULT_WORKSPACE_ID } from "./config";

// wire 样本与 server 契约（contracts IssueDto/CommentDto/AgentTaskDto，
// Jackson SNAKE_CASE 输出）逐字段对齐。
const issueWire = {
  id: "11111111-1111-1111-1111-111111111111",
  workspace_id: DEFAULT_WORKSPACE_ID,
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
  issue_id: issueWire.id,
  author_type: "member",
  author_id: "00000000-0000-0000-0000-000000000002",
  body: "复现了",
  created_at: "2026-08-22T10:01:00Z",
};

const taskWire = {
  id: "44444444-4444-4444-4444-444444444444",
  workspace_id: DEFAULT_WORKSPACE_ID,
  issue_id: issueWire.id,
  agent_id: "22222222-2222-2222-2222-222222222222",
  status: "queued",
  priority: 0,
  created_at: "2026-08-22T10:02:00Z",
};

describe("config", () => {
  it("DEFAULT_WORKSPACE_ID 等于 server WorkspaceDefaults 种子值", () => {
    expect(DEFAULT_WORKSPACE_ID).toBe("00000000-0000-0000-0000-000000000001");
  });
});

describe("issueSchema", () => {
  it("解析完整 wire 形状的 issue", () => {
    const parsed = issueSchema.parse(issueWire);
    expect(parsed).toEqual(issueWire);
  });

  it("未知 status 枚举值仍可解析（开放枚举纪律）", () => {
    const parsed = issueSchema.parse({ ...issueWire, status: "brand-new" });
    expect(parsed.status).toBe("brand-new");
  });

  it("description 允许 null", () => {
    const parsed = issueSchema.parse({ ...issueWire, description: null });
    expect(parsed.description).toBeNull();
  });

  it("缺关键字段时校验失败（契约漂移可被 parseWithFallback 捕获）", () => {
    expect(issueSchema.safeParse({ id: "x" }).success).toBe(false);
  });
});

describe("commentSchema", () => {
  it("解析完整 wire 形状的 comment", () => {
    expect(commentSchema.parse(commentWire)).toEqual(commentWire);
  });
});

describe("agentTaskSchema", () => {
  it("解析完整 wire 形状的 agent task", () => {
    expect(agentTaskSchema.parse(taskWire)).toEqual(taskWire);
  });
});

describe("issueDetailSchema", () => {
  it("解析 {issue, comments} 详情结构", () => {
    const parsed = issueDetailSchema.parse({
      issue: issueWire,
      comments: [commentWire],
    });
    expect(parsed.issue.id).toBe(issueWire.id);
    expect(parsed.comments).toHaveLength(1);
  });
});

describe("streamEventSchema", () => {
  it("解析已知事件类型", () => {
    const parsed = streamEventSchema.parse({
      type: "comment:created",
      payload: commentWire,
    });
    expect(parsed.type).toBe("comment:created");
    expect(parsed.payload).toEqual(commentWire);
  });

  it("未知事件类型仍可解析（向前兼容）", () => {
    const parsed = streamEventSchema.parse({ type: "future:event", payload: {} });
    expect(parsed.type).toBe("future:event");
  });
});

describe("parseStreamEvent", () => {
  it("解析 SSE data 帧字符串为事件对象", () => {
    const event = parseStreamEvent(
      JSON.stringify({ type: "connected", payload: { issue_id: issueWire.id } }),
    );
    expect(event).toEqual({
      type: "connected",
      payload: { issue_id: issueWire.id },
    });
  });

  it("非法 JSON 返回 null", () => {
    expect(parseStreamEvent("not-json{")).toBeNull();
  });

  it("type 非字符串返回 null", () => {
    expect(parseStreamEvent(JSON.stringify({ type: 1, payload: {} }))).toBeNull();
  });

  it("缺少 payload 字段仍可解析（payload 缺省为 undefined）", () => {
    const event = parseStreamEvent(JSON.stringify({ type: "connected" }));
    expect(event?.type).toBe("connected");
  });
});
