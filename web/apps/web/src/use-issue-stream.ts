import {
  DEFAULT_WORKSPACE_ID,
  appendStreamStatus,
  applyIssueStreamEvent,
  parseStreamEvent,
} from "@legion/core/issues";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";

/**
 * SSE 订阅（DOM 层接线）：EventSource 生命周期归本 hook，
 * 帧解析与缓存补丁全在 @legion/core/issues（node 环境已测）。
 * 断线重连交给 EventSource 原生行为；M0 不做人工心跳。
 */
export function useIssueStream(issueId: string | undefined): void {
  const qc = useQueryClient();

  useEffect(() => {
    if (!issueId) return;
    const source = new EventSource(`/api/issues/${issueId}/stream`);
    source.onmessage = (ev: MessageEvent<string>) => {
      const event = parseStreamEvent(ev.data);
      if (event) {
        applyIssueStreamEvent(qc, DEFAULT_WORKSPACE_ID, issueId, event);
      }
    };
    source.onerror = () => {
      // 404 等非 200 响应：连接永久失败（readyState=CLOSED），浏览器不重连，只留痕；
      // 网络瞬断：readyState=CONNECTING，浏览器自动重连，无需人工 close。
      appendStreamStatus(
        qc,
        DEFAULT_WORKSPACE_ID,
        issueId,
        source.readyState === EventSource.CLOSED ? "closed" : "reconnecting",
      );
    };
    return () => source.close();
  }, [qc, issueId]);
}
