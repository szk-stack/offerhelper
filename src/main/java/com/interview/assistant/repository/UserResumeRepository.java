package com.interview.assistant.repository;

import com.interview.assistant.entity.UserResume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserResumeRepository extends JpaRepository<UserResume, Long> {

    List<UserResume> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);

    Optional<UserResume> findByIdAndTenantId(Long id, Long tenantId);

    @Modifying
    @Query("update UserResume r set r.tenantId = :tenantId where r.tenantId is null")
    int assignTenantIdToLegacy(@Param("tenantId") Long tenantId);
}
