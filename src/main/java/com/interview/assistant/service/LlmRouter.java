package com.interview.assistant.service;

import com.interview.assistant.security.TenantContext;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 模型路由：把「当前租户配置 → 真实模型实例」的解析放到调用时，
 * 这样在网页上改完 Key / Base URL / 模型名后立即生效，不需要重启。
 * <p>
 * 对外暴露的 ChatModel 是稳定的代理对象，可以被 AiServices 长期持有；
 * 真正的模型按配置指纹缓存，配置变更时整体失效。
 */
@Service
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    private final LlmSettingsService settingsService;

    private final Map<LlmPurpose, ChatModel> chatFacades = new EnumMap<>(LlmPurpose.class);
    private final Map<LlmPurpose, StreamingChatModel> streamFacades = new EnumMap<>(LlmPurpose.class);
    private final Map<String, ChatModel> chatCache = new ConcurrentHashMap<>();
    private final Map<String, StreamingChatModel> streamCache = new ConcurrentHashMap<>();

    /** 异步线程（报告生成、SSE）没有请求上下文，用它显式指定租户 */
    private final ThreadLocal<Long> tenantOverride = new ThreadLocal<>();

    public LlmRouter(LlmSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public ChatModel chat(LlmPurpose purpose) {
        return chatFacades.computeIfAbsent(purpose, p -> new RoutingChatModel(this, p));
    }

    public StreamingChatModel streaming(LlmPurpose purpose) {
        return streamFacades.computeIfAbsent(purpose, p -> new RoutingStreamingChatModel(this, p));
    }

    /** 配置变更后清空模型缓存 */
    public void invalidate() {
        chatCache.clear();
        streamCache.clear();
        log.info("大模型配置已变更，模型缓存已清空");
    }

    public <T> T withTenant(Long tenantId, Supplier<T> action) {
        Long previous = tenantOverride.get();
        if (tenantId != null) {
            tenantOverride.set(tenantId);
        }
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    public void withTenant(Long tenantId, Runnable action) {
        withTenant(tenantId, () -> {
            action.run();
            return null;
        });
    }

    private void restore(Long previous) {
        if (previous == null) {
            tenantOverride.remove();
        } else {
            tenantOverride.set(previous);
        }
    }

    Long currentTenantId() {
        Long override = tenantOverride.get();
        return override != null ? override : TenantContext.tenantId();
    }

    ChatModel resolveChat(LlmPurpose purpose) {
        LlmSettingsService.ResolvedLlm config = settingsService.resolve(currentTenantId());
        if (!config.hasKey()) {
            throw new IllegalStateException("尚未配置大模型 API Key，请到「模型设置」填写");
        }
        return chatCache.computeIfAbsent(config.fingerprint(purpose), key -> buildChat(config, purpose));
    }

    StreamingChatModel resolveStreaming(LlmPurpose purpose) {
        LlmSettingsService.ResolvedLlm config = settingsService.resolve(currentTenantId());
        if (!config.hasKey()) {
            throw new IllegalStateException("尚未配置大模型 API Key，请到「模型设置」填写");
        }
        return streamCache.computeIfAbsent(config.fingerprint(purpose), key -> buildStreaming(config, purpose));
    }

    private ChatModel buildChat(LlmSettingsService.ResolvedLlm config, LlmPurpose purpose) {
        String model = config.modelFor(purpose);
        log.info("构建模型实例: provider={}, baseUrl={}, model={}, purpose={}",
                config.provider(), config.baseUrl(), model, purpose);
        return OpenAiChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .modelName(model)
                .temperature(purpose == LlmPurpose.PLAN ? 0.3 : 0.6)
                .maxTokens(purpose == LlmPurpose.PLAN ? 8192 : 4096)
                .timeout(Duration.ofMinutes(3))
                .build();
    }

    private StreamingChatModel buildStreaming(LlmSettingsService.ResolvedLlm config, LlmPurpose purpose) {
        String model = config.modelFor(purpose);
        log.info("构建流式模型实例: provider={}, baseUrl={}, model={}, purpose={}",
                config.provider(), config.baseUrl(), model, purpose);
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .modelName(model)
                .temperature(purpose == LlmPurpose.PLAN ? 0.3 : 0.6)
                .timeout(Duration.ofMinutes(3))
                .build();
    }

    // ======================== 代理实现 ========================

    static final class RoutingChatModel implements ChatModel {

        private final LlmRouter router;
        private final LlmPurpose purpose;

        RoutingChatModel(LlmRouter router, LlmPurpose purpose) {
            this.router = router;
            this.purpose = purpose;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            return router.resolveChat(purpose).chat(request);
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return ChatRequestParameters.builder().build();
        }

        @Override
        public List<ChatModelListener> listeners() {
            return List.of();
        }

        @Override
        public ModelProvider provider() {
            return ModelProvider.OTHER;
        }

        @Override
        public Set<Capability> supportedCapabilities() {
            return Set.of();
        }
    }

    static final class RoutingStreamingChatModel implements StreamingChatModel {

        private final LlmRouter router;
        private final LlmPurpose purpose;

        RoutingStreamingChatModel(LlmRouter router, LlmPurpose purpose) {
            this.router = router;
            this.purpose = purpose;
        }

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            router.resolveStreaming(purpose).chat(request, handler);
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return ChatRequestParameters.builder().build();
        }

        @Override
        public List<ChatModelListener> listeners() {
            return List.of();
        }

        @Override
        public ModelProvider provider() {
            return ModelProvider.OTHER;
        }

        @Override
        public Set<Capability> supportedCapabilities() {
            return Set.of();
        }
    }
}
