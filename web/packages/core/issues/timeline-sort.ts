import type { Comment } from "./schemas";

/**
 * 评论流的稳定升序排序——移植自参照仓库 packages/core/issues/timeline-sort.ts。
 *
 * 所有向 issue 评论缓存追加的写入路径必须经过本函数，保证显示顺序
 * 恒为 created_at ASC（id 平局裁决），即使 SSE 事件与 mutation 回调
 * 乱序到达。就地观察（按 id map/filter）不经此函数——它们保持既有
 * 相对顺序。
 */
export function sortCommentsAsc(entries: Comment[]): Comment[] {
  entries.sort((a, b) => {
    if (a.created_at !== b.created_at) {
      return a.created_at < b.created_at ? -1 : 1;
    }
    return a.id < b.id ? -1 : 1;
  });
  return entries;
}
