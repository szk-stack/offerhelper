package com.interview.assistant.repository;

import com.interview.assistant.entity.MockInterviewSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MockInterviewSessionRepository extends JpaRepository<MockInterviewSession, Long> {

    Optional<MockInterviewSession> findBySessionId(String sessionId);

    List<MockInterviewSession> findAllByOrderByCreatedAtDesc();

    List<MockInterviewSession> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);

    List<MockInterviewSession> findByStatusOrderByCreatedAtDesc(MockInterviewSession.Status status);

    /** 历史数据迁移：把没有归属的会话挂到默认空间 */
    @Modifying
    @Query("update MockInterviewSession s set s.tenantId = :tenantId where s.tenantId is null")
    int assignTenantIdToLegacy(@Param("tenantId") Long tenantId);
}
