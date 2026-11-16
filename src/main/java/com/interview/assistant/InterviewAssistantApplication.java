package com.interview.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class InterviewAssistantApplication {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(1);
        e.setMaxPoolSize(2);
        e.setQueueCapacity(100);
        e.setThreadNamePrefix("async-");
        e.initialize();
        return e;
    }

    /** 模拟面试的 SSE 流式回答：每个连接占一个线程，单独给一个池子 */
    @Bean(name = "interviewStreamExecutor")
    public Executor interviewStreamExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(2);
        e.setMaxPoolSize(6);
        e.setQueueCapacity(100);
        e.setThreadNamePrefix("mi-stream-");
        e.initialize();
        return e;
    }

    public static void main(String[] args) {
        SpringApplication.run(InterviewAssistantApplication.class, args);
    }
}
