package com.interview.assistant.entity;

import com.fasterxml.jackson.annotation.JsonView;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 智能助手（Function Calling）对话会话实体。
 * 每个会话有独立的 sessionId，可保存历史并恢复上下文继续对话。
 */
@Entity
@Table(name = "tool_chat_sessions", indexes = @Index(columnList = "session_id", unique = true))
public class ToolChatSession {

    public static class Views {
        public interface List {
        }

        public interface Detail extends List {
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonView(Views.List.class)
    private Long id;

    @Column(name = "session_id", unique = true, nullable = false)
    @JsonView(Views.List.class)
    private String sessionId;

    /** 会话标题（取首条用户消息前 30 字） */
    @JsonView(Views.List.class)
    private String title;

    @JsonView(Views.List.class)
    private LocalDateTime createdAt = LocalDateTime.now();

    @JsonView(Views.List.class)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @OneToMany(mappedBy = "toolSession", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    @JsonView(Views.Detail.class)
    private List<ToolChatMessage> messages = new ArrayList<>();

    // Getters & Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<ToolChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ToolChatMessage> messages) {
        this.messages = messages;
    }
}
