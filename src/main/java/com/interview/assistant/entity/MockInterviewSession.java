package com.interview.assistant.entity;

import com.fasterxml.jackson.annotation.JsonView;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 模拟面试会话：记录一次完整的语音面试过程
 */
@Entity
@Table(name = "mock_interview_sessions", indexes = @Index(columnList = "session_id", unique = true))
public class MockInterviewSession {

    public enum Status {
        CREATED,      // 已创建，待生成面试计划
        PLAN_READY,   // 面试计划已生成，待开始
        IN_PROGRESS,  // 面试进行中
        PAUSED,       // 暂停
        COMPLETED,    // 正常结束
        ABORTED       // 中途退出
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonView(Views.List.class)
    private Long id;

    @Column(name = "session_id", unique = true, nullable = false)
    @JsonView(Views.List.class)
    private String sessionId;

    /** 所属租户（数据隔离依据） */
    @Column(name = "tenant_id")
    @JsonView(Views.List.class)
    private Long tenantId;

    /** 目标公司 */
    @JsonView(Views.List.class)
    private String company;

    /** 目标部门 */
    @JsonView(Views.List.class)
    private String department;

    /** 面试官风格: GENTLE / PRESSURE / TECH_GEEK */
    @JsonView(Views.List.class)
    private String interviewerStyle = "GENTLE";

    /** 简历文本快照 */
    @Column(columnDefinition = "TEXT")
    @JsonView(Views.Detail.class)
    private String resumeSnapshot;

    /** 选用的面经数据快照 */
    @Column(columnDefinition = "TEXT")
    @JsonView(Views.Detail.class)
    private String experienceSnapshot;

    /** 用户自定义必问问题（JSON 数组字符串） */
    @Column(columnDefinition = "TEXT")
    @JsonView(Views.Detail.class)
    private String mandatoryQuestions;

    /** 面试难度: EASY / MEDIUM / HARD */
    @JsonView(Views.List.class)
    private String difficulty = "MEDIUM";

    /**
     * 面试模式：
     * PRACTICE 练习模式 —— 每题答完立刻给出评价、纠错和参考答案，再问下一题；
     * SIMULATION 真实模拟 —— 全程只追问或换题，不评价、不给答案，结束后统一给逐题参考答案和综合评价。
     */
    @JsonView(Views.List.class)
    @Column(name = "interview_mode")
    private String mode = "PRACTICE";

    /** 面试配置（各类题目数量等，JSON 字符串） */
    @Column(columnDefinition = "TEXT")
    @JsonView(Views.Detail.class)
    private String interviewConfig;

    /** 面试状态 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @JsonView(Views.List.class)
    private Status status = Status.CREATED;

    /** 当前阶段索引（phases 数组下标） */
    @JsonView(Views.Detail.class)
    private int currentPhaseIndex = 0;

    /** 当前阶段内的问题索引 */
    @JsonView(Views.Detail.class)
    private int currentQuestionIndex = 0;

    /** 当前追问轮次 */
    @JsonView(Views.Detail.class)
    private int currentFollowUpCount = 0;

    /** 预计面试时长（分钟） */
    @JsonView(Views.List.class)
    private int estimatedDurationMinutes = 30;

    /**
     * 以下字段不落库，仅供列表接口做轻量摘要，
     * 避免为了展示分数而把整份 plan / dialogues / report 序列化出去。
     */
    @Transient
    @JsonView(Views.List.class)
    private Integer reportScore;

    @Transient
    @JsonView(Views.List.class)
    private String reportLevel;

    /** 报告状态：GENERATING / SUCCESS / FAILED / EMPTY，历史数据为 null */
    @Transient
    @JsonView(Views.List.class)
    private String reportStatus;

    @Transient
    @JsonView(Views.List.class)
    private int dialogueCount;

    @JsonView(Views.List.class)
    private LocalDateTime createdAt = LocalDateTime.now();

    @JsonView(Views.List.class)
    private LocalDateTime startedAt;

    @JsonView(Views.List.class)
    private LocalDateTime endedAt;

    /** 关联的面试计划 */
    @OneToOne(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonView(Views.Detail.class)
    private MockInterviewPlan plan;

    /** 对话记录 */
    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    @JsonView(Views.Detail.class)
    private List<MockInterviewDialogue> dialogues = new ArrayList<>();

    /** 评估报告 */
    @OneToOne(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonView(Views.Detail.class)
    private MockInterviewReport report;

    public static class Views {
        public interface List {}
        public interface Detail extends List {}
    }

    // ---- Getters & Setters ----
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public String getInterviewerStyle() { return interviewerStyle; }
    public void setInterviewerStyle(String interviewerStyle) { this.interviewerStyle = interviewerStyle; }
    public String getResumeSnapshot() { return resumeSnapshot; }
    public void setResumeSnapshot(String resumeSnapshot) { this.resumeSnapshot = resumeSnapshot; }
    public String getExperienceSnapshot() { return experienceSnapshot; }
    public void setExperienceSnapshot(String experienceSnapshot) { this.experienceSnapshot = experienceSnapshot; }
    public String getMandatoryQuestions() { return mandatoryQuestions; }
    public void setMandatoryQuestions(String mandatoryQuestions) { this.mandatoryQuestions = mandatoryQuestions; }
    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getInterviewConfig() { return interviewConfig; }
    public void setInterviewConfig(String interviewConfig) { this.interviewConfig = interviewConfig; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int getCurrentPhaseIndex() { return currentPhaseIndex; }
    public void setCurrentPhaseIndex(int currentPhaseIndex) { this.currentPhaseIndex = currentPhaseIndex; }
    public int getCurrentQuestionIndex() { return currentQuestionIndex; }
    public void setCurrentQuestionIndex(int currentQuestionIndex) { this.currentQuestionIndex = currentQuestionIndex; }
    public int getCurrentFollowUpCount() { return currentFollowUpCount; }
    public void setCurrentFollowUpCount(int currentFollowUpCount) { this.currentFollowUpCount = currentFollowUpCount; }
    public int getEstimatedDurationMinutes() { return estimatedDurationMinutes; }
    public void setEstimatedDurationMinutes(int estimatedDurationMinutes) { this.estimatedDurationMinutes = estimatedDurationMinutes; }
    public Integer getReportScore() { return reportScore; }
    public void setReportScore(Integer reportScore) { this.reportScore = reportScore; }
    public String getReportLevel() { return reportLevel; }
    public void setReportLevel(String reportLevel) { this.reportLevel = reportLevel; }
    public String getReportStatus() { return reportStatus; }
    public void setReportStatus(String reportStatus) { this.reportStatus = reportStatus; }
    public int getDialogueCount() { return dialogueCount; }
    public void setDialogueCount(int dialogueCount) { this.dialogueCount = dialogueCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
    public MockInterviewPlan getPlan() { return plan; }
    public void setPlan(MockInterviewPlan plan) { this.plan = plan; }
    public List<MockInterviewDialogue> getDialogues() { return dialogues; }
    public void setDialogues(List<MockInterviewDialogue> dialogues) { this.dialogues = dialogues; }
    public MockInterviewReport getReport() { return report; }
    public void setReport(MockInterviewReport report) { this.report = report; }
}
