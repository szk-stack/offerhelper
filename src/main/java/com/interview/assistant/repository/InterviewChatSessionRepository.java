package com.interview.assistant.repository;

import com.interview.assistant.entity.InterviewChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InterviewChatSessionRepository extends JpaRepository<InterviewChatSession, Long> {

    Optional<InterviewChatSession> findBySessionId(String sessionId);

    List<InterviewChatSession> findAllByOrderByCreatedAtDesc();

    List<InterviewChatSession> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);

    @Modifying
    @Query("update InterviewChatSession s set s.tenantId = :tenantId where s.tenantId is null")
    int assignTenantIdToLegacy(@Param("tenantId") Long tenantId);
}
