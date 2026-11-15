package com.interview.assistant.config;

import com.interview.assistant.config.McpConfig.AiCodeHelperService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import jakarta.annotation.Resource;

@Disabled("依赖本地 MCP 脚本、外部网络和大模型 Key，不适合在 CI 中自动执行")
@SpringBootTest
public class McpTest {

    @Resource
    private AiCodeHelperService aiCodeHelperService;

    @Test
    public void testMcpTools() {
        System.out.println("====== 开始测试 MCP 工具 ======");
        // 你可以根据你配置的 MCP 服务器能力修改这句 prompt。
        // 例如：如果挂载了天气工具，可以问：“请用工具查一下今天北京的天气”。
        // 这里默认发送一个通用请求来触发 MCP 链路。
        String prompt = "从小红书搜3条哈啰测开日常实习的面经";
        System.out.println("User Prompt: " + prompt);

        String response = aiCodeHelperService.chat(prompt);

        System.out.println("AI 响应: " + response);
        System.out.println("====== 测试结束 ======");
    }
}
