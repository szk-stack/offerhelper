package com.interview.assistant.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 智能助手对话消息实体（独立表，不复用面试模拟的消息表）。
 */
@Entity
@Table(name = "tool_chat_messages")
public class ToolChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonView(ToolChatSession.Views.Detail.class)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tool_session_id", nullable = false)
    @JsonIgnore
    private ToolChatSession toolSession;

    /** user | assistant */
    @Column(nullable = false, length = 20)
    @JsonView(ToolChatSession.Views.Detail.class)
    private String role;

    @Column(columnDefinition = "TEXT", nullable = false)
    @JsonView(ToolChatSession.Views.Detail.class)
    private String content;

    @JsonView(ToolChatSession.Views.Detail.class)
    private int sortOrder;

    @JsonView(ToolChatSession.Views.Detail.class)
    private LocalDateTime createdAt = LocalDateTime.now();

    // Getters & Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ToolChatSession getToolSession() {
        return toolSession;
    }

    public void setToolSession(ToolChatSession toolSession) {
        this.toolSession = toolSession;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
