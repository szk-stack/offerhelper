package com.interview.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告 JSON 解析/修复的单元测试：模型返回的格式五花八门，这里覆盖线上真实出现过的几类脏数据。
 */
class MockInterviewReportParserTest {

    private final MockInterviewReportService service =
            new MockInterviewReportService(null, null, null, null, null);

    private static final String VALID = """
            {"overallScore": 78, "overallLevel": "B+", "overallComment": "ok",
             "dimensions": [{"name": "项目深度", "score": 85, "comment": "清晰"}],
             "questionDetails": [{"questionId": "q1", "question": "自我介绍", "score": 7}],
             "improvementPlan": ["多准备 STAR"]}
            """;

    @Test
    void parsesPlainJson() {
        JsonNode node = service.parseLenient(VALID);
        assertNotNull(node);
        assertEquals(78, node.path("overallScore").asInt());
        assertEquals(1, node.path("dimensions").size());
    }

    @Test
    void stripsMarkdownFence() {
        String raw = "```json\n" + VALID + "\n```";
        JsonNode node = service.parseLenient(raw);
        assertNotNull(node);
        assertEquals("B+", node.path("overallLevel").asText());
    }

    @Test
    void stripsSurroundingProse() {
        String raw = "以下是评估结果：\n" + VALID + "\n希望有帮助。";
        JsonNode node = service.parseLenient(raw);
        assertNotNull(node);
        assertEquals(78, node.path("overallScore").asInt());
        assertEquals(1, node.path("questionDetails").size());
    }

    @Test
    void repairsTruncatedJson() {
        // 模型输出被 maxTokens 截断：没有收尾的括号
        String raw = "```json\n{\"overallScore\": 38, \"overallLevel\": \"C\", "
                + "\"dimensions\": [{\"name\": \"项目深度\", \"score\": 25, \"comment\": \"停留在表面\"}";
        JsonNode node = service.parseLenient(raw);
        assertNotNull(node);
        assertEquals(38, node.path("overallScore").asInt());
        assertEquals(1, node.path("dimensions").size());
    }

    @Test
    void repairsTruncatedInsideString() {
        String raw = "{\"overallScore\": 55, \"overallLevel\": \"C+\", \"overallComment\": \"表达还算清楚，但是";
        JsonNode node = service.parseLenient(raw);
        assertNotNull(node);
        assertEquals(55, node.path("overallScore").asInt());
    }

    @Test
    void toleratesTrailingComma() {
        String raw = "{\"overallScore\": 66, \"dimensions\": [{\"name\": \"八股\", \"score\": 60},],}";
        JsonNode node = service.parseLenient(raw);
        assertNotNull(node);
        assertEquals(66, node.path("overallScore").asInt());
        assertEquals(1, node.path("dimensions").size());
    }

    @Test
    void returnsNullForGarbage() {
        assertTrue(service.parseLenient("抱歉，我无法生成报告。") == null);
        assertTrue(service.parseLenient(null) == null);
    }
}
