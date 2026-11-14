package com.interview.assistant.controller;

import com.fasterxml.jackson.annotation.JsonView;
import com.interview.assistant.entity.MockInterviewSession;
import com.interview.assistant.exception.NotFoundException;
import com.interview.assistant.security.AuthException;
import com.interview.assistant.service.MockInterviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mock-interview")
@CrossOrigin
public class MockInterviewController {

    private static final Logger log = LoggerFactory.getLogger(MockInterviewController.class);
    private final MockInterviewService mockInterviewService;

    public MockInterviewController(MockInterviewService mockInterviewService) {
        this.mockInterviewService = mockInterviewService;
    }

    /**
     * 创建面试会话
     */
    @PostMapping("/sessions")
    public ResponseEntity<?> createSession(@RequestBody Map<String, Object> request) {
        String company = (String) request.get("company");
        String department = (String) request.get("department");
        String resumeText = (String) request.get("resumeText");
        String interviewerStyle = request.getOrDefault("interviewerStyle", "GENTLE").toString();
        String difficulty = request.getOrDefault("difficulty", "MEDIUM").toString();
        // mandatoryQuestions: 前端传来的是 JSON 数组，转为字符串存储
        Object mqRaw = request.get("mandatoryQuestions");
        String mandatoryQuestions = null;
        if (mqRaw instanceof java.util.List<?> list && !list.isEmpty()) {
            try {
                mandatoryQuestions = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(list);
            } catch (Exception ignored) {}
        }
        // questionCounts: 前端传来的是 JSON 对象
        Object qcRaw = request.get("questionCounts");
        String interviewConfig = null;
        if (qcRaw instanceof java.util.Map<?, ?> map && !map.isEmpty()) {
            try {
                interviewConfig = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
            } catch (Exception ignored) {}
        }

        try {
            MockInterviewSession session = mockInterviewService.createSession(
                    company, department, resumeText, interviewerStyle,
                    mandatoryQuestions, difficulty, interviewConfig);
            return ResponseEntity.ok(Map.of(
                    "sessionId", session.getSessionId(),
                    "status", session.getStatus().name()
            ));
        } catch (Exception e) {
            log.error("创建模拟面试会话失败", e);
            return errorResponse(e);
        }
    }

    /**
     * 生成面试计划
     */
    @PostMapping("/sessions/{sessionId}/plan")
    public ResponseEntity<?> generatePlan(@PathVariable String sessionId) {
        try {
            MockInterviewSession session = mockInterviewService.generatePlan(sessionId);
            return ResponseEntity.ok(Map.of(
                    "sessionId", session.getSessionId(),
                    "status", session.getStatus().name(),
                    "planJson", session.getPlan() != null ? session.getPlan().getPlanJson() : null,
                    "estimatedDurationMinutes", session.getEstimatedDurationMinutes()
            ));
        } catch (Exception e) {
            log.error("生成面试计划失败, sessionId={}", sessionId, e);
            return errorResponse(e);
        }
    }

