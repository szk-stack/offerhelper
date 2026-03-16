package com.interview.assistant.repository;

import com.interview.assistant.entity.ToolChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ToolChatSessionRepository extends JpaRepository<ToolChatSession, Long> {
    Optional<ToolChatSession> findBySessionId(String sessionId);

    List<ToolChatSession> findAllByOrderByUpdatedAtDesc();
}
