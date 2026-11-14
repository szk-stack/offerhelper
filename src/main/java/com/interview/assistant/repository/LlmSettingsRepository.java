package com.interview.assistant.repository;

import com.interview.assistant.entity.LlmSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LlmSettingsRepository extends JpaRepository<LlmSettings, Long> {

    Optional<LlmSettings> findByTenantId(Long tenantId);

    void deleteByTenantId(Long tenantId);
}
