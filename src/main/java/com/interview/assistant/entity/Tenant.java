package com.interview.assistant.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 租户（空间）：一个账号一个空间，未来可扩展成多成员。
 * 所有个人数据（面试、简历、投递、对话）都带 tenantId，实现行级隔离。
 */
@Entity
@Table(name = "tenants")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Tenant() {
    }

    public Tenant(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
