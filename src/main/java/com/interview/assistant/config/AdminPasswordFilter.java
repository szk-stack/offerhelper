package com.interview.assistant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 历史上用于「部署级管理员密码」，现在接口鉴权已由多租户登录（Bearer 令牌）接管。
 * 这里只保留 /api 的 CORS 预检处理，避免旧前端带 X-Admin-Password 时预检失败。
 */
@Component
@Order(1)
public class AdminPasswordFilter extends OncePerRequestFilter {

    private void addCorsHeaders(HttpServletRequest request, HttpServletResponse response) {
        String origin = request.getHeader("Origin");
        response.setHeader("Access-Control-Allow-Origin", origin != null && !origin.isEmpty() ? origin : "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, X-Admin-Password");
        response.setHeader("Access-Control-Max-Age", "86400");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if (!path.startsWith("/api")) {
            chain.doFilter(request, response);
            return;
        }
        // 多租户登录鉴权已取代部署级管理员密码，这里只保留 CORS 预检处理
        if ("OPTIONS".equalsIgnoreCase(method)) {
            addCorsHeaders(request, response);
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }
        chain.doFilter(request, response);
    }
}
