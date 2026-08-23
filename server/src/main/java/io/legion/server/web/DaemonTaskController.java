package io.legion.server.web;

import io.legion.contracts.ClaimTaskResponse;
import io.legion.contracts.CompleteTaskRequest;
import io.legion.contracts.CompleteTaskResponse;
import io.legion.contracts.FailTaskRequest;
import io.legion.contracts.FailTaskResponse;
import io.legion.contracts.ReportUsageRequest;
import io.legion.contracts.TaskMessagesRequest;
import io.legion.server.service.AgentTaskClaimService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * daemon 协议端点（设计 §4.2 M1 路径，M0 只实现认领与终态三件套）。
 * M0 内嵌 worker 经 localhost HTTP 调这里，M1 拆独立 daemon 进程协议零改动。
 */
@RestController
@RequestMapping("/api/daemon/tasks")
public class DaemonTaskController {

    private final AgentTaskClaimService claimService;

    public DaemonTaskController(AgentTaskClaimService claimService) {
        this.claimService = claimService;
    }

    /** 认领下一个任务；队列空返回 {"task": null}。 */
    @PostMapping("/claim")
    public ClaimTaskResponse claim() {
        return new ClaimTaskResponse(claimService.claim());
    }

    /** 终态 completed（幂等：重复上报 200 + applied=false）。 */
    @PostMapping("/{id}/complete")
    public CompleteTaskResponse complete(@PathVariable UUID id,
                                         @RequestBody CompleteTaskRequest request) {
        return new CompleteTaskResponse(claimService.complete(id, request.result()));
    }

    /** 终态 failed（幂等：重复上报 200 + applied=false）。 */
    @PostMapping("/{id}/fail")
    public FailTaskResponse fail(@PathVariable UUID id,
                                 @RequestBody FailTaskRequest request) {
        return new FailTaskResponse(claimService.fail(id, request.error(), request.failureClass()));
    }

    /** 流式事件批次（设计 §4.2 messages）：映射成 SSE task:message 帧转发。 */
    @PostMapping("/{id}/messages")
    public Map<String, Boolean> messages(@PathVariable UUID id,
                                         @RequestBody TaskMessagesRequest request) {
        claimService.forwardMessages(id, request.events());
        return Map.of("accepted", true);
    }

    /** usage 上报（设计 §4.2：先于一切 early return 的计费路径）。 */
    @PostMapping("/{id}/usage")
    public Map<String, Boolean> usage(@PathVariable UUID id,
                                      @RequestBody ReportUsageRequest request) {
        return Map.of("applied", claimService.recordUsage(id, request));
    }
}