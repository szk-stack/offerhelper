package com.interview.assistant.service;

import com.interview.assistant.entity.LlmSettings;
import com.interview.assistant.repository.LlmSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型配置解析：
 * 1. 租户在「模型设置」里填了自己的 Key，就用租户的；
 * 2. 否则回落到部署级配置（application.yml / 环境变量）；
 * 3. 都没有时抛出可读错误，由接口层转成提示，而不是启动失败。
 */
@Service
public class LlmSettingsService {

    private static final Logger log = LoggerFactory.getLogger(LlmSettingsService.class);
    public static final String ZHIPU_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";
    public static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";

    /** API Key 的占位符：前端提交它表示「清除已保存的 Key」 */
    public static final String CLEAR_KEY = "__CLEAR__";

    private final LlmSettingsRepository repository;

    @Value("${zhipu.apiKey:}")
    private String globalApiKey;

    @Value("${zhipu.model:glm-4-flash}")
    private String globalModel;

    @Value("${zhipu.mockInterviewPlanModel:glm-4-plus}")
    private String globalPlanModel;

    @Value("${zhipu.mockInterviewChatModel:glm-4-flash}")
    private String globalChatModel;

    @Value("${zhipu.visionModel:glm-4v-plus}")
    private String globalVisionModel;

    public LlmSettingsService(LlmSettingsRepository repository) {
        this.repository = repository;
    }

    public record ResolvedLlm(String provider, String baseUrl, String apiKey,
                              String chatModel, String planModel, String visionModel) {

        public boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        public String modelFor(LlmPurpose purpose) {
            return switch (purpose) {
                case PLAN -> firstNonBlank(planModel, chatModel);
                case VISION -> visionModel;
                default -> chatModel;
            };
        }

        public String fingerprint(LlmPurpose purpose) {
            return provider + "|" + baseUrl + "|" + Integer.toHexString(apiKey == null ? 0 : apiKey.hashCode())
                    + "|" + modelFor(purpose);
        }

        private static String firstNonBlank(String a, String b) {
            return (a != null && !a.isBlank()) ? a : b;
        }
    }

    private record ProviderDefaults(String baseUrl, String chatModel, String planModel, String visionModel) {
    }

    private static final Map<String, ProviderDefaults> PROVIDERS = Map.of(
            // deepseek-flash = DeepSeek-V4.1-Flash，原生多模态，图片和文本共用同一个模型
            "deepseek", new ProviderDefaults(DEEPSEEK_BASE_URL, "deepseek-flash", "deepseek-flash", "deepseek-flash"),
            "zhipu", new ProviderDefaults(ZHIPU_BASE_URL, "glm-4-flash", "glm-4-plus", "glm-4v-plus"),
            "custom", new ProviderDefaults("", "", "", null)
    );

    public static List<String> supportedProviders() {
        return List.of("deepseek", "zhipu", "custom");
    }

    public static String defaultBaseUrl(String provider) {
        ProviderDefaults d = PROVIDERS.get(provider);
        return d != null ? d.baseUrl() : "";
    }

    /**
     * 解析某个租户当前生效的模型配置。
     *
     * @param tenantId 为 null 时只用部署级配置
     */
    public ResolvedLlm resolve(Long tenantId) {
        if (tenantId != null) {
            LlmSettings saved = repository.findByTenantId(tenantId).orElse(null);
            if (saved != null && saved.getApiKey() != null && !saved.getApiKey().isBlank()) {
                return fromSettings(saved);
            }
        }
        return global();
    }

    private ResolvedLlm global() {
        String key = (globalApiKey != null && !globalApiKey.isBlank())
                ? globalApiKey : System.getenv("ZHIPU_API_KEY");
        return new ResolvedLlm("zhipu", ZHIPU_BASE_URL, key,
                blankTo(globalChatModel, globalModel), globalPlanModel, globalVisionModel);
    }

    private ResolvedLlm fromSettings(LlmSettings s) {
        String provider = s.getProvider() != null ? s.getProvider() : "custom";
        ProviderDefaults d = PROVIDERS.getOrDefault(provider, PROVIDERS.get("custom"));
        String baseUrl = blankTo(s.getBaseUrl(), d.baseUrl());
        String chat = blankTo(s.getChatModel(), blankTo(d.chatModel(), "deepseek-flash"));
        String plan = blankTo(s.getPlanModel(), chat);
        String vision = blankTo(s.getVisionModel(), d.visionModel());
        return new ResolvedLlm(provider, baseUrl, s.getApiKey(), chat, plan, vision);
    }

