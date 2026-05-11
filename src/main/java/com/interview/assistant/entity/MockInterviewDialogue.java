package com.interview.assistant.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 模拟面试对话记录：每条消息（面试官/候选人）为一行
 */
@Entity
@Table(name = "mock_interview_dialogues")
public class MockInterviewDialogue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    @JsonIgnore
    private MockInterviewSession session;

    /** 角色: interviewer | candidate */
    @Column(nullable = false, length = 20)
    private String role;

    /** 消息文本内容 */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 所属阶段类型: WARM_UP / PROJECT / INTERNSHIP / EXPERIENCE_BASED / WRAP_UP */
    @Column(length = 30)
    private String phaseType;

    /** 所属阶段索引 */
    private int phaseIndex;

    /** 关联的问题 ID（对应面试计划中的 question.id） */
    @Column(length = 50)
    private String questionId;

    /** 是否为追问 */
    private boolean followUp = false;

    /** 排序序号 */
    private int sortOrder;

    private LocalDateTime createdAt = LocalDateTime.now();

    // ---- Getters & Setters ----
    @JsonView(MockInterviewSession.Views.Detail.class)
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public MockInterviewSession getSession() { return session; }
    public void setSession(MockInterviewSession session) { this.session = session; }
    @JsonView(MockInterviewSession.Views.Detail.class)
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    @JsonView(MockInterviewSession.Views.Detail.class)
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    @JsonView(MockInterviewSession.Views.Detail.class)
    public String getPhaseType() { return phaseType; }
    public void setPhaseType(String phaseType) { this.phaseType = phaseType; }
    @JsonView(MockInterviewSession.Views.Detail.class)
    public int getPhaseIndex() { return phaseIndex; }
    public void setPhaseIndex(int phaseIndex) { this.phaseIndex = phaseIndex; }
    @JsonView(MockInterviewSession.Views.Detail.class)
    public String getQuestionId() { return questionId; }
    public void setQuestionId(String questionId) { this.questionId = questionId; }
    @JsonView(MockInterviewSession.Views.Detail.class)
    public boolean isFollowUp() { return followUp; }
    public void setFollowUp(boolean followUp) { this.followUp = followUp; }
    @JsonView(MockInterviewSession.Views.Detail.class)
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    @JsonView(MockInterviewSession.Views.Detail.class)
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
