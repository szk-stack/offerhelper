package com.interview.assistant.repository;

import com.interview.assistant.entity.MockInterviewSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MockInterviewSessionRepository extends JpaRepository<MockInterviewSession, Long> {

    Optional<MockInterviewSession> findBySessionId(String sessionId);

    List<MockInterviewSession> findAllByOrderByCreatedAtDesc();

    List<MockInterviewSession> findByStatusOrderByCreatedAtDesc(MockInterviewSession.Status status);
}
