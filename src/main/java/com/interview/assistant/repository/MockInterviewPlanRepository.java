package com.interview.assistant.repository;

import com.interview.assistant.entity.MockInterviewPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MockInterviewPlanRepository extends JpaRepository<MockInterviewPlan, Long> {

    Optional<MockInterviewPlan> findBySession_SessionId(String sessionId);
}
