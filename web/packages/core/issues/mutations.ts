import {
  mutationOptions,
  type UseMutationOptions,
} from "@tanstack/react-query";
import type { QueryClient } from "@tanstack/react-query";
import { createComment, createIssue } from "./api";
import { issueKeys } from "./queries";
import type {
  Comment,
  CreateCommentInput,
  CreateIssueInput,
  Issue,
} from "./schemas";

/**
 * 写操作。失效时机契约：延迟到 onSettled（成功与失败都失效），
 * 保证 mutation 期间的手术补丁不被过期响应覆盖（详细设计 §8.3）。
 * M0 建单/评论无乐观更新——服务器是唯一事实源，砍到骨头。
 */
export function createIssueMutationOptions(
  qc: QueryClient,
  wsId: string,
): UseMutationOptions<Issue | null, Error, CreateIssueInput> {
  return mutationOptions({
    mutationFn: (input: CreateIssueInput) => createIssue(input),
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: issueKeys.list(wsId) });
    },
  });
}

export function createCommentMutationOptions(
  qc: QueryClient,
  wsId: string,
  issueId: string,
): UseMutationOptions<Comment | null, Error, CreateCommentInput> {
  return mutationOptions({
    mutationFn: (input: CreateCommentInput) => createComment(issueId, input),
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: issueKeys.detail(wsId, issueId) });
    },
  });
}
