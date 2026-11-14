package com.interview.assistant.security;

/**
 * 当前请求的登录身份。由 AuthFilter 写入，请求结束后清理。
 * 业务代码统一从这里取 tenantId，不要相信前端传来的租户参数。
 */
public final class TenantContext {

    public record Principal(Long userId, Long tenantId, String username, String displayName, String role) {
    }

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Principal principal) {
        HOLDER.set(principal);
    }

    public static Principal current() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static Long tenantId() {
        Principal p = HOLDER.get();
        return p == null ? null : p.tenantId();
    }

    public static Long userId() {
        Principal p = HOLDER.get();
        return p == null ? null : p.userId();
    }

    public static Long requireTenantId() {
        Principal p = HOLDER.get();
        if (p == null || p.tenantId() == null) {
            throw new AuthException("请先登录");
        }
        return p.tenantId();
    }

    public static Principal requirePrincipal() {
        Principal p = HOLDER.get();
        if (p == null) {
            throw new AuthException("请先登录");
        }
        return p;
    }
}
