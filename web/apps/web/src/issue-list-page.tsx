import {
  ApiError,
  DEFAULT_WORKSPACE_ID,
  ISSUE_STATUS_CATEGORIES,
  createIssueMutationOptions,
  issueListQueryOptions,
  useIssueListViewStore,
  type Issue,
} from "@legion/core/issues";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { Button } from "@legion/ui";

const STATUS_BADGE: Record<string, string> = {
  backlog: "bg-neutral-800 text-neutral-300",
  todo: "bg-blue-900 text-blue-100",
  in_progress: "bg-amber-900 text-amber-100",
  in_review: "bg-violet-900 text-violet-100",
  done: "bg-emerald-900 text-emerald-100",
  blocked: "bg-red-900 text-red-100",
  cancelled: "bg-neutral-700 text-neutral-400 line-through",
};

/** 未知状态走 default 兜底（开放枚举纪律），绝不穷举。 */
function StatusBadge({ status }: { status: string }) {
  const color = STATUS_BADGE[status] ?? "bg-neutral-800 text-neutral-300";
  return (
    <span className={`rounded px-1.5 py-0.5 text-xs ${color}`}>{status}</span>
  );
}

function IssueRow({ issue }: { issue: Issue }) {
  return (
    <Link
      to={`/issues/${issue.id}`}
      className="flex items-center justify-between gap-3 rounded-md border border-neutral-800 px-3 py-2 hover:border-neutral-600"
    >
      <span className="min-w-0 flex-1 truncate text-sm">{issue.title}</span>
      <span className="flex shrink-0 items-center gap-2 text-xs text-neutral-400">
        <StatusBadge status={issue.status} />
        <span>{issue.priority}</span>
        <span>{issue.assignee_type ?? "未指派"}</span>
        <span>{issue.updated_at.slice(0, 16).replace("T", " ")}</span>
      </span>
    </Link>
  );
}

function CreateIssueForm() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const create = useMutation({
    ...createIssueMutationOptions(qc, DEFAULT_WORKSPACE_ID),
    onSuccess: (issue) => {
      // 契约漂移降级返回 null 时不跳转，列表失效后仍可见结果。
      if (issue) navigate(`/issues/${issue.id}`);
    },
  });

  return (
    <form
      className="flex flex-col gap-2 rounded-md border border-neutral-800 p-3"
      onSubmit={(e) => {
        e.preventDefault();
        create.mutate({
          title: title.trim(),
          ...(description.trim() ? { description: description.trim() } : {}),
        });
      }}
    >
      <label className="flex flex-col gap-1 text-sm">
        标题
        <input
          className="rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1 text-sm"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          required
          minLength={1}
        />
      </label>
      <label className="flex flex-col gap-1 text-sm">
        描述
        <textarea
          className="min-h-16 rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1 text-sm"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
        />
      </label>
      {create.isError ? (
        <p className="text-sm text-red-400">
          {create.error instanceof ApiError ? create.error.message : "创建失败"}
        </p>
      ) : null}
      <Button type="submit" disabled={create.isPending || title.trim().length === 0}>
        提交
      </Button>
    </form>
  );
}

export function IssueListPage() {
  const { data: issues, isPending, error } = useQuery(
    issueListQueryOptions(DEFAULT_WORKSPACE_ID),
  );
  const statusFilter = useIssueListViewStore((s) => s.statusFilter);
  const setStatusFilter = useIssueListViewStore((s) => s.setStatusFilter);
  const [formOpen, setFormOpen] = useState(false);

  const filtered = (issues ?? []).filter(
    (issue) => statusFilter === "all" || issue.status === statusFilter,
  );

  return (
    <main className="mx-auto flex min-h-screen max-w-3xl flex-col gap-4 bg-neutral-950 px-4 py-8 text-neutral-50">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold">任务列表</h1>
        <Button onClick={() => setFormOpen((open) => !open)}>新建 issue</Button>
      </div>

      {formOpen ? <CreateIssueForm /> : null}

      <div className="flex flex-wrap gap-2" role="group" aria-label="状态过滤">
        {(["all", ...ISSUE_STATUS_CATEGORIES] as const).map((status) => (
          <button
            key={status}
            type="button"
            onClick={() => setStatusFilter(status)}
            className={`rounded-full px-2.5 py-0.5 text-xs ${
              statusFilter === status
                ? "bg-neutral-100 text-neutral-900"
                : "bg-neutral-900 text-neutral-400 hover:text-neutral-100"
            }`}
          >
            {status}
          </button>
        ))}
      </div>

      {isPending ? (
        <p className="text-sm text-neutral-400">加载中…</p>
      ) : error ? (
        <p className="text-sm text-red-400">
          {error instanceof ApiError ? error.message : "加载失败"}
        </p>
      ) : filtered.length === 0 ? (
        <p className="text-sm text-neutral-400">暂无 issue</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {filtered.map((issue) => (
            <li key={issue.id}>
              <IssueRow issue={issue} />
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
