import { queryOptions } from "@tanstack/react-query";
import { getIssueDetail, listIssues } from "./api";

/**
 * queryKey 工厂：所有 issue 域缓存补丁/失效都经由这些键寻址，
 * 禁止页面手写 ["issues", ...] 字面量（漂移即失配）。
 * wsId 键控照参照仓库 issues/queries.ts 的形态，M1 多工作区零改动。
 */
export const issueKeys = {
  all: (wsId: string) => ["issues", wsId] as const,
  /** 前缀键：列表失效/寻址用。 */
  list: (wsId: string) => [...issueKeys.all(wsId), "list"] as const,
  detail: (wsId: string, issueId: string) =>
    [...issueKeys.all(wsId), "detail", issueId] as const,
  /**
   * SSE 实时区 transcript 的合成键：append-only 事件日志住在
   * Query 缓存里（SSE 事件只打 Query 缓存的纪律，详细设计 §8.3），
   * 不设 queryFn——未订阅时无条目即为空。
   */
  transcript: (wsId: string, issueId: string) =>
    [...issueKeys.all(wsId), "transcript", issueId] as const,
};

export function issueListQueryOptions(wsId: string) {
  return queryOptions({
    queryKey: issueKeys.list(wsId),
    queryFn: () => listIssues(),
  });
}

export function issueDetailQueryOptions(wsId: string, issueId: string) {
  return queryOptions({
    queryKey: issueKeys.detail(wsId, issueId),
    queryFn: () => getIssueDetail(issueId),
  });
}
