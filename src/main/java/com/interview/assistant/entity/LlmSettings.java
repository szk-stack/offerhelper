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

    // ── 语音识别（ASR）配置，和大模型配置放在同一行，设置页两个分区 ──

    /** siliconflow / dashscope / tencent / custom */
    @Column(length = 20)
    private String asrProvider;

    /** Bearer Key；腾讯云存 "SecretId:SecretKey" */
    @JsonIgnore
    @Column(length = 400)
    private String asrApiKey;

    @Column(length = 300)
    private String asrBaseUrl;

    @Column(length = 100)
    private String asrModel;

    /** 热词表，逗号分隔，最多 128 个（腾讯云上限） */
    @Column(length = 1200)
    private String asrHotwords;

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

    public String getAsrProvider() {
        return asrProvider;
    }

    public void setAsrProvider(String asrProvider) {
        this.asrProvider = asrProvider;
    }

    public String getAsrApiKey() {
        return asrApiKey;
    }

    public void setAsrApiKey(String asrApiKey) {
        this.asrApiKey = asrApiKey;
    }

    public String getAsrBaseUrl() {
        return asrBaseUrl;
    }

    public void setAsrBaseUrl(String asrBaseUrl) {
        this.asrBaseUrl = asrBaseUrl;
    }

    public String getAsrModel() {
        return asrModel;
    }

    public void setAsrModel(String asrModel) {
        this.asrModel = asrModel;
    }

    public String getAsrHotwords() {
        return asrHotwords;
    }

    public void setAsrHotwords(String asrHotwords) {
        this.asrHotwords = asrHotwords;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
