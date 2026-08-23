import { parseWithFallback } from "../api/schema";
import {
  commentSchema,
  issueDetailSchema,
  issueListSchema,
  issueSchema,
  type Comment,
  type CreateCommentInput,
  type CreateIssueInput,
  type Issue,
  type IssueDetail,
} from "./schemas";

/**
 * 统一错误面：server 的 {error: message} + 语义化状态码
 * （ApiExceptionHandler）。UI 层据此渲染失败态，不解析自由文本。
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly endpoint: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

interface RequestResult {
  ok: boolean;
  status: number;
  body: unknown;
}

async function request(path: string, init?: RequestInit): Promise<RequestResult> {
  const res = await fetch(path, init);
  const text = await res.text();
  let body: unknown = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = null;
    }
  }
  return { ok: res.ok, status: res.status, body };
}

function toApiError(endpoint: string, status: number, body: unknown): ApiError {
  const message =
    typeof body === "object" &&
    body !== null &&
    typeof (body as { error?: unknown }).error === "string"
      ? (body as { error: string }).error
      : `request failed: ${status}`;
  return new ApiError(status, message, endpoint);
}

function postInit(body: unknown): RequestInit {
  return {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  };
}

/** 相对路径：dev 经 Vite proxy，生产同源部署——core 不碰环境配置。 */
export async function listIssues(): Promise<Issue[]> {
  const path = "/api/issues";
  const r = await request(path, { method: "GET" });
  if (!r.ok) throw toApiError(`GET ${path}`, r.status, r.body);
  return parseWithFallback(r.body, issueListSchema, [], {
    endpoint: `GET ${path}`,
  });
}

export async function getIssueDetail(issueId: string): Promise<IssueDetail | null> {
  const path = `/api/issues/${issueId}`;
  const r = await request(path, { method: "GET" });
  if (!r.ok) throw toApiError(`GET ${path}`, r.status, r.body);
  return parseWithFallback<IssueDetail | null>(r.body, issueDetailSchema, null, {
    endpoint: `GET ${path}`,
  });
}

export async function createIssue(input: CreateIssueInput): Promise<Issue | null> {
  const path = "/api/issues";
  const r = await request(path, postInit(input));
  if (!r.ok) throw toApiError(`POST ${path}`, r.status, r.body);
  return parseWithFallback<Issue | null>(r.body, issueSchema, null, {
    endpoint: `POST ${path}`,
  });
}

export async function createComment(
  issueId: string,
  input: CreateCommentInput,
): Promise<Comment | null> {
  const path = `/api/issues/${issueId}/comments`;
  const r = await request(path, postInit(input));
  if (!r.ok) throw toApiError(`POST ${path}`, r.status, r.body);
  return parseWithFallback<Comment | null>(r.body, commentSchema, null, {
    endpoint: `POST ${path}`,
  });
}
