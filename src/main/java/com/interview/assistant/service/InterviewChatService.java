package com.interview.assistant.service;

import com.interview.assistant.entity.InterviewChatMessage;
import com.interview.assistant.entity.InterviewChatSession;
import com.interview.assistant.exception.NotFoundException;
import com.interview.assistant.repository.InterviewChatMessageRepository;
import com.interview.assistant.repository.InterviewChatSessionRepository;
import com.interview.assistant.security.TenantContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 面试深挖问题后的上下文探讨聊天服务，支持按会话记忆。
 */
@Service
public class InterviewChatService {

    private static final String SYSTEM_PROMPT = """
            你是互联网大厂技术面试官，与候选人进行模拟面试。

            两种角色：
            1. 面试官：候选人作答时，追问、点评或引导。
            2. 答疑：候选人请你回答某题时，给出全面但简短的参考答案。

            答疑时要求：
            - 答案控制在 300 字以内，只讲核心要点
            - 用数字序号或简短小标题分点，少用或不用 * 符号
            - 重点用 **加粗** 标出
            - 禁止冗长啰嗦，禁止大段重复
            - 直接给答案，不要「好的」「下面我来说」等开场

            用中文回答。
            """;

    private final ChatModel chatModel;
    private final RagService ragService;
    private final InterviewChatSessionRepository sessionRepository;
    private final InterviewChatMessageRepository messageRepository;

    /** sessionId -> 会话上下文（含记忆和面试背景） */
    private final Map<String, InterviewSession> sessions = new ConcurrentHashMap<>();

    public InterviewChatService(@Qualifier("interviewChatModel") ChatModel chatModel,
            RagService ragService,
            InterviewChatSessionRepository sessionRepository,
            InterviewChatMessageRepository messageRepository) {
        this.chatModel = chatModel;
        this.ragService = ragService;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    public String chat(String sessionId, String userMessage, String questions, String resume, String company,
            String department) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage 不能为空");
        }
        // 防止用别人的 sessionId 往他人会话里写消息
        assertOwnership(sessionId);

        InterviewSession session = sessions.computeIfAbsent(sessionId, id -> {
            StringBuilder system = new StringBuilder(SYSTEM_PROMPT);
            if (questions != null && !questions.isBlank()) {
                system.append("\n\n【本场面试深挖问题】\n").append(questions);
            }
            if (resume != null && !resume.isBlank()) {
                system.append("\n\n【候选人简历】\n")
                        .append(resume.length() > 2000 ? resume.substring(0, 2000) + "..." : resume);
            }
            return new InterviewSession(system.toString());
        });

        synchronized (session) {
            session.memory.add(UserMessage.from(userMessage));

            String c = company != null && !company.isEmpty() ? company : null;
            String d = department != null && !department.isEmpty() ? department : null;
            List<String> ragRelevant = ragService.search(userMessage, c, d, 5);
            String ragContext = ragRelevant.isEmpty() ? ""
                    : "\n\n【相关面经参考，供追问和答疑时参考】\n" + String.join("\n\n---\n\n", ragRelevant.stream()
                            .map(t -> t.length() > 500 ? t.substring(0, 500) + "..." : t)
                            .toList());

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(session.systemContext + ragContext));
            messages.addAll(session.memory.messages());

            ChatResponse response = chatModel.chat(messages);
            AiMessage aiMessage = response.aiMessage();
            String text = aiMessage.text();

            session.memory.add(aiMessage);

