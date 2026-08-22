package io.legion.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.legion.contracts.AgentTaskRow;
import io.legion.server.mapper.AgentTaskClaimMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 认领与终态化的事务边界（设计 §3.4）。M0 是单语句原子操作，@Transactional 是
 * M1 的缝：容量检查 + 认领要在同一事务（先锁 agent 行再认领）。
 */
@Service
public class AgentTaskClaimService {

    private final AgentTaskClaimMapper mapper;

    public AgentTaskClaimService(AgentTaskClaimMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public AgentTaskRow claim() {
        return mapper.claimNextTask();
    }

    @Transactional
    public boolean complete(UUID id, JsonNode result) {
        return mapper.completeTask(id, result) > 0;
    }

    @Transactional
    public boolean fail(UUID id, String error, String failureClass) {
        return mapper.failTask(id, error, failureClass) > 0;
    }
}