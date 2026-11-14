package com.interview.assistant.repository;

import com.interview.assistant.entity.MockInterviewReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MockInterviewReportRepository extends JpaRepository<MockInterviewReport, Long> {

    Optional<MockInterviewReport> findBySession_SessionId(String sessionId);

    Optional<MockInterviewReport> findBySession_Id(Long sessionId);

    /** 历史数据回填：早期报告没有 status 字段 */
    List<MockInterviewReport> findByStatusIsNull();

    /** 列表页用的轻量投影，不加载 reportJson */
    interface ReportSummary {
        Long getSessionId();
        Integer getOverallScore();
        String getOverallLevel();
        MockInterviewReport.Status getStatus();
    }

    @Query("select r.session.id as sessionId, r.overallScore as overallScore, " +
           "r.overallLevel as overallLevel, r.status as status " +
           "from MockInterviewReport r where r.session.id in :ids")
    List<ReportSummary> findSummaryBySessionIds(@Param("ids") Collection<Long> ids);
}
