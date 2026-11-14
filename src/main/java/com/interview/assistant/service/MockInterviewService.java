package com.interview.assistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.assistant.entity.*;
import com.interview.assistant.repository.*;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 模拟面试核心服务：管理面试全流程
 * <p>
 * 流程：创建会话 → 生成面试计划 → 开始面试 → 逐题对话（含追问） → 结束 → 生成报告
 */
@Service
public class MockInterviewService {

    private static final Logger log = LoggerFactory.getLogger(MockInterviewService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ChatModel mockInterviewPlanModel;
    private final ChatModel mockInterviewChatModel;

    private final RagService ragService;
    private final InterviewFlowEngine flowEngine;
    private final MockInterviewReportService reportService;
    private final MockInterviewSessionRepository sessionRepo;
    private final MockInterviewPlanRepository planRepo;
    private final MockInterviewDialogueRepository dialogueRepo;
    private final MockInterviewReportRepository reportRepo;

    /** sessionId → 内存中的对话上下文 */
    private final Map<String, InterviewContext> contexts = new ConcurrentHashMap<>();

    public MockInterviewService(@Qualifier("mockInterviewPlanModel") ChatModel mockInterviewPlanModel,
                                @Qualifier("mockInterviewChatModel") ChatModel mockInterviewChatModel,
                                RagService ragService,
                                InterviewFlowEngine flowEngine,
                                MockInterviewReportService reportService,
                                MockInterviewSessionRepository sessionRepo,
                                MockInterviewPlanRepository planRepo,
                                MockInterviewDialogueRepository dialogueRepo,
                                MockInterviewReportRepository reportRepo) {
        this.mockInterviewPlanModel = mockInterviewPlanModel;
        this.mockInterviewChatModel = mockInterviewChatModel;
        this.ragService = ragService;
        this.flowEngine = flowEngine;
        this.reportService = reportService;
        this.sessionRepo = sessionRepo;
        this.planRepo = planRepo;
        this.dialogueRepo = dialogueRepo;
        this.reportRepo = reportRepo;
    }

    // ========================== 1. 创建会话 ==========================

    /**
     * 创建新的模拟面试会话
     */
    public MockInterviewSession createSession(String company, String department,
                                               String resumeText, String interviewerStyle,
                                               String mandatoryQuestions, String difficulty,
                                               String interviewConfig) {
        MockInterviewSession session = new MockInterviewSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setCompany(company);
        session.setDepartment(department);
        session.setResumeSnapshot(resumeText);
        session.setInterviewerStyle(interviewerStyle != null ? interviewerStyle : "GENTLE");
        session.setMandatoryQuestions(mandatoryQuestions);
        session.setDifficulty(difficulty != null ? difficulty : "MEDIUM");
        session.setInterviewConfig(interviewConfig);
        session.setStatus(MockInterviewSession.Status.CREATED);

        // 检索关联面经（检索失败不能让整场面试建不起来，降级为无面经快照）
        String query = (company != null ? company + " " : "") +
                       (department != null ? department + " " : "") +
                       "技术面试 八股 项目 实习";
        try {
            List<String> relevant = ragService.search(query, company, department, 5);
            if (!relevant.isEmpty()) {
                String snapshot = relevant.stream()
                        .map(t -> t.length() > 800 ? t.substring(0, 800) + "..." : t)
                        .collect(Collectors.joining("\n\n---\n\n"));
                session.setExperienceSnapshot(snapshot);
            }
        } catch (Exception e) {
            log.warn("面经检索失败，本次面试将不使用面经快照: {}", e.getMessage());
        }

        return sessionRepo.save(session);
    }

    // ========================== 2. 生成面试计划 ==========================

    /**
     * 调用 LLM 生成结构化面试计划 JSON
     */
    public MockInterviewSession generatePlan(String sessionId) {
        MockInterviewSession session = getSessionOrThrow(sessionId);
        if (session.getStatus() != MockInterviewSession.Status.CREATED) {
            throw new IllegalStateException("只能在 CREATED 状态下生成面试计划");
        }

        String prompt = buildPlanGenerationPrompt(session);
        String systemPrompt = buildPlanSystemPrompt(session);
        ChatResponse response = mockInterviewPlanModel.chat(
                SystemMessage.from(systemPrompt),
                UserMessage.from(prompt)
        );
        String rawJson = response.aiMessage().text();

        // 提取 JSON（LLM 可能包裹在 ```json ... ``` 中）
        String planJson = extractJson(rawJson);

        // 验证 JSON 合法性
        try {
            JsonNode root = objectMapper.readTree(planJson);
            int totalPhases = root.path("phases").size();
            int totalQuestions = 0;
            for (JsonNode phase : root.path("phases")) {
                totalQuestions += phase.path("questions").size();
            }

            MockInterviewPlan plan = new MockInterviewPlan();
            plan.setSession(session);
            plan.setPlanJson(planJson);
            plan.setTotalPhases(totalPhases);
            plan.setTotalQuestions(totalQuestions);
            planRepo.save(plan);

            session.setPlan(plan);
            session.setStatus(MockInterviewSession.Status.PLAN_READY);
            session.setEstimatedDurationMinutes(Math.max(20, totalQuestions * 4));
            return sessionRepo.save(session);
        } catch (JsonProcessingException e) {
            log.error("LLM 生成的面试计划 JSON 解析失败: {}", rawJson, e);
            throw new RuntimeException("面试计划生成失败，请重试");
        }
    }

    // ========================== 3. 开始面试 ==========================

    /**
     * 开始面试，返回第一个问题
     */
    public Map<String, Object> startInterview(String sessionId) {
        MockInterviewSession session = getSessionOrThrow(sessionId);
        if (session.getStatus() != MockInterviewSession.Status.PLAN_READY) {
            throw new IllegalStateException("只能在 PLAN_READY 状态下开始面试");
        }

        session.setStatus(MockInterviewSession.Status.IN_PROGRESS);
        session.setStartedAt(LocalDateTime.now());
        session.setCurrentPhaseIndex(0);
        session.setCurrentQuestionIndex(0);
        session.setCurrentFollowUpCount(0);
        sessionRepo.save(session);

        String planJson = session.getPlan().getPlanJson();
        String firstQuestion = flowEngine.getQuestionContent(planJson, 0, 0);
        String phaseTitle = flowEngine.getPhaseTitle(planJson, 0);
        String phaseType = flowEngine.getPhaseType(planJson, 0);

        // 初始化内存上下文
        InterviewContext ctx = new InterviewContext(session.getSessionId(),
                buildInterviewerSystemPrompt(session));
        contexts.put(sessionId, ctx);

        // 面试官开场语
        String opening = "你好，欢迎参加本次模拟面试。我是你的面试官，接下来我会针对你的简历和相关技术进行提问。\n\n"
                + firstQuestion;

        // 持久化面试官开场
        saveDialogue(session, "interviewer", opening, phaseType, 0, 
                flowEngine.getQuestionId(planJson, 0, 0), false);

        ctx.memory.add(dev.langchain4j.data.message.AiMessage.from(opening));

        return buildResponse(session, opening, phaseType, phaseTitle);
    }

    // ========================== 4. 候选人回答 & AI 追问/下一题 ==========================

    /**
     * 处理候选人回答，返回面试官的回应（追问或下一题）
     */
    public Map<String, Object> answer(String sessionId, String candidateAnswer) {
        MockInterviewSession session = getSessionOrThrow(sessionId);
        if (session.getStatus() != MockInterviewSession.Status.IN_PROGRESS) {
            throw new IllegalStateException("面试未在进行中");
        }

        String planJson = session.getPlan().getPlanJson();
        InterviewContext ctx = contexts.computeIfAbsent(sessionId,
                id -> rebuildContext(session));

        int phaseIdx = session.getCurrentPhaseIndex();
        int questionIdx = session.getCurrentQuestionIndex();
        String phaseType = flowEngine.getPhaseType(planJson, phaseIdx);
        String phaseTitle = flowEngine.getPhaseTitle(planJson, phaseIdx);
        String questionId = flowEngine.getQuestionId(planJson, phaseIdx, questionIdx);

        // 保存候选人回答
        saveDialogue(session, "candidate", candidateAnswer, phaseType, phaseIdx,
                questionId, session.getCurrentFollowUpCount() > 0);

        long startTime = System.currentTimeMillis();

        String nextQuestionPreview = peekNextQuestionPreview(planJson, session);
        boolean canFollowUp = isDeepDivePhase(phaseType) && flowEngine.canFollowUp(planJson, session);

        String prompt = String.format(
            "候选人最新的回复是：\n\"%s\"\n\n" +
            "当前处于「%s」阶段（%s）。\n" +
            "请严格按照以下规则决定你的下一个动作，并在你回复的【最前面】加上隐式标签（必带）：\n\n" +
            "1. 如果候选人没听懂或要求解释，以 [CLARIFICATION] 开头，然后用通俗的语言解释上一个问题。" +
            "解释控制在 300 字以内，讲完再抛一个更简单的验证性问题，不要把标准答案一次性全部讲完。\n\n" +
            "2. 如果候选人给出了实质性回答，但缺少细节或量化指标（且该环节支持深挖：%s），以 [FOLLOW_UP] 开头，" +
            "先对候选人的回答做简要评价（指出哪里答得好、哪里有遗漏或不准确），" +
            "如果回答有错误或不完整，必须先给出当前问题的正确参考答案（讲清关键知识点），" +
            "然后再针对其回答中薄弱的一个技术点进行深挖追问。\n\n" +
            "3. 如果候选人回答完整且准确，或者放弃回答，或者该环节不能深挖，以 [NEXT] 开头，按如下格式回复：\n" +
            "   a) 先对候选人的回答给出评价（1-2句话，指出优点和不足）。\n" +
            "   b) 如果候选人的回答不够完整或存在错误，你必须给出该问题的正确、完整的参考答案（关键知识点要讲清楚，不要含糊）。如果候选人回答得很好则跳过此步。\n" +
            "   c) 然后自然过渡到下一题：\n【%s】\n\n" +
            "注意：只需输出前缀标签+你要说的话，不要输出其他多余字符。" +
            "评价要具体客观，有实质内容，不要只说\"回答得不错\"之类的空话。\n" +
            "给出参考答案时要体现专业深度，帮助候选人真正学到东西。",
            candidateAnswer, phaseTitle, phaseType, canFollowUp ? "是" : "否", nextQuestionPreview
        );

        // 临时放入短期记忆进行决策（不落库保存这一大段提示词）
        ctx.memory.add(UserMessage.from(prompt));

        List<ChatMessage> fullMessages = new ArrayList<>();
        fullMessages.add(SystemMessage.from(ctx.systemPrompt));
        fullMessages.addAll(ctx.memory.messages());

        ChatResponse resp = mockInterviewChatModel.chat(fullMessages);
        String fullReply = resp.aiMessage().text().trim();

        // 恢复真实上下文（移除临时决策用的超长 Prompt，仅放入干净的用户回答文本）
        ctx.memory.messages().remove(ctx.memory.messages().size() - 1);
        ctx.memory.add(UserMessage.from(candidateAnswer));

        long endTime = System.currentTimeMillis();
        double waitSeconds = (endTime - startTime) / 1000.0;
        log.info("======== [AI面试官] 统一生成响应耗时: {} 秒 ========", String.format("%.2f", waitSeconds));

        String intent = "NEXT";
        String interviewerReply = fullReply;
        if (fullReply.startsWith("[CLARIFICATION]")) {
            intent = "CLARIFICATION";
            interviewerReply = fullReply.substring("[CLARIFICATION]".length()).trim();
        } else if (fullReply.startsWith("[FOLLOW_UP]")) {
            intent = "FOLLOW_UP";
            interviewerReply = fullReply.substring("[FOLLOW_UP]".length()).trim();
            flowEngine.incrementFollowUp(session);
        } else if (fullReply.startsWith("[NEXT]")) {
            intent = "NEXT";
            interviewerReply = fullReply.substring("[NEXT]".length()).trim();
        } else if (fullReply.startsWith("[WRAP_UP]")) {
            intent = "NEXT";
            interviewerReply = fullReply.substring("[WRAP_UP]".length()).trim();
        } else {
            if (canFollowUp && (fullReply.contains("追问") || fullReply.contains("展开说说"))) {
                intent = "FOLLOW_UP";
                flowEngine.incrementFollowUp(session);
            }
        }

        ctx.memory.add(AiMessage.from(interviewerReply));

        // 这条回复是「对刚回答那道题的点评 + 新问题」，归属按被回答的题记录，
        // 否则逐题报告会整体错位到下一题。
        String reviewedPhaseType = phaseType;
        int reviewedPhaseIdx = phaseIdx;
        String reviewedQuestionId = questionId;

        if ("NEXT".equals(intent)) {
            boolean hasNext = flowEngine.advanceToNextQuestion(planJson, session);
            if (!hasNext) {
                saveDialogue(session, "interviewer", interviewerReply, "WRAP_UP", phaseIdx, questionId, false);
                sessionRepo.save(session);
                return endInterview(sessionId);
            }
        }

        saveDialogue(session, "interviewer", interviewerReply, reviewedPhaseType,
                reviewedPhaseIdx, reviewedQuestionId, "FOLLOW_UP".equals(intent));

        sessionRepo.save(session);
        return buildResponse(session, interviewerReply,
                flowEngine.getPhaseType(planJson, session.getCurrentPhaseIndex()),
                flowEngine.getPhaseTitle(planJson, session.getCurrentPhaseIndex()));
    }

    // ========================== 5. 结束面试 ==========================

    /**
     * 结束面试并触发报告生成
     */
    public Map<String, Object> endInterview(String sessionId) {
        MockInterviewSession session = getSessionOrThrow(sessionId);
        boolean alreadyCompleted = session.getStatus() == MockInterviewSession.Status.COMPLETED;

        // 生成结束语
        String closing = "好的，本次模拟面试到此结束。感谢你的参与！我会根据你的表现生成一份详细的评估报告，请稍候...";

        if (!alreadyCompleted) {
            session.setStatus(MockInterviewSession.Status.COMPLETED);
            session.setEndedAt(LocalDateTime.now());
            sessionRepo.save(session);

            contexts.remove(sessionId);

            saveDialogue(session, "interviewer", closing,
                    "WRAP_UP", session.getCurrentPhaseIndex(), null, false);

            // 报告异步生成：/end 立即返回，前端轮询会话详情获取结果
            reportService.generateReportAsync(sessionId);
        }

        MockInterviewReport report = reportRepo.findBySession_SessionId(sessionId).orElse(null);
        String reportStatus;
        if (report != null && report.getStatus() != null) {
            reportStatus = report.getStatus().name();
        } else if (!alreadyCompleted) {
            reportStatus = "GENERATING";
        } else {
            reportStatus = null;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("status", "COMPLETED");
        result.put("interviewerMessage", closing);
        result.put("hasReport", report != null && report.getStatus() == MockInterviewReport.Status.SUCCESS);
        result.put("reportStatus", reportStatus);
        if (report != null) {
            result.put("overallScore", report.getOverallScore());
            result.put("overallLevel", report.getOverallLevel());
        }
        return result;
    }

    // ========================== 6. 生命周期控制 ==========================

    /**
     * 暂停面试：保留对话与指针，随时可以继续。
     */
    public Map<String, Object> pauseInterview(String sessionId) {
        MockInterviewSession session = getSessionOrThrow(sessionId);
        if (session.getStatus() != MockInterviewSession.Status.IN_PROGRESS) {
            throw new IllegalStateException("只有进行中的面试可以暂停");
        }
        session.setStatus(MockInterviewSession.Status.PAUSED);
        sessionRepo.save(session);
        contexts.remove(sessionId);

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("status", "PAUSED");
        result.put("interviewerMessage", "面试已暂停，进度已保存，随时可以回来继续。");
        return result;
    }

    /**
     * 继续面试：从暂停处恢复到当前问题。
     */
    public Map<String, Object> resumeInterview(String sessionId) {
        MockInterviewSession session = getSessionOrThrow(sessionId);
        if (session.getStatus() != MockInterviewSession.Status.PAUSED
                && session.getStatus() != MockInterviewSession.Status.IN_PROGRESS) {
            throw new IllegalStateException("当前状态不能继续面试");
        }
        session.setStatus(MockInterviewSession.Status.IN_PROGRESS);
        sessionRepo.save(session);
        contexts.computeIfAbsent(sessionId, id -> rebuildContext(session));

        String planJson = session.getPlan() != null ? session.getPlan().getPlanJson() : null;
        String question = planJson != null
                ? flowEngine.getQuestionContent(planJson, session.getCurrentPhaseIndex(), session.getCurrentQuestionIndex())
                : "";

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("status", "IN_PROGRESS");
        result.put("interviewerMessage", "我们继续刚才的面试。当前问题是：\n\n" + question);
        if (planJson != null) {
            result.put("currentPhase", flowEngine.getPhaseTitle(planJson, session.getCurrentPhaseIndex()));
            result.put("currentPhaseType", flowEngine.getPhaseType(planJson, session.getCurrentPhaseIndex()));
            result.put("progressPercent", flowEngine.getProgressPercent(planJson, session));
        }
        return result;
    }

    /**
     * 放弃面试：不生成报告，之后可从历史记录里「补生成报告」。
     */
    public Map<String, Object> abortInterview(String sessionId) {
        MockInterviewSession session = getSessionOrThrow(sessionId);
        if (session.getStatus() == MockInterviewSession.Status.COMPLETED) {
            throw new IllegalStateException("面试已结束，无需放弃");
        }
        session.setStatus(MockInterviewSession.Status.ABORTED);
        session.setEndedAt(LocalDateTime.now());
        sessionRepo.save(session);
        contexts.remove(sessionId);

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("status", "ABORTED");
        result.put("interviewerMessage", "本次面试已放弃，对话记录仍然保留。");
        return result;
    }

    /**
     * 重新生成报告：用于生成失败或想重算已结束的面试。
     */
    public Map<String, Object> regenerateReport(String sessionId) {
        MockInterviewSession session = getSessionOrThrow(sessionId);
        if (session.getStatus() == MockInterviewSession.Status.CREATED
                || session.getStatus() == MockInterviewSession.Status.PLAN_READY
                || session.getStatus() == MockInterviewSession.Status.IN_PROGRESS) {
            throw new IllegalStateException("面试尚未结束，不能生成报告");
        }
        reportService.generateReportAsync(sessionId);

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("reportStatus", "GENERATING");
        return result;
    }

    // ========================== 查询接口 ==========================

    public List<MockInterviewSession> listSessions() {
        List<MockInterviewSession> sessions = sessionRepo.findAllByOrderByCreatedAtDesc();
        if (sessions.isEmpty()) {
            return sessions;
        }
        List<Long> ids = sessions.stream()
                .map(MockInterviewSession::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return sessions;
        }

        // 列表页只需要分数 / 报告状态 / 对话数，单独批量查，避免把 plan、dialogues、report 全量序列化
        Map<Long, MockInterviewReportRepository.ReportSummary> reports = reportRepo.findSummaryBySessionIds(ids)
                .stream()
                .collect(Collectors.toMap(MockInterviewReportRepository.ReportSummary::getSessionId,
                        s -> s, (a, b) -> a));
        Map<Long, Long> dialogueCounts = dialogueRepo.countGroupBySessionIds(ids)
                .stream()
                .collect(Collectors.toMap(MockInterviewDialogueRepository.DialogueCount::getSessionId,
                        MockInterviewDialogueRepository.DialogueCount::getCnt, (a, b) -> a));

        for (MockInterviewSession s : sessions) {
            MockInterviewReportRepository.ReportSummary summary = reports.get(s.getId());
            if (summary != null) {
                s.setReportScore(summary.getOverallScore());
                s.setReportLevel(summary.getOverallLevel());
                s.setReportStatus(summary.getStatus() != null ? summary.getStatus().name() : null);
            }
            s.setDialogueCount(dialogueCounts.getOrDefault(s.getId(), 0L).intValue());
        }
        return sessions;
    }

    @Transactional(readOnly = true)
    public MockInterviewSession getSessionDetail(String sessionId) {
        return sessionRepo.findBySessionId(sessionId).map(s -> {
            s.getDialogues().size(); // 触发懒加载
            if (s.getPlan() != null) s.getPlan().getPlanJson();
            if (s.getReport() != null) s.getReport().getReportJson();
            return s;
        }).orElse(null);
    }

    @Transactional(readOnly = true)
    public MockInterviewSession getSessionDetailById(Long id) {
        return sessionRepo.findById(id).map(s -> {
            s.getDialogues().size();
            if (s.getPlan() != null) s.getPlan().getPlanJson();
            if (s.getReport() != null) s.getReport().getReportJson();
            return s;
        }).orElse(null);
    }

    public void deleteSession(Long id) {
        sessionRepo.findById(id).ifPresent(s -> {
            contexts.remove(s.getSessionId());
            sessionRepo.delete(s);
        });
    }

    /**
     * 获取面试当前进度
     */
    public Map<String, Object> getProgress(String sessionId) {
        MockInterviewSession session = getSessionOrThrow(sessionId);
        String planJson = session.getPlan() != null ? session.getPlan().getPlanJson() : null;
        Map<String, Object> result = new HashMap<>();
        result.put("status", session.getStatus().name());
        if (planJson != null) {
            result.put("progressPercent", flowEngine.getProgressPercent(planJson, session));
            result.put("currentPhase", flowEngine.getPhaseTitle(planJson, session.getCurrentPhaseIndex()));
            result.put("totalPhases", flowEngine.getTotalPhases(planJson));
            result.put("currentPhaseIndex", session.getCurrentPhaseIndex());
        }
        return result;
    }

    // ========================== 内部方法 ==========================

    private MockInterviewSession getSessionOrThrow(String sessionId) {
        return sessionRepo.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
    }

    private boolean isDeepDivePhase(String phaseType) {
        return "PROJECT".equals(phaseType) || "INTERNSHIP".equals(phaseType)
                || "THEORETICAL".equals(phaseType) || "SCENARIO".equals(phaseType);
    }

    private String peekNextQuestionPreview(String planJson, MockInterviewSession session) {
        int phaseIdx = session.getCurrentPhaseIndex();
        int questionIdx = session.getCurrentQuestionIndex();
        if (questionIdx + 1 < flowEngine.getQuestionCount(planJson, phaseIdx)) {
            return flowEngine.getQuestionContent(planJson, phaseIdx, questionIdx + 1);
        }
        if (phaseIdx + 1 < flowEngine.getTotalPhases(planJson)) {
            String newPhaseTitle = flowEngine.getPhaseTitle(planJson, phaseIdx + 1);
            String q = flowEngine.getQuestionContent(planJson, phaseIdx + 1, 0);
            return "即将进入新阶段「" + newPhaseTitle + "」。新阶段第一题是：" + q;
        }
        return "没有下一题了，这已经是最后一题。请在说完评语后告知面试结束。";
    }

    private void saveDialogue(MockInterviewSession session, String role, String content,
                               String phaseType, int phaseIndex, String questionId, boolean followUp) {
        int order = dialogueRepo.countBySession_Id(session.getId());
        MockInterviewDialogue dialogue = new MockInterviewDialogue();
        dialogue.setSession(session);
        dialogue.setRole(role);
        dialogue.setContent(content);
        dialogue.setPhaseType(phaseType);
        dialogue.setPhaseIndex(phaseIndex);
        dialogue.setQuestionId(questionId);
        dialogue.setFollowUp(followUp);
        dialogue.setSortOrder(order);
        dialogueRepo.save(dialogue);
    }

    private Map<String, Object> buildResponse(MockInterviewSession session, String interviewerMessage,
                                               String phaseType, String phaseTitle) {
        String planJson = session.getPlan() != null ? session.getPlan().getPlanJson() : null;
        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", session.getSessionId());
        result.put("status", session.getStatus().name());
        result.put("interviewerMessage", interviewerMessage);
        result.put("currentPhase", phaseTitle);
        result.put("currentPhaseType", phaseType);
        if (planJson != null) {
            result.put("progressPercent", flowEngine.getProgressPercent(planJson, session));
        }
        return result;
    }

    /**
     * 重建上下文（服务器重启后恢复）
     */
    private InterviewContext rebuildContext(MockInterviewSession session) {
        InterviewContext ctx = new InterviewContext(session.getSessionId(),
                buildInterviewerSystemPrompt(session));
        List<MockInterviewDialogue> dialogues = dialogueRepo
                .findBySession_SessionIdOrderBySortOrderAsc(session.getSessionId());
        for (MockInterviewDialogue d : dialogues) {
            if ("interviewer".equals(d.getRole())) {
                ctx.memory.add(AiMessage.from(d.getContent()));
            } else {
                ctx.memory.add(UserMessage.from(d.getContent()));
            }
        }
        return ctx;
    }

    // ========================== Prompt 构建 ==========================

    private static final String PLAN_SYSTEM_PROMPT_TEMPLATE = """
            你是一位资深技术面试官，负责根据候选人的简历和面经资料，生成一份针对目标岗位量身定制的结构化面试计划。

            你必须严格输出 JSON 格式，不要包含任何其他文字说明。JSON 结构如下：
            {
              "techStackAnalysis": "一句话总结该目标岗位和候选人简历所要求的核心理论技术栈（如：微服务、MySQL底层、Redis缓存、计网等）",
              "phases": [
                {
                  "phaseId": 数字,
                  "type": "WARM_UP|THEORETICAL|PROJECT|INTERNSHIP|SCENARIO|WRAP_UP",
                  "title": "阶段标题",
                  "questions": [
                    { "id": "q数字", "content": "问题内容", "maxFollowUps": 数字 }
                  ]
                }
              ]
            }

            规则：
            1. 必须包含 WARM_UP（1题自我介绍）和 WRAP_UP（1题反问环节）。
            %s
            7. 只输出 JSON，不要输出任何其他内容。
            """;

    private String buildPlanSystemPrompt(MockInterviewSession session) {
        String diff = session.getDifficulty() != null ? session.getDifficulty() : "MEDIUM";
        String diffDesc = switch (diff) {
            case "EASY" -> "简单难度：以基础概念、常见用法为主，不需要深入底层原理，适合实习生或初级岗位";
            case "HARD" -> "困难难度：要求深入底层原理、源码级别理解，场景题要求复杂业务系统设计，适合社招或高级岗";
            default -> "中等难度：需要一定深度的技术理解，符合大厂真实面试难度";
        };

        // 解析自定义题目数量
        int theoretical = 3, project = 2, scenario = 1;
        if (session.getInterviewConfig() != null && !session.getInterviewConfig().isBlank()) {
            try {
                var node = objectMapper.readTree(session.getInterviewConfig());
                if (node.has("theoretical")) theoretical = node.get("theoretical").asInt(3);
                if (node.has("project")) project = node.get("project").asInt(2);
                if (node.has("scenario")) scenario = node.get("scenario").asInt(1);
            } catch (Exception ignored) {}
        }
        int total = theoretical + project + scenario + 2; // +2 for WARM_UP + WRAP_UP

        String rules = String.format("""
            2. THEORETICAL：理论知识考核。根据你分析的核心技术栈，生成 %d 个有深度的底层原理或经典八股问题。maxFollowUps 设为 1 或 2。
            3. PROJECT/INTERNSHIP：根据简历生成 %d 个深挖问题，maxFollowUps 设为 2 或 3。
            4. SCENARIO：场景设计题。给出 %d 个该岗位常遇的复杂业务场景，maxFollowUps 设为 2。
            5. 总问题数控制在 %d 个左右。
            6. 难度要求：%s。""",
            theoretical, project, scenario, total, diffDesc);

        return String.format(PLAN_SYSTEM_PROMPT_TEMPLATE, rules);
    }

    private String buildPlanGenerationPrompt(MockInterviewSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append("目标公司：").append(session.getCompany() != null ? session.getCompany() : "通用");
        sb.append("\n目标部门：").append(session.getDepartment() != null ? session.getDepartment() : "技术");
        sb.append("\n\n【候选人简历】\n");
        sb.append(session.getResumeSnapshot() != null ? session.getResumeSnapshot() : "（未提供简历）");
        if (session.getExperienceSnapshot() != null && !session.getExperienceSnapshot().isEmpty()) {
            sb.append("\n\n【参考面经资料】\n");
            sb.append(session.getExperienceSnapshot());
        }
        if (session.getMandatoryQuestions() != null && !session.getMandatoryQuestions().isBlank()) {
            sb.append("\n\n【必问问题（强制要求）】\n");
            sb.append("以下问题是用户指定的必问题，你必须将它们原样纳入面试计划的对应阶段中（根据问题内容判断属于 THEORETICAL/PROJECT/SCENARIO 等），不得省略或替换：\n");
            try {
                var questions = objectMapper.readValue(session.getMandatoryQuestions(), String[].class);
                for (int i = 0; i < questions.length; i++) {
                    sb.append(String.format("%d. %s\n", i + 1, questions[i]));
                }
            } catch (Exception e) {
                sb.append(session.getMandatoryQuestions());
            }
        }
        return sb.toString();
    }

    private String buildInterviewerSystemPrompt(MockInterviewSession session) {
        String style = switch (session.getInterviewerStyle()) {
            case "PRESSURE" -> "你的风格是压力面试官：会质疑候选人的回答，追问细节漏洞，语气严厉但专业。";
            case "TECH_GEEK" -> "你的风格是技术极客面试官：喜欢讨论底层原理、源码实现，会从架构设计角度追问。";
            default -> "你的风格是温和面试官：语气友善，鼓励候选人展开回答，适时引导。";
        };

        return """
                你是一位互联网大厂的技术面试官，正在进行一场模拟面试。你不仅是考官，也是候选人的导师，帮助他在面试中成长。
                """ + style + """

                规则：
                1. 每次只问一个问题，等待候选人回答
                2. 对项目和实习经历要深挖细节：技术选型原因、架构设计、性能优化、踩过的坑
                3. 对八股类问题简洁追问即可
                4. 用中文交流，语气自然像真实面试
                5. 不要在一个回复中问多个问题
                6. 回复控制在 600 字以内
                7. 【评价要求】每当候选人回答完一个问题后，你必须对其回答给出客观、具体的评价，指出优点和不足
                8. 【纠正补充】如果候选人的回答有错误、不完整或遗漏了关键知识点，你必须给出该问题的正确且完整的参考答案，让候选人知道标准答案是什么，真正从面试中学到东西
                9. 【过渡提问】评价和纠正完成后，再自然过渡到下一个问题

                候选人简历：
                """ + (session.getResumeSnapshot() != null ?
                       (session.getResumeSnapshot().length() > 2000 ?
                        session.getResumeSnapshot().substring(0, 2000) + "..." :
                        session.getResumeSnapshot()) : "（未提供）");
    }

    private String extractJson(String raw) {
        if (raw == null) return "{}";
        // 去除 ```json ... ``` 包裹
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastBacktick = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastBacktick > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastBacktick).trim();
            }
        }
        // 尝试找到第一个 { 和最后一个 }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    // ========================== 内部类 ==========================

    private static class InterviewContext {
        final String systemPrompt;
        final MessageWindowChatMemory memory;

        InterviewContext(String sessionId, String systemPrompt) {
            this.systemPrompt = systemPrompt;
            this.memory = MessageWindowChatMemory.withMaxMessages(30);
        }
    }
}
