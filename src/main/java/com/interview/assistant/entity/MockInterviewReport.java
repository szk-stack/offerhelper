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

    /**
     * 报告生成状态：前端据此决定展示报告、提示生成中还是给出重试入口。
     * 历史数据该字段为 null，按「已生成」处理。
     */
    public enum Status {
        GENERATING, // 正在生成
        SUCCESS,    // 正常生成
        FAILED,     // 生成失败（已保留原始文本）
        EMPTY       // 没有对话记录，无法评分
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 报告生成状态 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Status status;

    /** 失败原因（供前端展示与排查） */
    @Column(length = 1000)
    private String errorMessage;

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
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
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
