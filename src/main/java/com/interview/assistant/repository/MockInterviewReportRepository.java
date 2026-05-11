package com.interview.assistant.repository;

import com.interview.assistant.entity.MockInterviewReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MockInterviewReportRepository extends JpaRepository<MockInterviewReport, Long> {

    Optional<MockInterviewReport> findBySession_SessionId(String sessionId);

    Optional<MockInterviewReport> findBySession_Id(Long sessionId);
}
