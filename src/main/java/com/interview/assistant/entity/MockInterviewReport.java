package com.interview.assistant.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 模拟面试量化评估报告
 */
@Entity
@Table(name = "mock_interview_reports")
public class MockInterviewReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false, unique = true)
    @JsonIgnore
    private MockInterviewSession session;

    /** 总评分（0-100） */
    private int overallScore;

    /** 总评等级，如 A / B+ / C */
    @Column(length = 10)
    private String overallLevel;

    /**
     * 量化报告 JSON，结构如：
     * {
     *   "dimensions": [
     *     { "name": "项目深度", "score": 85, "comment": "..." },
     *     ...
     *   ],
     *   "questionDetails": [
     *     { "questionId": "q1", "question": "...", "candidateAnswer": "...", "score": 7, "feedback": "...", "suggestion": "..." }
     *   ],
     *   "improvementPlan": ["1. ...", "2. ..."],
     *   "overallComment": "..."
     * }
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String reportJson;

    private LocalDateTime createdAt = LocalDateTime.now();

    // ---- Getters & Setters ----
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public MockInterviewSession getSession() { return session; }
    public void setSession(MockInterviewSession session) { this.session = session; }
    public int getOverallScore() { return overallScore; }
    public void setOverallScore(int overallScore) { this.overallScore = overallScore; }
    public String getOverallLevel() { return overallLevel; }
    public void setOverallLevel(String overallLevel) { this.overallLevel = overallLevel; }
    public String getReportJson() { return reportJson; }
    public void setReportJson(String reportJson) { this.reportJson = reportJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
