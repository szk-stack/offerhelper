package com.interview.assistant.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 结构化面试计划：存储 AI 生成的面试流程 JSON
 */
@Entity
@Table(name = "mock_interview_plans")
public class MockInterviewPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false, unique = true)
    @JsonIgnore
    private MockInterviewSession session;

    /**
     * 面试计划 JSON，结构如：
     * {
     *   "phases": [
     *     {
     *       "phaseId": 1,
     *       "type": "WARM_UP | PROJECT | INTERNSHIP | EXPERIENCE_BASED | WRAP_UP",
     *       "title": "阶段标题",
     *       "questions": [
     *         { "id": "q1", "content": "问题内容", "maxFollowUps": 3, "source": "resume|experience" }
     *       ]
     *     }
     *   ]
     * }
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String planJson;

    /** 面试计划中的总问题数（冗余缓存值） */
    private int totalQuestions;

    /** 面试计划中的总阶段数（冗余缓存值） */
    private int totalPhases;

    private LocalDateTime createdAt = LocalDateTime.now();

    // ---- Getters & Setters ----
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public MockInterviewSession getSession() { return session; }
    public void setSession(MockInterviewSession session) { this.session = session; }
    public String getPlanJson() { return planJson; }
    public void setPlanJson(String planJson) { this.planJson = planJson; }
    public int getTotalQuestions() { return totalQuestions; }
    public void setTotalQuestions(int totalQuestions) { this.totalQuestions = totalQuestions; }
    public int getTotalPhases() { return totalPhases; }
    public void setTotalPhases(int totalPhases) { this.totalPhases = totalPhases; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
