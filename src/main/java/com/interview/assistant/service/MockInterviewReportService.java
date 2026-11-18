package com.interview.assistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.assistant.entity.MockInterviewDialogue;
import com.interview.assistant.entity.MockInterviewReport;
import com.interview.assistant.entity.MockInterviewSession;
import com.interview.assistant.repository.MockInterviewDialogueRepository;
import com.interview.assistant.repository.MockInterviewReportRepository;
import com.interview.assistant.repository.MockInterviewSessionRepository;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 面试量化评估报告生成服务：面试结束后根据完整对话生成多维度评分报告。
 * <p>
 * 生成过程是异步的：会话结束后先落一条 GENERATING 的报告，前端轮询会话详情即可拿到结果；
 * 失败时保留原始文本并把状态置为 FAILED，前端可一键重新生成。
 */
@Service
public class MockInterviewReportService {

    private static final Logger log = LoggerFactory.getLogger(MockInterviewReportService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 单次送进模型的对话文本上限（字符），超出部分保留头尾，避免丢掉最后几题 */
    private static final int TRANSCRIPT_BUDGET = 24000;

    private final ChatModel mockInterviewPlanModel;
    private final MockInterviewDialogueRepository dialogueRepo;
    private final MockInterviewReportRepository reportRepo;
    private final MockInterviewSessionRepository sessionRepo;
    private final LlmRouter llmRouter;

    /** 防止同一场面试被并发重复生成 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public MockInterviewReportService(@Qualifier("mockInterviewPlanModel") ChatModel mockInterviewPlanModel,
                                      MockInterviewDialogueRepository dialogueRepo,
                                      MockInterviewReportRepository reportRepo,
                                      MockInterviewSessionRepository sessionRepo,
                                      LlmRouter llmRouter) {
        this.mockInterviewPlanModel = mockInterviewPlanModel;
        this.dialogueRepo = dialogueRepo;
        this.reportRepo = reportRepo;
        this.sessionRepo = sessionRepo;
        this.llmRouter = llmRouter;
    }

    private static final String REPORT_SYSTEM_PROMPT = """
            你是一位资深面试评估专家，负责根据模拟面试的完整对话记录，生成一份量化评估报告。

            你必须严格输出 JSON 格式，不要包含任何其他文字，不要使用 markdown 代码块。JSON 结构如下：
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
                  "suggestion": "改进建议",
                  "referenceAnswer": "这道题的标准参考答案（分点作答，200字以内）；练习模式下候选人已当场看过答案，可留空字符串"
                }
              ],
              "improvementPlan": ["建议1", "建议2"]
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

            要求：questionDetails 必须覆盖对话中出现的每一个主问题，不要只评第一题。
            只输出 JSON，不输出任何其他内容。
            """;

    /**
     * 异步生成报告。会话结束后调用，不阻塞 /end 请求。
     * <p>
     * 异步线程没有请求上下文，必须显式带上 tenantId 才能用对租户的模型配置。
     */
    @Async("taskExecutor")
    public void generateReportAsync(String sessionId, Long tenantId) {
        if (!inFlight.add(sessionId)) {
            log.info("报告正在生成中，忽略重复请求, sessionId={}", sessionId);
            return;
        }
        try {
            llmRouter.withTenant(tenantId, () -> {
                MockInterviewSession session = sessionRepo.findBySessionId(sessionId).orElse(null);
                if (session == null) {
                    log.warn("生成报告时会话不存在, sessionId={}", sessionId);
                    return;
                }
                try {
                    generateReport(session);
                } catch (Exception e) {
                    log.error("生成报告失败, sessionId={}", sessionId, e);
                    markFailed(session, "报告生成异常：" + e.getMessage(), null);
                }
            });
        } finally {
            inFlight.remove(sessionId);
        }
    }

    /**
     * 同步生成报告（供异步方法或重新生成接口调用）。
     */
    public MockInterviewReport generateReport(MockInterviewSession session) {
        List<MockInterviewDialogue> dialogues = dialogueRepo
                .findBySession_SessionIdOrderBySortOrderAsc(session.getSessionId());

        if (dialogues.isEmpty()) {
            log.warn("面试会话无对话记录, sessionId={}", session.getSessionId());
            return createEmptyReport(session);
        }

        MockInterviewReport report = getOrCreateReport(session);
        report.setStatus(MockInterviewReport.Status.GENERATING);
        report.setErrorMessage(null);
        reportRepo.save(report);

        String transcript = clipTranscript(buildTranscript(dialogues));
        String prompt = buildReportPrompt(session, transcript);

        String rawJson;
        try {
            ChatResponse response = mockInterviewPlanModel.chat(
                    SystemMessage.from(REPORT_SYSTEM_PROMPT + buildModeInstruction(session)),
                    UserMessage.from(prompt)
            );
            rawJson = response.aiMessage().text();
        } catch (Exception e) {
            log.error("调用模型生成报告失败, sessionId={}", session.getSessionId(), e);
            return markFailed(session, "模型调用失败：" + e.getMessage(), null);
        }

        JsonNode root = parseLenient(rawJson);
        if (root == null) {
            // 兜底：让模型把已经生成的半成品整理成合法 JSON，再解析一次
            log.warn("报告 JSON 解析失败，尝试让模型修复, sessionId={}", session.getSessionId());
            root = parseLenient(repairWithModel(rawJson));
        }
        if (root == null || !isValidReport(root)) {
            return markFailed(session, "模型返回的内容不是合法的报告 JSON（可能是输出被截断）", rawJson);
        }

        try {
            report.setOverallScore(root.path("overallScore").asInt(0));
            report.setOverallLevel(root.path("overallLevel").asText("N/A"));
            report.setReportJson(objectMapper.writeValueAsString(root));
            report.setStatus(MockInterviewReport.Status.SUCCESS);
            report.setErrorMessage(null);
            return reportRepo.save(report);
        } catch (JsonProcessingException e) {
            log.error("报告落库序列化失败, sessionId={}", session.getSessionId(), e);
            return markFailed(session, "报告序列化失败：" + e.getMessage(), rawJson);
        }
    }

    private boolean isValidReport(JsonNode root) {
        JsonNode score = root.path("overallScore");
        boolean hasScore = score.isNumber() || (score.isTextual() && score.asText().matches("\\d+"));
        boolean hasDimensions = root.path("dimensions").isArray() && root.path("dimensions").size() > 0;
        return hasScore && hasDimensions;
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

    /**
     * 超长对话保留头尾：头部是自我介绍与八股，尾部是最近的项目/场景题，掐掉中间比直接截断更合理。
     */
    private String clipTranscript(String transcript) {
        if (transcript.length() <= TRANSCRIPT_BUDGET) {
            return transcript;
        }
        int head = TRANSCRIPT_BUDGET / 2;
        int tail = TRANSCRIPT_BUDGET - head;
        return transcript.substring(0, head)
                + "\n\n……（中间部分对话已省略）……\n\n"
                + transcript.substring(transcript.length() - tail);
    }

    private String buildReportPrompt(MockInterviewSession session, String transcript) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是一场模拟面试的完整对话记录：\n\n");
        sb.append("目标公司：").append(session.getCompany() != null ? session.getCompany() : "通用");
        sb.append(" / 目标部门：").append(session.getDepartment() != null ? session.getDepartment() : "技术");
        if (session.getDifficulty() != null) {
            sb.append(" / 难度：").append(session.getDifficulty());
        }
        sb.append(" / 面试模式：").append(isSimulationMode(session) ? "真实模拟（过程中未给任何反馈）" : "练习（逐题已给反馈）");
        sb.append("\n\n---\n\n");
        sb.append(transcript);
        sb.append("\n\n---\n\n请根据以上对话生成量化评估报告（JSON 格式）。");
        return sb.toString();
    }

    private boolean isSimulationMode(MockInterviewSession session) {
        return "SIMULATION".equalsIgnoreCase(session.getMode());
    }

    /**
     * 真实模拟模式下，候选人在过程中没有拿到任何答案，因此报告必须补齐每题的标准参考答案；
     * 练习模式下这些答案已在对话里出现过，report 里留空即可，避免重复占篇幅。
     */
    private String buildModeInstruction(MockInterviewSession session) {
        if (!isSimulationMode(session)) {
            return "\n补充要求：本场为练习模式，候选人在每轮已当场看到参考答案，questionDetails 的 referenceAnswer 一律输出空字符串。";
        }
        return """
                \n补充要求：本场为真实模拟模式，候选人在面试过程中没有收到任何评价和参考答案，
                因此你必须为 questionDetails 中的每一道主问题补齐 referenceAnswer：
                1. referenceAnswer 必须是完整的标准答案，分点作答，覆盖关键知识点，不要只写「见解析」这种占位内容；
                2. 候选人答错的题，referenceAnswer 要写清正确结论和常见误区；
                3. overallComment 除了总评，还要点出这场最该补的 1-2 个方向。
                """;
    }

    private MockInterviewReport getOrCreateReport(MockInterviewSession session) {
        MockInterviewReport report = reportRepo.findBySession_SessionId(session.getSessionId())
                .orElseGet(MockInterviewReport::new);
        report.setSession(session);
        // reportJson 是非空列：新建报告时先放占位 JSON，避免 GENERATING 状态入库失败
        if (report.getReportJson() == null) {
            report.setReportJson("{}");
        }
        return report;
    }

    private MockInterviewReport createEmptyReport(MockInterviewSession session) {
        MockInterviewReport report = getOrCreateReport(session);
        report.setOverallScore(0);
        report.setOverallLevel("N/A");
        report.setStatus(MockInterviewReport.Status.EMPTY);
        report.setErrorMessage("没有对话记录，无法评分");
        report.setReportJson("{\"overallScore\":0,\"overallLevel\":\"N/A\",\"overallComment\":\"无对话记录\",\"dimensions\":[],\"questionDetails\":[],\"improvementPlan\":[]}");
        return reportRepo.save(report);
    }

    private MockInterviewReport markFailed(MockInterviewSession session, String reason, String rawText) {
        MockInterviewReport report = getOrCreateReport(session);
        report.setOverallScore(0);
        report.setOverallLevel("N/A");
        report.setStatus(MockInterviewReport.Status.FAILED);
        report.setErrorMessage(reason != null && reason.length() > 900 ? reason.substring(0, 900) : reason);
        String comment = "报告生成失败，可点击「重新生成报告」重试";
        StringBuilder json = new StringBuilder();
        json.append("{\"overallScore\":0,\"overallLevel\":\"N/A\",\"overallComment\":\"")
                .append(comment)
                .append("\",\"dimensions\":[],\"questionDetails\":[],\"improvementPlan\":[],\"failed\":true");
        if (rawText != null && !rawText.isBlank()) {
            json.append(",\"rawText\":").append(toJsonString(rawText.length() > 4000 ? rawText.substring(0, 4000) : rawText));
        }
        json.append("}");
        report.setReportJson(json.toString());
        return reportRepo.save(report);
    }

    private String toJsonString(String text) {
        try {
            return objectMapper.writeValueAsString(text);
        } catch (JsonProcessingException e) {
            return "\"\"";
        }
    }

    /** 让模型把半成品整理成合法 JSON；失败返回 null */
    private String repairWithModel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String clipped = raw.length() > 6000 ? raw.substring(0, 6000) : raw;
            ChatResponse response = mockInterviewPlanModel.chat(
                    SystemMessage.from("你是 JSON 修复器。把用户给出的内容整理成合法的报告 JSON，只输出 JSON，不要解释。"),
                    UserMessage.from(clipped)
            );
            return response.aiMessage().text();
        } catch (Exception e) {
            log.warn("模型修复 JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    // ======================== JSON 解析与修复 ========================

    /**
     * 宽松解析：剥离代码围栏 → 截出第一个完整 JSON 对象 → 去掉尾逗号 → 解析；
     * 失败则按最近的逗号回退并补全括号，最多回退 4 次。
     * <p>包级可见，便于单元测试覆盖模型返回的各种脏格式。
     */
    JsonNode parseLenient(String raw) {
        String candidate = extractJson(raw);
        for (int attempt = 0; attempt < 5 && candidate != null; attempt++) {
            String normalized = candidate.replaceAll(",\\s*([}\\]])", "$1");
            try {
                JsonNode node = objectMapper.readTree(normalized);
                if (node != null && node.isObject()) {
                    return node;
                }
            } catch (JsonProcessingException ignored) {
                // 继续回退
            }
            candidate = closeTruncated(normalized);
        }
        return null;
    }

    private String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        // 去掉 ```json ... ``` 围栏（也兼容围栏未闭合的情况）
        int fenceStart = text.indexOf("```");
        if (fenceStart >= 0 && fenceStart <= 10) {
            int lineEnd = text.indexOf('\n', fenceStart);
            if (lineEnd > 0) {
                text = text.substring(lineEnd + 1);
            }
            int fenceEnd = text.lastIndexOf("```");
            if (fenceEnd >= 0) {
                text = text.substring(0, fenceEnd);
            }
            text = text.trim();
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        return cutBalanced(text.substring(start));
    }

    /** 截取从第一个 { 开始、括号配平的完整对象；未闭合时返回剩余全部内容 */
    private String cutBalanced(String text) {
        boolean inStr = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    return text.substring(0, i + 1);
                }
            }
        }
        return text;
    }

    /**
     * 被截断的 JSON 修复：回退到最后一个处于结构内的逗号处，丢弃半截内容并补齐未闭合的括号。
     */
    private String closeTruncated(String text) {
        Deque<Character> stack = new ArrayDeque<>();
        Deque<Character> stackAtSafe = null;
        boolean inStr = false;
        boolean escaped = false;
        int lastSafe = -1;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inStr = true;
                case '{', '[' -> stack.push(c);
                case '}', ']' -> {
                    if (!stack.isEmpty()) {
                        stack.pop();
                    }
                }
                case ',' -> {
                    lastSafe = i;
                    stackAtSafe = new ArrayDeque<>(stack);
                }
                default -> {
                }
            }
        }

        if (lastSafe <= 0 || stackAtSafe == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(text.substring(0, lastSafe));
        while (!stackAtSafe.isEmpty()) {
            char open = stackAtSafe.pop();
            out.append(open == '{' ? '}' : ']');
        }
        return out.toString();
    }
}
