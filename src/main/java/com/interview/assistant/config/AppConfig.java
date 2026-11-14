package com.interview.assistant.config;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
// import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.util.List;

@Configuration
public class AppConfig implements WebMvcConfigurer {

    @Value("${rag.qdrant.host:localhost}")
    private String qdrantHost;

    @Value("${rag.qdrant.port:6334}")
    private int qdrantPort;

    @Value("${rag.qdrant.collection:interview_experiences}")
    private String qdrantCollection;

    @Value("${rag.embedding.api-key:}")
    private String embeddingApiKey;

    @Value("${rag.embedding.model:embedding-3}")
    private String embeddingModel;

    @Value("${rag.embedding.base-url:https://open.bigmodel.cn/api/paas/v4/}")
    private String embeddingBaseUrl;

    /** 生产部署：前端打包后放入 static，SPA 路由回退到 index.html */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        if (resourcePath.startsWith("api"))
                            return null;
                        Resource r = location.createRelative(resourcePath);
                        if (r.exists() && r.isReadable())
                            return r;
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    /**
     * Embedding 模型改为懒加载：没有配置 Key 时不再让整个应用启动失败，
     * 而是等到真正做向量检索时抛错，由 RagService 捕获后降级为空结果。
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return new LazyEmbeddingModel(embeddingApiKey, embeddingBaseUrl, embeddingModel);
    }

    static final class LazyEmbeddingModel implements EmbeddingModel {

        private final String apiKey;
        private final String baseUrl;
        private final String modelName;
        private volatile EmbeddingModel delegate;

        LazyEmbeddingModel(String apiKey, String baseUrl, String modelName) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.modelName = modelName;
        }

        private EmbeddingModel delegate() {
            if (delegate == null) {
                synchronized (this) {
                    if (delegate == null) {
                        String key = (apiKey != null && !apiKey.isBlank())
                                ? apiKey : System.getenv("ZHIPU_API_KEY");
                        if (key == null || key.isBlank()) {
                            throw new IllegalStateException("未配置 Embedding API Key，面经向量检索不可用");
                        }
                        delegate = OpenAiEmbeddingModel.builder()
                                .baseUrl(baseUrl)
                                .apiKey(key)
                                .modelName(modelName)
                                .build();
                    }
                }
            }
            return delegate;
        }

        @Override
        public Response<Embedding> embed(String text) {
            return delegate().embed(text);
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return delegate().embedAll(segments);
        }

        @Override
        public int dimension() {
            return delegate().dimension();
        }
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        // 为了方便测试模拟面试模块，暂时改用内存向量库，避免 Qdrant 连不上的报错
        return new InMemoryEmbeddingStore<>();
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
