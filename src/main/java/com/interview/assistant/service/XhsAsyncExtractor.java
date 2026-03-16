package com.interview.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.interview.assistant.entity.InterviewExperience;
import com.interview.assistant.repository.InterviewExperienceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 独立的异步提取服务。
 * 
 * 【关键设计】Spring 的 @Async 代理机制要求：调用方和被调用方必须是不同的 Bean。
 * 如果在同一个类内部调用 @Async 方法（自调用），Spring AOP 代理不会拦截，
 * 导致方法仍然在调用线程上同步执行。
 * 
 * 因此将异步逻辑从 XhsSpiderService 中拆出到此类，
 * 由 XhsSpiderService 注入并调用，确保 @Async 真正生效。
 * 
 * 两个 Bean 互相引用，使用 @Lazy setter 注入打破循环依赖。
 */
@Service
public class XhsAsyncExtractor {

    private static final Logger log = LoggerFactory.getLogger(XhsAsyncExtractor.class);

    @Value("${xhs-spider.api-url:http://localhost:8686}")
    private String spiderApiUrl;

    private final InterviewExperienceRepository experienceRepository;
    private final RagService ragService;

    // 使用 @Lazy setter 注入打破循环依赖
    private XhsSpiderService spiderService;

    public XhsAsyncExtractor(InterviewExperienceRepository experienceRepository,
            RagService ragService) {
        this.experienceRepository = experienceRepository;
        this.ragService = ragService;
    }

    @Autowired
    public void setSpiderService(@org.springframework.context.annotation.Lazy XhsSpiderService spiderService) {
        this.spiderService = spiderService;
    }

    /**
     * 真正的异步方法：后台逐条获取笔记详情 → LLM 提取 → 存库 → 建索引。
     * 因为在独立 Bean 中，Spring 代理可以正常拦截，运行在 async 线程池。
     */
    @Async("taskExecutor")
    public void asyncExtractAndSave(List<Map<String, Object>> notes) {
        log.info("🔥 [async-{}] 开始异步提取 {} 条笔记...", Thread.currentThread().getName(), notes.size());
        int count = 0;
        for (Map<String, Object> note : notes) {
            try {
                String noteUrl = (String) note.getOrDefault("note_url", "");
                if (noteUrl == null || noteUrl.isEmpty())
                    continue;

                // 1. 调 Python 端获取笔记正文
                var res = WebClient.builder().baseUrl(spiderApiUrl).build()
                        .get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/api/note")
                                .queryParam("url", noteUrl)
                                .build())
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block(Duration.ofMinutes(1));

                if (res != null && res.path("success").asBoolean()) {
                    JsonNode data = res.get("data");
                    String title = data.path("title").asText();
                    String desc = data.path("desc").asText();

                    // 2. LLM 提取
                    InterviewExperience exp = spiderService.extractInterviewFromNote(title, desc, noteUrl);
                    if (exp != null) {
                        experienceRepository.save(exp);
                        indexExperience(exp);
                        count++;
                    }
                }
            } catch (Exception e) {
                log.warn("❌ 异步提取笔记失败 [{}]: {}", note.get("title"), e.getMessage());
            }
        }
        log.info("🏁 [async-{}] 异步提取完成，新增 {} 条面经。", Thread.currentThread().getName(), count);
    }

    private void indexExperience(InterviewExperience exp) {
        try {
            ragService.indexExperiencesAsync(List.of(exp));
            log.info("🔍 RAG 索引已建立: {}", exp.getCompany());
        } catch (Exception e) {
            log.error("❌ RAG 索引失败: {}", e.getMessage());
        }
    }
}
