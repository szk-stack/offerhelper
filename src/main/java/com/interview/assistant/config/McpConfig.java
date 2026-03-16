package com.interview.assistant.config;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

/**
 * @author BNTang
 */
@Configuration
public class McpConfig {

        @Value("${zhipu.apiKey:}")
        private String apiKey;

        @Bean
        public McpToolProvider mcpToolProvider() {
                // // 1. 配置与 MCP 服务的通讯方式 (SSE)
                // McpTransport transport = new HttpMcpTransport.Builder()
                // .sseUrl("https://open.bigmodel.cn/api/mcp/web_search/sse?Authorization=" +
                // apiKey)
                // // 开启日志，方便调试查看交互细节
                // // .logRequests(true)
                // // .logResponses(true)
                // .build();
                // 创建连接本地爬虫脚本的 Stdio Transport
                McpTransport xhsTransport = new StdioMcpTransport.Builder()
                                // 注意这里：第一个元素是你的 python 执行器路径（如果是配好环境变量的，直接写 "python"）
                                // 第二个元素是你那个 mcp_server.py 的绝对路径
                                .command(List.of("python", "D:/project/Spider_XHS-master/mcp_server.py"))
                                .logEvents(true)
                                .build();
                // 这是连接爬虫的客户端
                McpClient xhsClient = new DefaultMcpClient.Builder()
                                .key("xhsSpiderClient")
                                .transport(xhsTransport)
                                .toolExecutionTimeout(Duration.ofSeconds(120))
                                .build();
                // 2. 创建 MCP 客户端
                // McpClient zhipuClient = new DefaultMcpClient.Builder()
                // .key("neoMcpClient")
                // .transport(transport)
                // .build();

                // 3. 从 MCP 客户端获取工具提供者
                return McpToolProvider.builder()
                                // mcpClients(List.of(zhipuClient, xhsClient))
                                .mcpClients(List.of(xhsClient)) // 注意这里传一个 List，包含两个 Client
                                .build();
        }

        // 1. 先定义好 AiCodeHelperService 接口（作为内部接口测试用）
        public interface AiCodeHelperService {
                String chat(String userMessage);
        }

        // 2. 注入项目现有的 ChatModel
        @Bean
        public AiCodeHelperService aiCodeHelperService(
                        ChatModel chatModel, // ✅ 使用被 @Primary 标注的智谱模型
                        McpToolProvider mcpToolProvider) {

                return AiServices.builder(AiCodeHelperService.class)
                                .chatModel(chatModel)
                                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                                .toolProvider(mcpToolProvider) // ✅ 注册刚刚配置好的 MCP 工具
                                // .contentRetriever(contentRetriever) // 🚫 测试 MCP 可先注释掉或者后续用 ragService
                                // .tools(new CnblogsArticleTool()) // 🚫 删掉项目中没有的工具
                                .build();
        }

}