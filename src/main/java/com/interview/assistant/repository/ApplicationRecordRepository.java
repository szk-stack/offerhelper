package com.interview.assistant.repository;

import com.interview.assistant.entity.ApplicationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ApplicationRecordRepository extends JpaRepository<ApplicationRecord, Long> {

    List<ApplicationRecord> findAllByOrderByAppliedAtDesc();

    List<ApplicationRecord> findAllByTenantIdOrderByAppliedAtDesc(Long tenantId);

    Optional<ApplicationRecord> findByIdAndTenantId(Long id, Long tenantId);

    @Modifying
    @Query("update ApplicationRecord r set r.tenantId = :tenantId where r.tenantId is null")
    int assignTenantIdToLegacy(@Param("tenantId") Long tenantId);
}
