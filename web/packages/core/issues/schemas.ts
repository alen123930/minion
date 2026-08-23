import { z } from "zod";

/**
 * 状态类别全集——行为等价类，与 DB/API 白名单一致
 * （server IssueService 状态白名单、V1__init.sql issue.status CHECK）。
 */
export const ISSUE_STATUS_CATEGORIES = [
  "backlog",
  "todo",
  "in_progress",
  "in_review",
  "done",
  "blocked",
  "cancelled",
] as const;

export type IssueStatusCategory = (typeof ISSUE_STATUS_CATEGORIES)[number];

/**
 * 开放联合：7 个内置值保住编辑器自动补全，同时接受任意自定义/未知键。
 * 服务器将来新增状态键时旧客户端不白屏（parseWithFallback 的宽松契约，
 * 参照仓库 types/issue.ts 的 MUL-6243 语义）。需要展示语义的分支必须
 * 带 default 兜底，禁止穷举 switch。
 */
export type IssueStatus = IssueStatusCategory | (string & {});

export type IssuePriority =
  | "urgent"
  | "high"
  | "medium"
  | "low"
  | "none"
  | (string & {});

export type IssueAssigneeType = "member" | "agent" | (string & {});

/** 字段名与 wire（Jackson SNAKE_CASE 输出）逐字对齐，不做 camelCase 转换。 */
export interface Issue {
  id: string;
  workspace_id: string;
  title: string;
  description: string | null;
  status: IssueStatus;
  priority: IssuePriority;
  assignee_type: IssueAssigneeType | null;
  assignee_id: string | null;
  creator_type: string;
  creator_id: string;
  created_at: string;
  updated_at: string;
}

export interface Comment {
  id: string;
  issue_id: string;
  author_type: string;
  author_id: string;
  body: string;
  created_at: string;
}

export interface AgentTask {
  id: string;
  workspace_id: string;
  issue_id: string;
  agent_id: string;
  status: string;
  priority: number;
  created_at: string;
}

export interface IssueDetail {
  issue: Issue;
  comments: Comment[];
}

export interface CreateIssueInput {
  title: string;
  description?: string;
  priority?: IssuePriority;
}

export interface CreateCommentInput {
  body: string;
}

/**
 * zod schema 一律宽松（枚举写 z.string()）：校验的目的是抓"契约漂移"
 * 而非穷举合法值——失败走 parseWithFallback 降级，不抛白屏。
 */
export const issueSchema = z.object({
  id: z.string(),
  workspace_id: z.string(),
  title: z.string(),
  description: z.string().nullable(),
  status: z.string(),
  priority: z.string(),
  assignee_type: z.string().nullable(),
  assignee_id: z.string().nullable(),
  creator_type: z.string(),
  creator_id: z.string(),
  created_at: z.string(),
  updated_at: z.string(),
});

export const commentSchema = z.object({
  id: z.string(),
  issue_id: z.string(),
  author_type: z.string(),
  author_id: z.string(),
  body: z.string(),
  created_at: z.string(),
});

export const agentTaskSchema = z.object({
  id: z.string(),
  workspace_id: z.string(),
  issue_id: z.string(),
  agent_id: z.string(),
  status: z.string(),
  priority: z.number(),
  created_at: z.string(),
});

export const issueDetailSchema = z.object({
  issue: issueSchema,
  comments: z.array(commentSchema),
});

export const issueListSchema = z.array(issueSchema);

/** SSE 帧形状 {type, payload}——type 宽松，未知事件类型向前兼容。 */
export const streamEventSchema = z.object({
  type: z.string(),
  payload: z.unknown().optional(),
});

export interface StreamEvent {
  type: string;
  payload: unknown;
}

/**
 * 解析 SSE data 帧字符串为事件对象。
 * 非法 JSON 或形状不符返回 null（调用方跳过该帧）——传输垃圾帧
 * 不是契约漂移，不值得告警刷屏；payload 的契约校验由
 * stream-updaters 按事件类型分别过 parseWithFallback。
 */
export function parseStreamEvent(data: string): StreamEvent | null {
  let json: unknown;
  try {
    json = JSON.parse(data);
  } catch {
    return null;
  }
  const result = streamEventSchema.safeParse(json);
  if (!result.success) return null;
  return { type: result.data.type, payload: result.data.payload };
}
