package com.paypilot.agent.repo;

import com.paypilot.agent.domain.AgentSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentSessionRepository extends JpaRepository<AgentSession, Long> {
}
