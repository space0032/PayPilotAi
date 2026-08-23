package com.paypilot.agent.repo;

import com.paypilot.agent.domain.AgentMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {

    List<AgentMessage> findBySessionIdOrderByCreatedAtAscIdAsc(Long sessionId);
}
