package com.interview.assistant.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * 使用智谱 GLM（OpenAI 兼容 API）
 * 智谱 API: https://open.bigmodel.cn/api/paas/v4
 */
@Configuration
public class LlmConfig {

    @Value("${zhipu.apiKey:}")
    private String apiKey;

    @Value("${zhipu.model:glm-4-flash}")
    private String model;

    @Value("${zhipu.questionModel:}")
    private String questionModel;

    @Value("${zhipu.chatModel:glm-4-flash}")
    private String chatModel;

    /** 复盘专用：纯文本分析，必须用 flash 等快速模型，不要用 glm-4.6v 等多模态模型 */
    @Value("${zhipu.replayModel:glm-4-flash}")
    private String replayModel;

    @Value("${zhipu.mockInterviewPlanModel:glm-5.1}")
    private String mockInterviewPlanModel;

    @Value("${zhipu.mockInterviewChatModel:GLM-4.7-FlashX}")
    private String mockInterviewChatModel;

    private static final String ZHIPU_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";

    @Bean
    @Primary
    public ChatModel chatModel() {
        String key = (apiKey != null && !apiKey.isEmpty()) ? apiKey : System.getenv("ZHIPU_API_KEY");
        if (key == null || key.isEmpty()) {
            throw new IllegalStateException(
                    "智谱 API Key 未配置。请设置环境变量 ZHIPU_API_KEY 或在 application.yml 中配置 zhipu.api-key");
        }
        return OpenAiChatModel.builder()
                .baseUrl(ZHIPU_BASE_URL)
                .apiKey(key)
                .modelName(model)
                .temperature(0.7)
                .maxTokens(4096)
                .timeout(Duration.ofMinutes(2))
                .build();
    }

    /** 深挖问题生成专用：可用更快的 flash 模型提速（questionModel 未配置则返回主模型） */
    @Bean("questionChatModel")
    public ChatModel questionChatModel(ChatModel chatLanguageModel) {
        String qModel = (questionModel != null && !questionModel.isBlank()) ? questionModel : model;
        if (qModel.equals(model)) {
            return chatLanguageModel;
        }
        String key = (apiKey != null && !apiKey.isEmpty()) ? apiKey : System.getenv("ZHIPU_API_KEY");
        return OpenAiChatModel.builder()
                .baseUrl(ZHIPU_BASE_URL)
                .apiKey(key)
                .modelName(qModel)
                .temperature(0.7)
                .maxTokens(8192)
                .timeout(Duration.ofMinutes(2))
                .build();
    }

    /** 面试对话专用：快速模型 + 更高 maxTokens 避免截断 */
    @Bean("interviewChatModel")
    public ChatModel interviewChatModel() {
        String key = (apiKey != null && !apiKey.isEmpty()) ? apiKey : System.getenv("ZHIPU_API_KEY");
        String cm = (chatModel != null && !chatModel.isBlank()) ? chatModel : "glm-4-flash";
        return OpenAiChatModel.builder()
                .baseUrl(ZHIPU_BASE_URL)
                .apiKey(key)
                .modelName(cm)
                .temperature(0.7)
                .maxTokens(4096)
                .timeout(Duration.ofMinutes(2))
                .logRequests(true) // 打印 LLM 请求（含 System Prompt + 用户消息 + 工具定义）
                .logResponses(true) // 打印 LLM 响应（含模型选择调用哪个工具 + 最终回答）
                .build();
    }

    /**
     * 面试复盘专用：纯文本分析，固定用 glm-4-flash 等快速模型（不用主 model，主 model 可能是 glm-4.6v 等多模态慢模型）
     */
    @Bean("replayChatModel")
    public ChatModel replayChatModel() {
        String key = (apiKey != null && !apiKey.isEmpty()) ? apiKey : System.getenv("ZHIPU_API_KEY");
        String rm = (replayModel != null && !replayModel.isBlank()) ? replayModel : "glm-4-flash";
        return OpenAiChatModel.builder()
                .baseUrl(ZHIPU_BASE_URL)
                .apiKey(key)
                .modelName(rm)
                .temperature(0.5)
                .maxTokens(8192)
                .timeout(Duration.ofMinutes(2))
                .build();
    }

    /**
     * 模拟面试官 - 规划与报告生成专用：
     * 需要生成大量结构化 JSON，使用最好的模型（如 glm-5.1），保证结构稳定性。
     */
    @Bean("mockInterviewPlanModel")
    public ChatModel mockInterviewPlanModel() {
        String key = (apiKey != null && !apiKey.isEmpty()) ? apiKey : System.getenv("ZHIPU_API_KEY");
        String pm = (mockInterviewPlanModel != null && !mockInterviewPlanModel.isBlank()) ? mockInterviewPlanModel : "glm-5.1";
        return OpenAiChatModel.builder()
                .baseUrl(ZHIPU_BASE_URL)
                .apiKey(key)
                .modelName(pm)  // 使用配置的计划生成模型
                .temperature(0.5)
                .maxTokens(8192)
                .timeout(Duration.ofMinutes(2))
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    /**
     * 模拟面试官 - 对话交互专用：
     * 需要低耗时快速回复优化体验，使用闪电模型（如 GLM-4.7-FlashX）。
     */
    @Bean("mockInterviewChatModel")
    public ChatModel mockInterviewChatModel() {
        String key = (apiKey != null && !apiKey.isEmpty()) ? apiKey : System.getenv("ZHIPU_API_KEY");
        String cm = (mockInterviewChatModel != null && !mockInterviewChatModel.isBlank()) ? mockInterviewChatModel : "GLM-4.7-FlashX";
        return OpenAiChatModel.builder()
                .baseUrl(ZHIPU_BASE_URL)
                .apiKey(key)
                .modelName(cm)  // 使用配置的对话交互极速模型
                .temperature(0.6)
                .maxTokens(4096)
                .timeout(Duration.ofMinutes(3))
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    /**
     * 流式输出模型（SSE）：
     * 直接对接前端 SSE，优化用户体验（首字返回时间低于1秒）
     */
    @Bean("mockInterviewStreamingChatModel")
    public StreamingChatModel mockInterviewStreamingChatModel() {
        String key = (apiKey != null && !apiKey.isEmpty()) ? apiKey : System.getenv("ZHIPU_API_KEY");
        String cm = (mockInterviewChatModel != null && !mockInterviewChatModel.isBlank()) ? mockInterviewChatModel : "glm-4-flash";
        return OpenAiStreamingChatModel.builder()
                .baseUrl(ZHIPU_BASE_URL)
                .apiKey(key)
                .modelName(cm)  // 流式默认也用极速模型
                .temperature(0.6)
                .timeout(Duration.ofMinutes(3))
                .logRequests(true)
                .logResponses(true)
                .build();
    }
}
