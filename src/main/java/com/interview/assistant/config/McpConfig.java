package com.interview.assistant.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

/**
 * MCP 工具配置（小红书爬虫）。
 * <p>
 * MCP 属于可选能力：脚本不存在或启动失败时只跳过工具，不能把整个应用一起拖垮。
 * 之前这里写死了本机路径，换一台机器启动就会超时失败。
 */
@Configuration
public class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

    @Value("${zhipu.apiKey:}")
    private String apiKey;

    /** 是否启用小红书 MCP 工具 */
    @Value("${app.mcp.enabled:true}")
    private boolean mcpEnabled;

    /** python 解释器 */
    @Value("${app.mcp.xhs.python:python}")
    private String pythonCommand;

    /** 爬虫 MCP 服务脚本路径 */
    @Value("${app.mcp.xhs.server-script:D:/project/Spider_XHS-master/mcp_server.py}")
    private String xhsServerScript;

    @Bean
    public McpToolProvider mcpToolProvider() {
        List<McpClient> clients = new ArrayList<>();
        if (!mcpEnabled) {
            log.info("app.mcp.enabled=false，本次启动不加载 MCP 工具");
            return McpToolProvider.builder().mcpClients(clients).build();
        }

        try {
            if (!Files.isRegularFile(Path.of(xhsServerScript))) {
                log.warn("小红书 MCP 脚本不存在，跳过 MCP 工具: {}", xhsServerScript);
            } else {
                McpTransport xhsTransport = new StdioMcpTransport.Builder()
                        .command(List.of(pythonCommand, xhsServerScript))
                        .logEvents(true)
                        .build();
                clients.add(new DefaultMcpClient.Builder()
                        .key("xhsSpiderClient")
                        .transport(xhsTransport)
                        .toolExecutionTimeout(Duration.ofSeconds(120))
                        .build());
            }
        } catch (Exception e) {
            log.warn("MCP 客户端初始化失败，本次启动不加载 MCP 工具: {}", e.getMessage());
            clients.clear();
        }
        return McpToolProvider.builder().mcpClients(clients).build();
    }

    // 1. 先定义好 AiCodeHelperService 接口（作为内部接口测试用）
    public interface AiCodeHelperService {
        String chat(String userMessage);
    }

    // 2. 注入项目现有的 ChatModel
    @Bean
    public AiCodeHelperService aiCodeHelperService(
            ChatModel chatModel, // 使用被 @Primary 标注的智谱模型
            McpToolProvider mcpToolProvider) {

        return AiServices.builder(AiCodeHelperService.class)
                .chatModel(chatModel)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .toolProvider(mcpToolProvider) // 注册 MCP 工具（可能为空）
                .build();
    }

}
