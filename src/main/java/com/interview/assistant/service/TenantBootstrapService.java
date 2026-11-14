package com.interview.assistant.service;

import com.interview.assistant.entity.AppUser;
import com.interview.assistant.entity.Tenant;
import com.interview.assistant.repository.*;
import com.interview.assistant.security.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 多租户迁移：把升级前没有 tenant_id 的历史数据挂到「默认空间」，
 * 并在首次迁移时创建一个可登录的管理员账号（密码可用 APP_BOOTSTRAP_PASSWORD 指定，
 * 未指定时随机生成并打印到启动日志）。
 */
@Component
public class TenantBootstrapService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantBootstrapService.class);
    private static final String PASSWORD_ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantRepository tenantRepo;
    private final AppUserRepository userRepo;
    private final MockInterviewSessionRepository sessionRepo;
    private final UserResumeRepository resumeRepo;
    private final ApplicationRecordRepository applicationRepo;
    private final UserInterviewRecordRepository recordRepo;
    private final InterviewChatSessionRepository chatSessionRepo;
    private final ToolChatSessionRepository toolSessionRepo;

    @Value("${app.auth.bootstrap-password:}")
    private String bootstrapPassword;

    public TenantBootstrapService(TenantRepository tenantRepo,
                                  AppUserRepository userRepo,
                                  MockInterviewSessionRepository sessionRepo,
                                  UserResumeRepository resumeRepo,
                                  ApplicationRecordRepository applicationRepo,
                                  UserInterviewRecordRepository recordRepo,
                                  InterviewChatSessionRepository chatSessionRepo,
                                  ToolChatSessionRepository toolSessionRepo) {
        this.tenantRepo = tenantRepo;
        this.userRepo = userRepo;
        this.sessionRepo = sessionRepo;
        this.resumeRepo = resumeRepo;
        this.applicationRepo = applicationRepo;
        this.recordRepo = recordRepo;
        this.chatSessionRepo = chatSessionRepo;
        this.toolSessionRepo = toolSessionRepo;
    }

    @Override
    @Transactional
    public void run(String... args) {
        long totalRows = sessionRepo.count() + resumeRepo.count() + applicationRepo.count()
                + recordRepo.count() + chatSessionRepo.count() + toolSessionRepo.count();
        if (totalRows == 0) {
            return;
        }

        List<Tenant> tenants = tenantRepo.findAll();
        Tenant tenant;
        if (tenants.isEmpty()) {
            tenant = tenantRepo.save(new Tenant("默认空间"));
            createAdmin(tenant);
            log.warn("检测到 {} 条历史数据，已归入「默认空间」", totalRows);
        } else {
            tenant = tenants.get(0);
        }

        int migrated = sessionRepo.assignTenantIdToLegacy(tenant.getId())
                + resumeRepo.assignTenantIdToLegacy(tenant.getId())
                + applicationRepo.assignTenantIdToLegacy(tenant.getId())
                + recordRepo.assignTenantIdToLegacy(tenant.getId())
                + chatSessionRepo.assignTenantIdToLegacy(tenant.getId())
                + toolSessionRepo.assignTenantIdToLegacy(tenant.getId());
        if (migrated > 0) {
            log.info("已将 {} 条历史数据归属到租户 {}", migrated, tenant.getId());
        }
    }

    private void createAdmin(Tenant tenant) {
        String username = "admin";
        if (userRepo.existsByUsername(username)) {
            return;
        }
        boolean generated = bootstrapPassword == null || bootstrapPassword.isBlank();
        String password = generated ? randomPassword() : bootstrapPassword;

        AppUser admin = new AppUser();
        admin.setTenantId(tenant.getId());
        admin.setUsername(username);
        admin.setPasswordHash(PasswordHasher.hash(password));
        admin.setDisplayName("管理员");
        admin.setRole("OWNER");
        admin.setLastLoginAt(LocalDateTime.now());
        userRepo.save(admin);

        if (generated) {
            log.warn("""
                    
                    ============================================================
                     已为历史数据创建默认账号
                       用户名: admin
                       密码:   {}
                     请登录后立即在「账号」里修改密码
                    ============================================================
                    """, password);
        } else {
            log.info("已使用 app.auth.bootstrap-password 创建默认账号 admin（租户 {}）", tenant.getId());
        }
    }

    private String randomPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }
}
