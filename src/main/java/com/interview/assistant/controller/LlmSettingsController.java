package com.interview.assistant.controller;

import com.interview.assistant.security.TenantContext;
import com.interview.assistant.service.LlmRouter;
import com.interview.assistant.service.LlmSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 大模型配置：每个租户可以填自己的 DeepSeek / 智谱 Key，不填则用部署级默认。
 */
@RestController
@RequestMapping("/api/settings/llm")
@CrossOrigin
public class LlmSettingsController {

    private static final Logger log = LoggerFactory.getLogger(LlmSettingsController.class);

    private final LlmSettingsService settingsService;
    private final LlmRouter llmRouter;

    public LlmSettingsController(LlmSettingsService settingsService, LlmRouter llmRouter) {
        this.settingsService = settingsService;
        this.llmRouter = llmRouter;
    }

    @GetMapping
    public ResponseEntity<?> get() {
        return ResponseEntity.ok(settingsService.describe(TenantContext.requireTenantId()));
    }

    @PutMapping
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body) {
        try {
            Long tenantId = TenantContext.requireTenantId();
            Map<String, Object> result = settingsService.save(tenantId, body);
            llmRouter.invalidate();
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping
    public ResponseEntity<?> reset() {
        Long tenantId = TenantContext.requireTenantId();
        settingsService.reset(tenantId);
        llmRouter.invalidate();
        return ResponseEntity.ok(settingsService.describe(tenantId));
    }

    /**
     * 连通性测试：优先请求 /models，失败再用 1 个 token 的对话探活。
     * 不会保存配置，方便先测再存。
     */
    @PostMapping("/test")
    public ResponseEntity<?> test(@RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.requireTenantId();
        LlmSettingsService.ResolvedLlm current = settingsService.resolve(tenantId);

        String provider = str(body.get("provider"), current.provider());
        String baseUrl = str(body.get("baseUrl"), LlmSettingsService.defaultBaseUrl(provider));
        String apiKey = str(body.get("apiKey"), null);
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = current.apiKey();
        }
        String chatModel = str(body.get("chatModel"), current.chatModel());

        if (baseUrl == null || !baseUrl.startsWith("http")) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Base URL 需要以 http(s):// 开头"));
        }
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "请先填写 API Key"));
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        RestClient client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();

        try {
            String models = client.get().uri("/models")
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(String.class);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("mode", "models");
            result.put("message", "连接成功");
            result.put("models", models != null && models.length() > 2000 ? models.substring(0, 2000) : models);
            return ResponseEntity.ok(result);
        } catch (Exception modelsError) {
            log.info("模型列表接口不可用，改用对话探活: {}", modelsError.getMessage());
        }

        try {
            Map<String, Object> payload = Map.of(
                    "model", chatModel,
                    "messages", java.util.List.of(Map.of("role", "user", "content", "ping")),
                    "max_tokens", 1,
                    "stream", false
            );
            String reply = client.post().uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("mode", "chat");
            result.put("message", "连接成功（模型 " + chatModel + "）");
            result.put("sample", reply != null && reply.length() > 500 ? reply.substring(0, 500) : reply);
            return ResponseEntity.ok(result);
        } catch (Exception chatError) {
            String message = chatError.getMessage() != null ? chatError.getMessage() : chatError.getClass().getSimpleName();
            return ResponseEntity.ok(Map.of("ok", false, "error", "连接失败：" + message));
        }
    }

    private static String str(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? fallback : s;
    }
}
