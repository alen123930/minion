package io.legion.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.legion.server.domain.IssueRow;
import io.legion.server.service.IssueService.IssueChange;
import io.legion.server.service.IssueService.RunTrigger;

/**
 * WillEnqueueRun M0 单规则的纯函数决策测试（设计 §4.3：M0 只实现
 * "assign 给 agent 且状态非 backlog"一条规则；backlog 是停车场）。
 * decideEnqueue 是 IssueService 内无 IO 的静态决策，agent 存在性由调用方传入。
 */
class WillEnqueueRunTest {

    private static final UUID ISSUE = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();

    @Test
    void createAssignedToAgentInTodoTriggersRun() {
        Optional<RunTrigger> t = decide(agentIssue("todo"), change(true));
        assertThat(t).isPresent();
        assertThat(t.get().agentId()).isEqualTo(AGENT);
    }

    @Test
    void createAssignedToAgentInBacklogParksWithoutTrigger() {
        assertThat(decide(agentIssue("backlog"), change(true))).isEmpty();
    }

    @Test
    void memberAssigneeNeverTriggers() {
        assertThat(decide(issue("member", AGENT, "todo"), change(true))).isEmpty();
    }

    @Test
    void unassignedNeverTriggers() {
        assertThat(decide(issue(null, null, "todo"), change(true))).isEmpty();
    }

    @Test
    void unknownAgentNeverTriggers() {
        assertThat(IssueService.decideEnqueue(agentIssue("todo"), change(true), false)).isEmpty();
    }

    @Test
    void statusPromotionFromBacklogTriggersRun() {
        IssueChange promote = new IssueChange(false, false, true, "backlog");
        assertThat(decide(agentIssue("todo"), promote)).isPresent();
    }

    @Test
    void statusChangeBetweenActiveStatusesDoesNotTrigger() {
        IssueChange shift = new IssueChange(false, false, true, "todo");
        assertThat(decide(agentIssue("in_progress"), shift)).isEmpty();
    }

    @Test
    void assigneeChangeToAgentTriggersRun() {
        IssueChange assign = new IssueChange(false, true, false, "todo");
        assertThat(decide(agentIssue("todo"), assign)).isPresent();
    }

    private static Optional<RunTrigger> decide(IssueRow issue, IssueChange change) {
        return IssueService.decideEnqueue(issue, change, true);
    }

    private static IssueRow agentIssue(String status) {
        return issue("agent", AGENT, status);
    }

    private static IssueRow issue(String assigneeType, UUID assigneeId, String status) {
        return new IssueRow(ISSUE, UUID.randomUUID(), "t", null, status, "none",
                assigneeType, assigneeId, "member", UUID.randomUUID(),
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static IssueChange change(boolean isCreate) {
        return new IssueChange(isCreate, false, false, null);
    }
}