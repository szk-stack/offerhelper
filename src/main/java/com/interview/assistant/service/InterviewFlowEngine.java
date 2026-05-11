package com.interview.assistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.assistant.entity.MockInterviewSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 面试流程引擎（状态机）：管理面试的阶段推进、追问决策。
 * <p>
 * planJson 结构：
 * { "phases": [ { "phaseId":1, "type":"...", "title":"...",
 *   "questions": [ {"id":"q1","content":"...","maxFollowUps":3} ] } ] }
 */
@Component
public class InterviewFlowEngine {

    private static final Logger log = LoggerFactory.getLogger(InterviewFlowEngine.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ======================== Plan 解析 ========================

    /** 获取总阶段数 */
    public int getTotalPhases(String planJson) {
        return getPhasesNode(planJson).size();
    }

    /** 获取指定阶段的总问题数 */
    public int getQuestionCount(String planJson, int phaseIndex) {
        JsonNode phase = getPhaseNode(planJson, phaseIndex);
        return phase != null ? phase.path("questions").size() : 0;
    }

    /** 获取指定阶段的类型（WARM_UP / PROJECT / INTERNSHIP / EXPERIENCE_BASED / WRAP_UP） */
    public String getPhaseType(String planJson, int phaseIndex) {
        JsonNode phase = getPhaseNode(planJson, phaseIndex);
        return phase != null ? phase.path("type").asText("UNKNOWN") : "UNKNOWN";
    }

    /** 获取指定阶段标题 */
    public String getPhaseTitle(String planJson, int phaseIndex) {
        JsonNode phase = getPhaseNode(planJson, phaseIndex);
        return phase != null ? phase.path("title").asText("") : "";
    }

    /** 获取指定问题内容 */
    public String getQuestionContent(String planJson, int phaseIndex, int questionIndex) {
        JsonNode q = getQuestionNode(planJson, phaseIndex, questionIndex);
        return q != null ? q.path("content").asText("") : "";
    }

    /** 获取指定问题 ID */
    public String getQuestionId(String planJson, int phaseIndex, int questionIndex) {
        JsonNode q = getQuestionNode(planJson, phaseIndex, questionIndex);
        return q != null ? q.path("id").asText("q" + phaseIndex + "_" + questionIndex) : "q" + phaseIndex + "_" + questionIndex;
    }

    /** 获取指定问题的最大追问次数 */
    public int getMaxFollowUps(String planJson, int phaseIndex, int questionIndex) {
        JsonNode q = getQuestionNode(planJson, phaseIndex, questionIndex);
        return q != null ? q.path("maxFollowUps").asInt(2) : 2;
    }

    // ======================== 流程推进 ========================

    /**
     * 判断当前面试是否还有下一个问题（包括下一个阶段的问题）
     */
    public boolean hasNextQuestion(String planJson, MockInterviewSession session) {
        int phaseIdx = session.getCurrentPhaseIndex();
        int questionIdx = session.getCurrentQuestionIndex();
        int totalPhases = getTotalPhases(planJson);

        // 当前阶段还有下一题
        if (questionIdx + 1 < getQuestionCount(planJson, phaseIdx)) {
            return true;
        }
        // 还有下一个阶段
        return phaseIdx + 1 < totalPhases;
    }

    /**
     * 推进到下一个问题，更新 session 的位置指针。
     * @return true 如果成功推进，false 如果面试已结束
     */
    public boolean advanceToNextQuestion(String planJson, MockInterviewSession session) {
        int phaseIdx = session.getCurrentPhaseIndex();
        int questionIdx = session.getCurrentQuestionIndex();

        // 当前阶段还有下一题
        if (questionIdx + 1 < getQuestionCount(planJson, phaseIdx)) {
            session.setCurrentQuestionIndex(questionIdx + 1);
            session.setCurrentFollowUpCount(0);
            return true;
        }

        // 下一个阶段
        if (phaseIdx + 1 < getTotalPhases(planJson)) {
            session.setCurrentPhaseIndex(phaseIdx + 1);
            session.setCurrentQuestionIndex(0);
            session.setCurrentFollowUpCount(0);
            return true;
        }

        // 所有问题已结束
        return false;
    }

    /**
     * 判断当前问题是否可以继续追问
     */
    public boolean canFollowUp(String planJson, MockInterviewSession session) {
        int maxFollowUps = getMaxFollowUps(planJson, session.getCurrentPhaseIndex(), session.getCurrentQuestionIndex());
        return session.getCurrentFollowUpCount() < maxFollowUps;
    }

    /**
     * 增加追问计数
     */
    public void incrementFollowUp(MockInterviewSession session) {
        session.setCurrentFollowUpCount(session.getCurrentFollowUpCount() + 1);
    }

    /**
     * 获取当前问题的上下文描述（用于面试官 Prompt）
     */
    public String getCurrentContext(String planJson, MockInterviewSession session) {
        int pi = session.getCurrentPhaseIndex();
        int qi = session.getCurrentQuestionIndex();
        String phaseType = getPhaseType(planJson, pi);
        String phaseTitle = getPhaseTitle(planJson, pi);
        String question = getQuestionContent(planJson, pi, qi);
        int followUpCount = session.getCurrentFollowUpCount();
        int maxFollowUps = getMaxFollowUps(planJson, pi, qi);

        return String.format("【当前阶段】%s - %s | 【当前问题】%s | 【追问轮次】%d/%d",
                phaseType, phaseTitle, question, followUpCount, maxFollowUps);
    }

    /**
     * 计算面试进度百分比
     */
    public int getProgressPercent(String planJson, MockInterviewSession session) {
        int totalQuestions = 0;
        int completedQuestions = 0;
        int totalPhases = getTotalPhases(planJson);

        for (int p = 0; p < totalPhases; p++) {
            int qCount = getQuestionCount(planJson, p);
            totalQuestions += qCount;
            if (p < session.getCurrentPhaseIndex()) {
                completedQuestions += qCount;
            } else if (p == session.getCurrentPhaseIndex()) {
                completedQuestions += session.getCurrentQuestionIndex();
            }
        }
        return totalQuestions == 0 ? 0 : (int) ((completedQuestions * 100.0) / totalQuestions);
    }

    // ======================== 内部工具方法 ========================

    private JsonNode getPhasesNode(String planJson) {
        try {
            return objectMapper.readTree(planJson).path("phases");
        } catch (JsonProcessingException e) {
            log.error("解析面试计划 JSON 失败", e);
            return objectMapper.createArrayNode();
        }
    }

    private JsonNode getPhaseNode(String planJson, int phaseIndex) {
        JsonNode phases = getPhasesNode(planJson);
        return (phaseIndex >= 0 && phaseIndex < phases.size()) ? phases.get(phaseIndex) : null;
    }

    private JsonNode getQuestionNode(String planJson, int phaseIndex, int questionIndex) {
        JsonNode phase = getPhaseNode(planJson, phaseIndex);
        if (phase == null) return null;
        JsonNode questions = phase.path("questions");
        return (questionIndex >= 0 && questionIndex < questions.size()) ? questions.get(questionIndex) : null;
    }
}
