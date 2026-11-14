package com.interview.assistant.service;

import com.interview.assistant.entity.MockInterviewReport;
import com.interview.assistant.entity.MockInterviewSession;
import com.interview.assistant.repository.MockInterviewDialogueRepository;
import com.interview.assistant.repository.MockInterviewReportRepository;
import com.interview.assistant.repository.MockInterviewSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 模拟面试的定时维护：
 * <ul>
 *   <li>启动时回填历史报告的状态字段；</li>
 *   <li>把长时间没有动静的「进行中」会话标记为 ABORTED。</li>
 * </ul>
 * 之前没有暂停/放弃入口，用户关掉页面后会话会永远停在 IN_PROGRESS，
 * 既不能补报告也看不出是「真在面试」。这里只改状态，对话与指针全部保留。
 */
@Component
public class MockInterviewMaintenanceService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MockInterviewMaintenanceService.class);

    private final MockInterviewSessionRepository sessionRepo;
    private final MockInterviewDialogueRepository dialogueRepo;
    private final MockInterviewReportRepository reportRepo;

    /** 超过多少小时没有新发言就判定为放弃，默认 12 小时 */
    @Value("${app.mock-interview.stale-hours:12}")
    private int staleHours;

    public MockInterviewMaintenanceService(MockInterviewSessionRepository sessionRepo,
                                           MockInterviewDialogueRepository dialogueRepo,
                                           MockInterviewReportRepository reportRepo) {
        this.sessionRepo = sessionRepo;
        this.dialogueRepo = dialogueRepo;
        this.reportRepo = reportRepo;
    }

    @Override
    @Transactional
    public void run(String... args) {
        backfillReportStatus();
    }

    /**
     * 早期报告没有 status 字段：按分数回填，让前端能正确区分「已生成」和「解析失败」。
     */
    public void backfillReportStatus() {
        List<MockInterviewReport> rows = reportRepo.findByStatusIsNull();
        if (rows.isEmpty()) {
            return;
        }
        for (MockInterviewReport report : rows) {
            if (report.getOverallScore() > 0) {
                report.setStatus(MockInterviewReport.Status.SUCCESS);
            } else {
                report.setStatus(MockInterviewReport.Status.FAILED);
                report.setErrorMessage("历史报告：当时未能解析模型返回内容，可重新生成");
            }
        }
        reportRepo.saveAll(rows);
        log.info("已回填 {} 条历史面试报告的状态", rows.size());
    }

    @Scheduled(initialDelayString = "${app.mock-interview.sweep-initial-delay-ms:300000}",
               fixedDelayString = "${app.mock-interview.sweep-interval-ms:3600000}")
    @Transactional
    public void abortStaleSessions() {
        int hours = Math.max(1, staleHours);
        LocalDateTime threshold = LocalDateTime.now().minusHours(hours);

        List<MockInterviewSession> running = sessionRepo
                .findByStatusOrderByCreatedAtDesc(MockInterviewSession.Status.IN_PROGRESS);
        if (running.isEmpty()) {
            return;
        }

        List<Long> ids = running.stream().map(MockInterviewSession::getId).toList();
        Map<Long, LocalDateTime> lastActivity = dialogueRepo.findLastActivity(ids).stream()
                .collect(Collectors.toMap(MockInterviewDialogueRepository.LastActivity::getSessionId,
                        MockInterviewDialogueRepository.LastActivity::getLastAt, (a, b) -> a));

        List<MockInterviewSession> stale = new ArrayList<>();
        for (MockInterviewSession session : running) {
            LocalDateTime last = lastActivity.get(session.getId());
            if (last == null) {
                last = session.getStartedAt() != null ? session.getStartedAt() : session.getCreatedAt();
            }
            if (last == null || last.isBefore(threshold)) {
                stale.add(session);
            }
        }
        if (stale.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (MockInterviewSession session : stale) {
            session.setStatus(MockInterviewSession.Status.ABORTED);
            session.setEndedAt(now);
        }
        sessionRepo.saveAll(stale);
        log.info("已将 {} 场超过 {} 小时未活动的面试标记为 ABORTED", stale.size(), hours);
    }
}