    /**
     * 开始面试（获取第一题）
     */
    @PostMapping("/sessions/{sessionId}/start")
    public ResponseEntity<?> startInterview(@PathVariable String sessionId) {
        try {
            Map<String, Object> result = mockInterviewService.startInterview(sessionId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("开始面试失败, sessionId={}", sessionId, e);
            return errorResponse(e);
        }
    }

    /**
     * 提交候选人回答，获取面试官回复（追问或下一题）
     */
    @PostMapping("/sessions/{sessionId}/answer")
    public ResponseEntity<?> answer(@PathVariable String sessionId, @RequestBody Map<String, String> request) {
        String candidateAnswer = request.get("answer");
        if (candidateAnswer == null || candidateAnswer.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "回答内容不能为空"));
        }
        try {
            Map<String, Object> result = mockInterviewService.answer(sessionId, candidateAnswer);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("回答处理失败, sessionId={}", sessionId, e);
            return errorResponse(e);
        }
    }

    /**
     * 提交候选人回答并流式返回面试官回复（SSE）。
     * 事件：chunk（{"text": "..."}）/ done（完整响应体）/ error（{"message": "..."}）
     */
    @PostMapping("/sessions/{sessionId}/answer/stream")
    public Object answerStream(@PathVariable String sessionId,
                               @RequestBody Map<String, String> request,
                               jakarta.servlet.http.HttpServletResponse response) {
        // 关闭 nginx 缓冲，保证 token 能实时到达浏览器
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        String candidateAnswer = request != null ? request.get("answer") : null;
        if (candidateAnswer == null || candidateAnswer.isBlank()) {
            throw new IllegalArgumentException("回答内容不能为空");
        }
        try {
            return mockInterviewService.answerStream(sessionId, candidateAnswer);
        } catch (Exception e) {
            log.error("流式回答失败, sessionId={}", sessionId, e);
            return errorResponse(e);
        }
    }

    /**
     * 结束面试，触发生成报告
     */
    @PostMapping("/sessions/{sessionId}/end")
    public ResponseEntity<?> endInterview(@PathVariable String sessionId) {
        try {
            Map<String, Object> result = mockInterviewService.endInterview(sessionId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("结束面试失败, sessionId={}", sessionId, e);
            return errorResponse(e);
        }
    }

    /**
     * 暂停面试（保留进度，可继续）
     */
    @PostMapping("/sessions/{sessionId}/pause")
    public ResponseEntity<?> pauseInterview(@PathVariable String sessionId) {
        try {
            return ResponseEntity.ok(mockInterviewService.pauseInterview(sessionId));
        } catch (Exception e) {
            log.error("暂停面试失败, sessionId={}", sessionId, e);
            return errorResponse(e);
        }
    }

    /**
     * 继续面试（从暂停处恢复）
     */
    @PostMapping("/sessions/{sessionId}/resume")
    public ResponseEntity<?> resumeInterview(@PathVariable String sessionId) {
        try {
            return ResponseEntity.ok(mockInterviewService.resumeInterview(sessionId));
        } catch (Exception e) {
            log.error("继续面试失败, sessionId={}", sessionId, e);
            return errorResponse(e);
        }
    }

    /**
     * 放弃面试（保留对话，不生成报告）
     */
    @PostMapping("/sessions/{sessionId}/abort")
    public ResponseEntity<?> abortInterview(@PathVariable String sessionId) {
        try {
            return ResponseEntity.ok(mockInterviewService.abortInterview(sessionId));
        } catch (Exception e) {
            log.error("放弃面试失败, sessionId={}", sessionId, e);
            return errorResponse(e);
        }
    }

    /**
     * 重新生成评估报告（生成失败或想重算时使用）
     */
    @PostMapping("/sessions/{sessionId}/report")
    public ResponseEntity<?> regenerateReport(@PathVariable String sessionId) {
        try {
            return ResponseEntity.ok(mockInterviewService.regenerateReport(sessionId));
        } catch (Exception e) {
            log.error("重新生成报告失败, sessionId={}", sessionId, e);
            return errorResponse(e);
        }
    }

    /**
     * 获取面试进度状态
     */
    @GetMapping("/sessions/{sessionId}/progress")
    public ResponseEntity<?> getProgress(@PathVariable String sessionId) {
        try {
            Map<String, Object> result = mockInterviewService.getProgress(sessionId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("获取进度失败, sessionId={}", sessionId, e);
            return errorResponse(e);
        }
    }

    /**
     * 获取历史面试会话列表。
     * 只返回列表视图（不含 plan / dialogues / report），分数与报告状态由摘要字段提供。
     */
    @GetMapping("/sessions")
    @JsonView(MockInterviewSession.Views.List.class)
    public ResponseEntity<List<MockInterviewSession>> listSessions() {
        return ResponseEntity.ok(mockInterviewService.listSessions());
    }

    /**
     * 获取具体会话详情（含对话、计划、报告）
     */
    @GetMapping("/sessions/by-id/{id}")
    public ResponseEntity<?> getSessionDetailById(@PathVariable Long id) {
        MockInterviewSession session = mockInterviewService.getSessionDetailById(id);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(session);
    }
    
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<?> getSessionDetailBySessionId(@PathVariable String sessionId) {
        MockInterviewSession session = mockInterviewService.getSessionDetail(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(session);
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<?> deleteSession(@PathVariable Long id) {
        try {
            mockInterviewService.deleteSession(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("删除会话失败, id={}", id, e);
            return errorResponse(e);
        }
    }

    /**
     * 把业务异常映射成正确的 HTTP 状态，前端才能区分「没登录 / 不存在 / 状态不对」。
     */
    private ResponseEntity<?> errorResponse(Exception e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (e instanceof AuthException) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", message));
        }
        if (e instanceof NotFoundException) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", message));
        }
        if (e instanceof IllegalStateException) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", message));
        }
        if (e instanceof IllegalArgumentException) {
            return ResponseEntity.badRequest().body(Map.of("error", message));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", message));
    }
}
