package com.interview.assistant.repository;

import com.interview.assistant.entity.UserInterviewRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserInterviewRecordRepository extends JpaRepository<UserInterviewRecord, Long> {

    List<UserInterviewRecord> findByCompany(String company);

    List<UserInterviewRecord> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);

    Optional<UserInterviewRecord> findByIdAndTenantId(Long id, Long tenantId);

    @Modifying
    @Query("update UserInterviewRecord r set r.tenantId = :tenantId where r.tenantId is null")
    int assignTenantIdToLegacy(@Param("tenantId") Long tenantId);
}
