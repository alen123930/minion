import { describe, expect, it } from "vitest";
import { useIssueListViewStore } from "./store";

describe("useIssueListViewStore", () => {
  it("初始状态过滤为 all", () => {
    expect(useIssueListViewStore.getState().statusFilter).toBe("all");
  });

  it("setStatusFilter 更新纯视图状态", () => {
    useIssueListViewStore.getState().setStatusFilter("in_progress");
    expect(useIssueListViewStore.getState().statusFilter).toBe("in_progress");
    useIssueListViewStore.getState().setStatusFilter("all");
    expect(useIssueListViewStore.getState().statusFilter).toBe("all");
  });
});