            // 持久化到数据库
            persistMessage(sessionId, userMessage, text, questions, resume, company, department);
            return text;
        }
    }

    private void persistMessage(String sessionId, String userContent, String aiContent, String questions, String resume,
            String company, String department) {
        InterviewChatSession dbSession = sessionRepository.findBySessionId(sessionId).orElseGet(() -> {
            InterviewChatSession s = new InterviewChatSession();
            s.setSessionId(sessionId);
            s.setTenantId(TenantContext.requireTenantId());
            s.setQuestions(questions);
            s.setResume(resume != null && resume.length() > 5000 ? resume.substring(0, 5000) + "..." : resume);
            s.setCompany(company);
            s.setDepartment(department);
            return sessionRepository.save(s);
        });

        int order = messageRepository.findBySession_IdOrderBySortOrderAscIdAsc(dbSession.getId()).size();
        InterviewChatMessage userMsg = new InterviewChatMessage();
        userMsg.setSession(dbSession);
        userMsg.setRole("user");
        userMsg.setContent(userContent);
        userMsg.setSortOrder(order++);
        messageRepository.save(userMsg);

        InterviewChatMessage aiMsg = new InterviewChatMessage();
        aiMsg.setSession(dbSession);
        aiMsg.setRole("assistant");
        aiMsg.setContent(aiContent);
        aiMsg.setSortOrder(order);
        messageRepository.save(aiMsg);
    }

    public void endSession(String sessionId, String questions, String resume, String company, String department) {
        assertOwnership(sessionId);
        InterviewChatSession dbSession = sessionRepository.findBySessionId(sessionId).orElseGet(() -> {
            InterviewChatSession s = new InterviewChatSession();
            s.setSessionId(sessionId);
            s.setTenantId(TenantContext.requireTenantId());
            s.setQuestions(questions);
            s.setResume(resume != null && resume.length() > 5000 ? resume.substring(0, 5000) + "..." : resume);
            s.setCompany(company);
            s.setDepartment(department);
            return sessionRepository.save(s);
        });
        dbSession.setEndedAt(java.time.LocalDateTime.now());
        sessionRepository.save(dbSession);
        sessions.remove(sessionId);
    }

    public void deleteSession(String sessionId) {
        Long tenantId = TenantContext.requireTenantId();
        sessionRepository.findBySessionId(sessionId)
                .filter(s -> tenantId.equals(s.getTenantId()))
                .ifPresent(s -> {
                    sessions.remove(sessionId);
                    sessionRepository.delete(s);
                });
    }

    public void deleteSessionById(Long id) {
        Long tenantId = TenantContext.requireTenantId();
        sessionRepository.findById(id)
                .filter(s -> tenantId.equals(s.getTenantId()))
                .ifPresent(s -> {
                    sessions.remove(s.getSessionId());
                    sessionRepository.delete(s);
                });
    }

    @Transactional(readOnly = true)
    public InterviewChatSession getSessionById(Long id) {
        Long tenantId = TenantContext.requireTenantId();
        return sessionRepository.findById(id)
                .filter(s -> tenantId.equals(s.getTenantId()))
                .map(s -> {
                    s.getMessages().size();
                    return s;
                })
                .orElse(null);
    }

    public List<InterviewChatSession> listSessions() {
        return sessionRepository.findAllByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId());
    }

    @Transactional(readOnly = true)
    public InterviewChatSession getSession(String sessionId) {
        Long tenantId = TenantContext.requireTenantId();
        return sessionRepository.findBySessionId(sessionId)
                .filter(s -> tenantId.equals(s.getTenantId()))
                .map(s -> {
                    s.getMessages().size(); // 触发懒加载
                    return s;
                })
                .orElse(null);
    }

    /**
     * 会话存在但不属于当前租户时按「不存在」处理，避免越权写入。
     */
    private void assertOwnership(String sessionId) {
        Long tenantId = TenantContext.requireTenantId();
        sessionRepository.findBySessionId(sessionId)
                .filter(s -> !tenantId.equals(s.getTenantId()))
                .ifPresent(s -> {
                    throw new NotFoundException("会话不存在: " + sessionId);
                });
    }

    private static class InterviewSession {        final String systemContext;
        final MessageWindowChatMemory memory;

        InterviewSession(String systemContext) {
            this.systemContext = systemContext;
            this.memory = MessageWindowChatMemory.withMaxMessages(20);
        }
    }
}