    /** 设置页展示用：Key 只回显掩码 */
    public Map<String, Object> describe(Long tenantId) {
        LlmSettings saved = tenantId == null ? null : repository.findByTenantId(tenantId).orElse(null);
        ResolvedLlm effective = resolve(tenantId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("usingSystemDefault", saved == null || saved.getApiKey() == null || saved.getApiKey().isBlank());
        result.put("provider", saved != null ? saved.getProvider() : effective.provider());
        result.put("baseUrl", saved != null ? saved.getBaseUrl() : effective.baseUrl());
        result.put("chatModel", saved != null ? saved.getChatModel() : effective.chatModel());
        result.put("planModel", saved != null ? saved.getPlanModel() : effective.planModel());
        result.put("visionModel", saved != null ? saved.getVisionModel() : effective.visionModel());
        boolean hasOwnKey = saved != null && saved.getApiKey() != null && !saved.getApiKey().isBlank();
        result.put("hasApiKey", hasOwnKey);
        // 只回显租户自己填的 Key 掩码，系统默认 Key 不对外暴露
        result.put("apiKeyMasked", hasOwnKey ? mask(saved.getApiKey()) : "");
        result.put("providers", supportedProviders());
        result.put("presets", Map.of(
                "deepseek", Map.of("baseUrl", DEEPSEEK_BASE_URL, "chatModel", "deepseek-flash",
                        "planModel", "deepseek-flash", "visionModel", "deepseek-flash"),
                "zhipu", Map.of("baseUrl", ZHIPU_BASE_URL, "chatModel", "glm-4-flash",
                        "planModel", "glm-4-plus", "visionModel", "glm-4v-plus")
        ));
        result.put("effectiveProvider", effective.provider());
        result.put("effectiveModel", effective.modelFor(LlmPurpose.CHAT));
        result.put("configured", effective.hasKey());
        return result;
    }

    @Transactional
    public Map<String, Object> save(Long tenantId, Map<String, Object> body) {
        if (tenantId == null) {
            throw new IllegalStateException("请先登录");
        }
        String provider = str(body.get("provider"), "deepseek");
        if (!PROVIDERS.containsKey(provider)) {
            throw new IllegalArgumentException("不支持的模型供应商: " + provider);
        }
        String baseUrl = str(body.get("baseUrl"), defaultBaseUrl(provider));
        if (baseUrl == null || !baseUrl.startsWith("http")) {
            throw new IllegalArgumentException("Base URL 需要以 http(s):// 开头");
        }

        LlmSettings saved = repository.findByTenantId(tenantId).orElseGet(LlmSettings::new);
        saved.setTenantId(tenantId);
        saved.setProvider(provider);
        saved.setBaseUrl(baseUrl.trim());

        Object keyRaw = body.get("apiKey");
        String key = keyRaw == null ? null : keyRaw.toString().trim();
        if (CLEAR_KEY.equals(key)) {
            saved.setApiKey(null);
        } else if (key != null && !key.isBlank()) {
            saved.setApiKey(key);
        }

        ProviderDefaults d = PROVIDERS.getOrDefault(provider, PROVIDERS.get("custom"));
        saved.setChatModel(str(body.get("chatModel"), blankTo(saved.getChatModel(), d.chatModel())));
        saved.setPlanModel(str(body.get("planModel"), blankTo(saved.getPlanModel(), d.planModel())));
        saved.setVisionModel(str(body.get("visionModel"), blankTo(saved.getVisionModel(), d.visionModel())));
        saved.setUpdatedAt(LocalDateTime.now());
        repository.save(saved);
        log.info("租户 {} 更新了大模型配置: provider={}, baseUrl={}", tenantId, provider, baseUrl);
        return describe(tenantId);
    }

    @Transactional
    public void reset(Long tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("请先登录");
        }
        repository.findByTenantId(tenantId).ifPresent(repository::delete);
    }

    public static String mask(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private static String blankTo(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    private static String str(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? fallback : s;
    }
}
