package io.legion.server.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.legion.contracts.RunAgentTaskRequest;
import io.legion.contracts.RunAgentTaskResponse;
import io.legion.server.service.AgentTaskService;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentTaskService agentTaskService;

    public AgentController(AgentTaskService agentTaskService) {
        this.agentTaskService = agentTaskService;
    }

    @PostMapping("/{id}/tasks")
    public ResponseEntity<RunAgentTaskResponse> run(@PathVariable UUID id, @RequestBody RunAgentTaskRequest req) {
        RunAgentTaskResponse res = agentTaskService.run(id, req);
        HttpStatus status = res.coalesced() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(res);
    }
}