import {
  ApiError,
  DEFAULT_WORKSPACE_ID,
  createCommentMutationOptions,
  issueDetailQueryOptions,
  issueKeys,
  type TranscriptEntry,
} from "@legion/core/issues";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { Button } from "@legion/ui";
import { useIssueStream } from "./use-issue-stream";

function CommentComposer({ issueId }: { issueId: string }) {
  const qc = useQueryClient();
  const [body, setBody] = useState("");
  const create = useMutation(
    createCommentMutationOptions(qc, DEFAULT_WORKSPACE_ID, issueId),
  );

  return (
    <form
      className="flex flex-col gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        create.mutate({ body: body.trim() });
      }}
    >
      <label className="flex flex-col gap-1 text-sm">
        评论
        <textarea
          className="min-h-16 rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1 text-sm"
          value={body}
          onChange={(e) => setBody(e.target.value)}
        />
      </label>
      {create.isError ? (
        <p className="text-sm text-red-400">
          {create.error instanceof ApiError ? create.error.message : "发表失败"}
        </p>
      ) : null}
      <Button type="submit" disabled={create.isPending || body.trim().length === 0}>
        发表评论
      </Button>
    </form>
  );
}

/**
 * SSE 实时区：transcript 为 append-only 事件日志，由 useIssueStream 经
 * 缓存补丁写入（详细设计 §8.4——M0 收流式消息直接 append）。
 * enabled:false + initialData：纯客户端写入的缓存键，永不发请求。
 */
function LiveExecutionPanel({ issueId }: { issueId: string }) {
  const { data: transcript } = useQuery({
    queryKey: issueKeys.transcript(DEFAULT_WORKSPACE_ID, issueId),
    enabled: false,
    initialData: [] as TranscriptEntry[],
  });

  return (
    <section
      aria-label="实时执行区"
      className="flex flex-col gap-2 rounded-md border border-neutral-800 bg-neutral-900/40 p-3"
    >
      <h2 className="text-sm font-semibold text-neutral-300">实时执行区（SSE）</h2>
      {transcript.length === 0 ? (
        <p className="text-xs text-neutral-500">等待事件…</p>
      ) : (
        <ol className="flex flex-col gap-1 font-mono text-xs text-neutral-300">
          {transcript.map((entry) => (
            <li key={entry.seq} className="flex gap-2">
              <span className="text-neutral-600">{entry.at.slice(11, 19)}</span>
              <span className="text-neutral-500">[{entry.type}]</span>
              <span className="min-w-0 break-all">{entry.text}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

export function IssueDetailPage() {
  const { issueId } = useParams();
  const safeIssueId = issueId ?? "";
  const { data, isPending, error } = useQuery(
    issueDetailQueryOptions(DEFAULT_WORKSPACE_ID, safeIssueId),
  );
  useIssueStream(safeIssueId);

  return (
    <main className="mx-auto flex min-h-screen max-w-3xl flex-col gap-4 bg-neutral-950 px-4 py-8 text-neutral-50">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold">
          {isPending ? "加载中…" : data ? data.issue.title : "issue"}
        </h1>
        <Link to="/issues" className="text-sm underline">
          返回列表
        </Link>
      </div>

      {error ? (
        <p className="text-sm text-red-400">
          {error instanceof ApiError ? error.message : "加载失败"}
        </p>
      ) : data ? (
        <>
          <div className="flex flex-wrap gap-2 text-xs text-neutral-400">
            <span className="rounded bg-neutral-800 px-1.5 py-0.5">{data.issue.status}</span>
            <span>{data.issue.priority}</span>
            <span>{data.issue.assignee_type ?? "未指派"}</span>
            <span>创建于 {data.issue.created_at.slice(0, 16).replace("T", " ")}</span>
          </div>
          {data.issue.description ? (
            <p className="whitespace-pre-wrap rounded-md border border-neutral-800 p-3 text-sm">
              {data.issue.description}
            </p>
          ) : null}

          <LiveExecutionPanel issueId={safeIssueId} />

          <section className="flex flex-col gap-2">
            <h2 className="text-sm font-semibold text-neutral-300">评论</h2>
            {data.comments.length === 0 ? (
              <p className="text-sm text-neutral-500">暂无评论</p>
            ) : (
              <ul className="flex flex-col gap-2">
                {data.comments.map((comment) => (
                  <li
                    key={comment.id}
                    className="rounded-md border border-neutral-800 px-3 py-2 text-sm"
                  >
                    <span className="mr-2 text-xs text-neutral-500">
                      {comment.author_type} ·{" "}
                      {comment.created_at.slice(0, 16).replace("T", " ")}
                    </span>
                    <span className="whitespace-pre-wrap">{comment.body}</span>
                  </li>
                ))}
              </ul>
            )}
            <CommentComposer issueId={safeIssueId} />
          </section>
        </>
      ) : null}
    </main>
  );
}
