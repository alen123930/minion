import { create } from "zustand";
import type { IssueStatusCategory } from "./schemas";

export type StatusFilter = "all" | IssueStatusCategory;

interface IssueListViewState {
  /** 列表页状态过滤——纯视图状态，绝不镜像服务端数据（详细设计 §8.3）。 */
  statusFilter: StatusFilter;
  setStatusFilter: (filter: StatusFilter) => void;
}

export const useIssueListViewStore = create<IssueListViewState>((set) => ({
  statusFilter: "all",
  setStatusFilter: (statusFilter) => set({ statusFilter }),
}));
