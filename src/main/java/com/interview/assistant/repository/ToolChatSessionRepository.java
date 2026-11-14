package com.interview.assistant.repository;

import com.interview.assistant.entity.ToolChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ToolChatSessionRepository extends JpaRepository<ToolChatSession, Long> {

    Optional<ToolChatSession> findBySessionId(String sessionId);

    List<ToolChatSession> findAllByOrderByUpdatedAtDesc();

    List<ToolChatSession> findAllByTenantIdOrderByUpdatedAtDesc(Long tenantId);

    @Modifying
    @Query("update ToolChatSession s set s.tenantId = :tenantId where s.tenantId is null")
    int assignTenantIdToLegacy(@Param("tenantId") Long tenantId);
}
