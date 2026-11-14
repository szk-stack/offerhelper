package com.interview.assistant.service;

import com.interview.assistant.entity.AppUser;
import com.interview.assistant.entity.AuthToken;
import com.interview.assistant.entity.Tenant;
import com.interview.assistant.repository.AppUserRepository;
import com.interview.assistant.repository.AuthTokenRepository;
import com.interview.assistant.repository.TenantRepository;
import com.interview.assistant.security.PasswordHasher;
import com.interview.assistant.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 账号与登录态：注册即创建一个新租户（一人一空间），令牌为服务端可吊销的不透明串。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\u4e00-\\u9fa5]{3,32}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantRepository tenantRepo;
    private final AppUserRepository userRepo;
    private final AuthTokenRepository tokenRepo;

    @Value("${app.auth.token-days:30}")
    private int tokenDays;

    public AuthService(TenantRepository tenantRepo, AppUserRepository userRepo, AuthTokenRepository tokenRepo) {
        this.tenantRepo = tenantRepo;
        this.userRepo = userRepo;
        this.tokenRepo = tokenRepo;
    }

    @Transactional
    public Map<String, Object> register(String username, String password, String displayName) {
        String name = username == null ? "" : username.trim();
        validateUsername(name);
        validatePassword(password);
        if (userRepo.existsByUsername(name)) {
            throw new IllegalArgumentException("用户名已被占用");
        }

        String display = (displayName == null || displayName.isBlank()) ? name : displayName.trim();
        Tenant tenant = tenantRepo.save(new Tenant(display + " 的空间"));

        AppUser user = new AppUser();
        user.setTenantId(tenant.getId());
        user.setUsername(name);
        user.setPasswordHash(PasswordHasher.hash(password));
        user.setDisplayName(display);
        user.setRole("OWNER");
        user.setLastLoginAt(LocalDateTime.now());
        userRepo.save(user);

        log.info("新租户注册成功: user={}, tenantId={}", name, tenant.getId());
        return issueToken(user, tenant);
    }

    @Transactional
    public Map<String, Object> login(String username, String password) {
        String name = username == null ? "" : username.trim();
        AppUser user = userRepo.findByUsername(name)
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
        if (!user.isEnabled()) {
            throw new IllegalArgumentException("账号已被禁用");
        }
        if (!PasswordHasher.verify(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        user.setLastLoginAt(LocalDateTime.now());
        userRepo.save(user);

        // 顺手清理过期令牌，避免表无限增长
        tokenRepo.deleteByExpiresAtBefore(LocalDateTime.now());

        Tenant tenant = tenantRepo.findById(user.getTenantId())
                .orElseThrow(() -> new IllegalStateException("租户数据异常"));
        return issueToken(user, tenant);
    }

    @Transactional
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            tokenRepo.deleteByToken(token);
        }
    }

    public Map<String, Object> me() {
        TenantContext.Principal principal = TenantContext.requirePrincipal();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", principal.userId());
        result.put("username", principal.username());
        result.put("displayName", principal.displayName());
        result.put("role", principal.role());
        result.put("tenantId", principal.tenantId());
        tenantRepo.findById(principal.tenantId()).ifPresent(t -> result.put("tenantName", t.getName()));
        return result;
    }

    @Transactional
    public Map<String, Object> changePassword(String oldPassword, String newPassword) {
        TenantContext.Principal principal = TenantContext.requirePrincipal();
        validatePassword(newPassword);
        AppUser user = userRepo.findById(principal.userId())
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        if (!PasswordHasher.verify(oldPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("原密码不正确");
        }
        user.setPasswordHash(PasswordHasher.hash(newPassword));
        userRepo.save(user);
        // 改密后所有旧令牌失效，返回新令牌保持当前设备登录
        tokenRepo.deleteByUserId(user.getId());
        Tenant tenant = tenantRepo.findById(user.getTenantId())
                .orElseThrow(() -> new IllegalStateException("租户数据异常"));
        return issueToken(user, tenant);
    }

    /** 令牌 → 身份；无效或过期返回 null，不抛异常 */
    public TenantContext.Principal resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return tokenRepo.findByToken(token)
                .filter(t -> t.getExpiresAt() == null || t.getExpiresAt().isAfter(LocalDateTime.now()))
                .map(t -> userRepo.findById(t.getUserId()).orElse(null))
                .filter(AppUser::isEnabled)
                .map(u -> new TenantContext.Principal(
                        u.getId(), u.getTenantId(), u.getUsername(), u.getDisplayName(), u.getRole()))
                .orElse(null);
    }

    private Map<String, Object> issueToken(AppUser user, Tenant tenant) {
        AuthToken token = new AuthToken();
        token.setToken(randomToken());
        token.setUserId(user.getId());
        token.setTenantId(user.getTenantId());
        token.setExpiresAt(LocalDateTime.now().plusDays(Math.max(1, tokenDays)));
        tokenRepo.save(token);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("userId", user.getId());
        profile.put("username", user.getUsername());
        profile.put("displayName", user.getDisplayName());
        profile.put("role", user.getRole());
        profile.put("tenantId", user.getTenantId());
        profile.put("tenantName", tenant != null ? tenant.getName() : null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token.getToken());
        result.put("expiresAt", token.getExpiresAt());
        result.put("user", profile);
        return result;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private void validateUsername(String username) {
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException("用户名需为 3-32 位字母、数字、下划线或中文");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 6 || password.length() > 64) {
            throw new IllegalArgumentException("密码长度需在 6-64 位之间");
        }
    }
}
