package com.interview.assistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.assistant.entity.MockInterviewDialogue;
import com.interview.assistant.entity.MockInterviewReport;
import com.interview.assistant.entity.MockInterviewSession;
import com.interview.assistant.repository.MockInterviewDialogueRepository;
import com.interview.assistant.repository.MockInterviewReportRepository;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 面试量化评估报告生成服务：面试结束后根据完整对话生成多维度评分报告
 */
@Service
public class MockInterviewReportService {

    private static final Logger log = LoggerFactory.getLogger(MockInterviewReportService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ChatModel mockInterviewPlanModel;
    private final MockInterviewDialogueRepository dialogueRepo;
    private final MockInterviewReportRepository reportRepo;

    public MockInterviewReportService(@Qualifier("mockInterviewPlanModel") ChatModel mockInterviewPlanModel,
                                       MockInterviewDialogueRepository dialogueRepo,
                                       MockInterviewReportRepository reportRepo) {
        this.mockInterviewPlanModel = mockInterviewPlanModel;
        this.dialogueRepo = dialogueRepo;
        this.reportRepo = reportRepo;
    }

    private static final String REPORT_SYSTEM_PROMPT = """
            你是一位资深面试评估专家，负责根据模拟面试的完整对话记录，生成一份量化评估报告。

            你必须严格输出 JSON 格式，不要包含任何其他文字。JSON 结构如下：
            {
              "overallScore": 0-100的整数,
              "overallLevel": "等级（如 A/A-/B+/B/B-/C+/C）",
              "overallComment": "一段总评，100字以内",
              "dimensions": [
                { "name": "维度名", "score": 0-100, "comment": "评语" }
              ],
              "questionDetails": [
                {
                  "questionId": "问题ID",
                  "question": "面试官问的问题",
                  "candidateAnswer": "候选人的回答摘要（50字以内）",
                  "score": 1-10,
                  "feedback": "点评",
                  "suggestion": "改进建议"
                }
              ],
              "improvementPlan": ["建议1", "建议2", ...]
            }

            评分维度（必须包含）：
            1. 项目深度 — 是否清晰描述项目架构、自身职责、技术选型理由
            2. 实习产出 — 是否有量化成果、是否了解业务背景
            3. 八股理论 — 基础知识掌握程度
            4. 技术深度 — 是否理解底层原理、源码级理解
            5. 表达逻辑 — 回答是否有条理、是否简洁
            6. 应变能力 — 面对追问时的表现

            评分标准：
            - 90-100 A: 优秀，超出预期
            - 80-89 B+: 良好，基本达标
            - 70-79 B: 中等，有明显短板
            - 60-69 C+: 及格，需大幅提升
            - 60以下 C: 不及格

            只输出 JSON，不输出任何其他内容。
            """;

    /**
     * 根据完整对话记录生成量化评估报告
     */
    public MockInterviewReport generateReport(MockInterviewSession session) {
        List<MockInterviewDialogue> dialogues = dialogueRepo
                .findBySession_SessionIdOrderBySortOrderAsc(session.getSessionId());

        if (dialogues.isEmpty()) {
            log.warn("面试会话无对话记录, sessionId={}", session.getSessionId());
            return createEmptyReport(session);
        }

        String transcript = buildTranscript(dialogues);
        String prompt = buildReportPrompt(session, transcript);

        ChatResponse response = mockInterviewPlanModel.chat(
                SystemMessage.from(REPORT_SYSTEM_PROMPT),
                UserMessage.from(prompt)
        );
        String rawJson = response.aiMessage().text();
        String reportJson = extractJson(rawJson);

        try {
            JsonNode root = objectMapper.readTree(reportJson);
            int overallScore = root.path("overallScore").asInt(0);
            String overallLevel = root.path("overallLevel").asText("N/A");

            MockInterviewReport report = getOrCreateReport(session);
            report.setOverallScore(overallScore);
            report.setOverallLevel(overallLevel);
            report.setReportJson(reportJson);
            return reportRepo.save(report);
        } catch (JsonProcessingException e) {
            log.error("评估报告 JSON 解析失败: {}", rawJson, e);
            return createFallbackReport(session, rawJson);
        }
    }

    private String buildTranscript(List<MockInterviewDialogue> dialogues) {
        return dialogues.stream()
                .map(d -> {
                    String roleLabel = "interviewer".equals(d.getRole()) ? "面试官" : "候选人";
                    String phase = d.getPhaseType() != null ? " [" + d.getPhaseType() + "]" : "";
                    String followUp = d.isFollowUp() ? " (追问)" : "";
                    return roleLabel + phase + followUp + "：" + d.getContent();
                })
                .collect(Collectors.joining("\n\n"));
    }

    private String buildReportPrompt(MockInterviewSession session, String transcript) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是一场模拟面试的完整对话记录：\n\n");
        sb.append("目标公司：").append(session.getCompany() != null ? session.getCompany() : "通用");
        sb.append(" / 目标部门：").append(session.getDepartment() != null ? session.getDepartment() : "技术");
        sb.append("\n\n---\n\n");
        // 限制 transcript 长度避免超过 token 限制
        String t = transcript.length() > 8000 ? transcript.substring(0, 8000) + "\n\n...(对话记录已截断)" : transcript;
        sb.append(t);
        sb.append("\n\n---\n\n请根据以上对话生成量化评估报告（JSON 格式）。");
        return sb.toString();
    }

    private MockInterviewReport getOrCreateReport(MockInterviewSession session) {
        MockInterviewReport report = reportRepo.findBySession_SessionId(session.getSessionId())
                .orElse(new MockInterviewReport());
        report.setSession(session);
        return report;
    }

    private MockInterviewReport createEmptyReport(MockInterviewSession session) {
        MockInterviewReport report = getOrCreateReport(session);
        report.setOverallScore(0);
        report.setOverallLevel("N/A");
        report.setReportJson("{\"overallScore\":0,\"overallLevel\":\"N/A\",\"overallComment\":\"无对话记录\",\"dimensions\":[],\"questionDetails\":[],\"improvementPlan\":[]}");
        return reportRepo.save(report);
    }

    private MockInterviewReport createFallbackReport(MockInterviewSession session, String rawText) {
        MockInterviewReport report = getOrCreateReport(session);
        report.setOverallScore(0);
        report.setOverallLevel("N/A");
        report.setReportJson("{\"overallScore\":0,\"overallLevel\":\"N/A\",\"overallComment\":\"报告生成异常，原始文本已保存\",\"rawText\":" +
                objectMapper.valueToTree(rawText) + ",\"dimensions\":[],\"questionDetails\":[],\"improvementPlan\":[]}");
        return reportRepo.save(report);
    }

    private String extractJson(String raw) {
        if (raw == null) return "{}";
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastBacktick = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastBacktick > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastBacktick).trim();
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }
}
