package com.interview.assistant.controller;

import com.interview.assistant.config.McpConfig.AiCodeHelperService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.annotation.Resource; // Spring Boot 3

@RestController
@RequestMapping("/api/mcp")
public class McpTestController {

    @Resource
    private AiCodeHelperService aiCodeHelperService;

    @GetMapping("/test")
    public String testMcp(@RequestParam String prompt) {
        // 请求发给 aiCodeHelperService，如果 prompt 触发了对应条件，大模型会自动调用 MCP 的工具
        return aiCodeHelperService.chat(prompt);
    }
}
