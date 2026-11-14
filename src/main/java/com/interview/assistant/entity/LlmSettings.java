package com.interview.assistant.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 租户级大模型配置。没有配置时回落到部署级（application.yml / 环境变量）。
 */
@Entity
@Table(name = "llm_settings", uniqueConstraints = @UniqueConstraint(columnNames = "tenant_id"))
public class LlmSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** deepseek / zhipu / custom */
    @Column(length = 20, nullable = false)
    private String provider = "deepseek";

    @Column(length = 300)
    private String baseUrl;

    @JsonIgnore
    @Column(length = 300)
    private String apiKey;

    /** 对话/追问用的快速模型 */
    @Column(length = 100)
    private String chatModel;

    /** 计划、报告等结构化输出用的模型 */
    @Column(length = 100)
    private String planModel;

    /** 视觉模型（目前仅智谱支持） */
    @Column(length = 100)
    private String visionModel;

    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getChatModel() {
        return chatModel;
    }

    public void setChatModel(String chatModel) {
        this.chatModel = chatModel;
    }

    public String getPlanModel() {
        return planModel;
    }

    public void setPlanModel(String planModel) {
        this.planModel = planModel;
    }

    public String getVisionModel() {
        return visionModel;
    }

    public void setVisionModel(String visionModel) {
        this.visionModel = visionModel;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
