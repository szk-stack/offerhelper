package com.interview.assistant.config;

import com.interview.assistant.service.LlmPurpose;
import com.interview.assistant.service.LlmRouter;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 模型 Bean 定义。
 * <p>
 * 这里不再在启动时写死 API Key 和模型名，而是返回 {@link LlmRouter} 的代理对象：
 * 真正用哪个供应商、哪个模型，在每次调用时根据「当前租户配置 → 部署级配置」解析，
 * 因此在网页上保存 DeepSeek / 智谱的 Key 后立即生效，且不影响已有注入点。
 */
@Configuration
public class LlmConfig {

    private final LlmRouter llmRouter;

    public LlmConfig(LlmRouter llmRouter) {
        this.llmRouter = llmRouter;
    }

    /** 主模型：通用对话 */
    @Bean
    @Primary
    public ChatModel chatModel() {
        return llmRouter.chat(LlmPurpose.GENERAL);
    }

    /** 深挖问题生成 */
    @Bean("questionChatModel")
    public ChatModel questionChatModel() {
        return llmRouter.chat(LlmPurpose.PLAN);
    }

    /** 面试对话 / 答疑 */
    @Bean("interviewChatModel")
    public ChatModel interviewChatModel() {
        return llmRouter.chat(LlmPurpose.CHAT);
    }

    /** 面试复盘 */
    @Bean("replayChatModel")
    public ChatModel replayChatModel() {
        return llmRouter.chat(LlmPurpose.PLAN);
    }

    /** 模拟面试：计划生成与报告 */
    @Bean("mockInterviewPlanModel")
    public ChatModel mockInterviewPlanModel() {
        return llmRouter.chat(LlmPurpose.PLAN);
    }

    /** 模拟面试：对话交互 */
    @Bean("mockInterviewChatModel")
    public ChatModel mockInterviewChatModel() {
        return llmRouter.chat(LlmPurpose.CHAT);
    }

    /** 模拟面试：流式输出 */
    @Bean("mockInterviewStreamingChatModel")
    public StreamingChatModel mockInterviewStreamingChatModel() {
        return llmRouter.streaming(LlmPurpose.CHAT);
    }
}
