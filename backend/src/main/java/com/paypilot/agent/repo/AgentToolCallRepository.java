package com.paypilot.agent.repo;

import com.paypilot.agent.domain.AgentToolCall;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentToolCallRepository extends JpaRepository<AgentToolCall, Long> {

    List<AgentToolCall> findBySessionIdOrderByCreatedAtAscIdAsc(Long sessionId);
}
