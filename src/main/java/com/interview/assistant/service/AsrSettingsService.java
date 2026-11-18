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
 * 语音识别（ASR）配置解析。结构和 {@link LlmSettingsService} 一致：租户自己填了 Key 用租户的，
 * 否则回落到部署级配置，都没有时给出可读错误而不是启动失败。
 *
 * 支持四家：
 * - siliconflow：硅基流动，SenseVoiceSmall 免费，OpenAI 兼容 /audio/transcriptions
 * - dashscope：阿里百炼 Qwen3-ASR，原生 multimodal-generation 协议
 * - tencent：腾讯云一句话识别，TC3-HMAC-SHA256 签名，热词表效果最好
 * - custom：任意 OpenAI 兼容的 /audio/transcriptions
 */
@Service
public class AsrSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AsrSettingsService.class);

    public static final String SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1";
    public static final String DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/api/v1";
    public static final String TENCENT_BASE_URL = "https://asr.tencentcloudapi.com";

    private final LlmSettingsRepository repository;

    @Value("${asr.apiKey:}")
    private String globalApiKey;

    @Value("${asr.provider:siliconflow}")
    private String globalProvider;

    @Value("${asr.baseUrl:}")
    private String globalBaseUrl;

    @Value("${asr.model:}")
    private String globalModel;

    @Value("${asr.hotwords:}")
    private String globalHotwords;

    public AsrSettingsService(LlmSettingsRepository repository) {
        this.repository = repository;
    }

    public record ResolvedAsr(String provider, String baseUrl, String apiKey, String model, String hotwords) {

        public boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** 腾讯云需要 SecretId 和 SecretKey 两段，约定用冒号分隔 */
        public String[] tencentCredentials() {
            if (apiKey == null || apiKey.isBlank()) {
                return null;
            }
            int idx = apiKey.indexOf(':');
            if (idx <= 0 || idx == apiKey.length() - 1) {
                return null;
            }
            return new String[]{apiKey.substring(0, idx).trim(), apiKey.substring(idx + 1).trim()};
        }

        public String fingerprint() {
            return provider + "|" + baseUrl + "|" + model + "|"
                    + Integer.toHexString(apiKey == null ? 0 : apiKey.hashCode());
        }
    }

    private record Preset(String baseUrl, String model, String keyHint) {
    }

    private static final Map<String, Preset> PRESETS = Map.of(
            "siliconflow", new Preset(SILICONFLOW_BASE_URL, "FunAudioLLM/SenseVoiceSmall",
                    "硅基流动 API Key（免费额度，sk- 开头）"),
            "dashscope", new Preset(DASHSCOPE_BASE_URL, "qwen3-asr-flash",
                    "阿里百炼 API Key（每月 10 小时免费）"),
            "tencent", new Preset(TENCENT_BASE_URL, "16k_zh_large",
                    "SecretId:SecretKey（冒号分隔，一句话识别每月 5000 次免费）"),
            "custom", new Preset("", "", "任意 OpenAI 兼容服务的 Key")
    );

    public static List<String> supportedProviders() {
        return List.of("siliconflow", "dashscope", "tencent", "custom");
    }

    public static String defaultBaseUrl(String provider) {
        Preset p = PRESETS.get(provider);
        return p != null ? p.baseUrl() : "";
    }

    public static String defaultModel(String provider) {
        Preset p = PRESETS.get(provider);
        return p != null ? p.model() : "";
    }

    public ResolvedAsr resolve(Long tenantId) {
        if (tenantId != null) {
            LlmSettings saved = repository.findByTenantId(tenantId).orElse(null);
            if (saved != null && saved.getAsrApiKey() != null && !saved.getAsrApiKey().isBlank()) {
                return fromSettings(saved);
            }
        }
        return global();
    }

    private ResolvedAsr global() {
        String provider = (globalProvider != null && !globalProvider.isBlank()) ? globalProvider : "siliconflow";
        String key = (globalApiKey != null && !globalApiKey.isBlank())
                ? globalApiKey : System.getenv("ASR_API_KEY");
        String baseUrl = (globalBaseUrl != null && !globalBaseUrl.isBlank())
                ? globalBaseUrl : defaultBaseUrl(provider);
        String model = (globalModel != null && !globalModel.isBlank())
                ? globalModel : defaultModel(provider);
        return new ResolvedAsr(provider, baseUrl, key, model, globalHotwords);
    }

    private ResolvedAsr fromSettings(LlmSettings s) {
        String provider = s.getAsrProvider() != null && !s.getAsrProvider().isBlank()
                ? s.getAsrProvider() : "siliconflow";
        String baseUrl = blankTo(s.getAsrBaseUrl(), defaultBaseUrl(provider));
        String model = blankTo(s.getAsrModel(), defaultModel(provider));
        return new ResolvedAsr(provider, baseUrl, s.getAsrApiKey(), model, s.getAsrHotwords());
    }

    /** 设置页展示：Key 只回显掩码，不下发原文 */
    public Map<String, Object> describe(Long tenantId) {
        LlmSettings saved = tenantId == null ? null : repository.findByTenantId(tenantId).orElse(null);
        ResolvedAsr effective = resolve(tenantId);
        boolean hasOwnKey = saved != null && saved.getAsrApiKey() != null && !saved.getAsrApiKey().isBlank();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("usingSystemDefault", !hasOwnKey);
        result.put("provider", saved != null && saved.getAsrProvider() != null
                ? saved.getAsrProvider() : effective.provider());
        result.put("baseUrl", saved != null ? blankTo(saved.getAsrBaseUrl(), defaultBaseUrl(effective.provider()))
                : effective.baseUrl());
        result.put("model", saved != null ? blankTo(saved.getAsrModel(), defaultModel(effective.provider()))
                : effective.model());
        result.put("hotwords", saved != null && saved.getAsrHotwords() != null
                ? saved.getAsrHotwords() : blankTo(effective.hotwords(), ""));
        result.put("hasApiKey", hasOwnKey);
        result.put("apiKeyMasked", hasOwnKey ? LlmSettingsService.mask(saved.getAsrApiKey()) : "");
        result.put("providers", supportedProviders());
        result.put("configured", effective.hasKey());

        Map<String, Object> presets = new LinkedHashMap<>();
        PRESETS.forEach((name, p) -> presets.put(name, Map.of(
                "baseUrl", p.baseUrl(),
                "model", p.model(),
                "keyHint", p.keyHint()
        )));
        result.put("presets", presets);
        result.put("effectiveProvider", effective.provider());
        result.put("effectiveModel", effective.model());
        return result;
    }

    @Transactional
    public Map<String, Object> save(Long tenantId, Map<String, Object> body) {
        if (tenantId == null) {
            throw new IllegalStateException("请先登录");
        }
        String provider = str(body.get("provider"), "siliconflow");
        if (!PRESETS.containsKey(provider)) {
            throw new IllegalArgumentException("不支持的语音识别服务商: " + provider);
        }
        String baseUrl = str(body.get("baseUrl"), defaultBaseUrl(provider));
        if (baseUrl == null || baseUrl.isBlank() || !baseUrl.startsWith("http")) {
            throw new IllegalArgumentException("Base URL 需要以 http(s):// 开头");
        }
        if ("tencent".equals(provider)) {
            String key = str(body.get("apiKey"), null);
            if (key != null && !LlmSettingsService.CLEAR_KEY.equals(key) && !key.contains(":")) {
                throw new IllegalArgumentException("腾讯云请按 SecretId:SecretKey 的格式填写");
            }
        }

        LlmSettings saved = repository.findByTenantId(tenantId).orElseGet(LlmSettings::new);
        saved.setTenantId(tenantId);
        saved.setAsrProvider(provider);
        saved.setAsrBaseUrl(baseUrl.trim());
        saved.setAsrModel(str(body.get("model"), defaultModel(provider)));

        Object keyRaw = body.get("apiKey");
        String key = keyRaw == null ? null : keyRaw.toString().trim();
        if (LlmSettingsService.CLEAR_KEY.equals(key)) {
            saved.setAsrApiKey(null);
        } else if (key != null && !key.isBlank()) {
            saved.setAsrApiKey(key);
        }

        String hotwords = str(body.get("hotwords"), "");
        saved.setAsrHotwords(trimHotwords(hotwords));
        saved.setUpdatedAt(LocalDateTime.now());
        repository.save(saved);
        log.info("租户 {} 更新了语音识别配置: provider={}, model={}", tenantId, provider, saved.getAsrModel());
        return describe(tenantId);
    }

    @Transactional
    public void reset(Long tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("请先登录");
        }
        repository.findByTenantId(tenantId).ifPresent(s -> {
            s.setAsrProvider(null);
            s.setAsrApiKey(null);
            s.setAsrBaseUrl(null);
            s.setAsrModel(null);
            s.setAsrHotwords(null);
            s.setUpdatedAt(LocalDateTime.now());
            repository.save(s);
        });
    }

    /** 统一热词分隔符，去掉空项，最多保留 128 个（腾讯云上限） */
    static String trimHotwords(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split("[,，;；\\n\\r\\t]+");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String p : parts) {
            String w = p.trim();
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(w);
            if (++count >= 128) {
                break;
            }
        }
        return sb.length() == 0 ? null : sb.toString();
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
