package com.wuyunbin.rag.desktop;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.http.server.RequestPath;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

final class DesktopSessionFilter extends OncePerRequestFilter {
    private final byte[] token;
    private final DesktopActivity activity;
    private final DesktopSettingsStore store;
    private final Runnable stop;
    private static final List<PathPattern> INDEX_WRITE_PATHS = List.of("/api/documents", "/api/documents/word",
            "/api/documents/pdf", "/api/documents/excel", "/api/documents/{documentId}/reindex")
            .stream().map(PathPatternParser.defaultInstance::parse).toList();

    DesktopSessionFilter(String token, DesktopActivity activity, DesktopSettingsStore store) {
        this(token, activity, store, () -> {});
    }

    DesktopSessionFilter(String token, DesktopActivity activity, DesktopSettingsStore store, Runnable stop) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{32,128}")) {
            throw new IllegalStateException("请从 Roch 启动器打开桌面服务。");
        }
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.activity = activity;
        this.store = store;
        this.stop = stop;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = "127.0.0.1:" + request.getLocalPort();
        if (!host.equals(request.getHeader("Host")) || !hasSession(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "DENY");
        if (request.getMethod().equals("GET") || request.getMethod().equals("HEAD")) {
            chain.doFilter(request, response);
            return;
        }

        String origin = request.getHeader("Origin");
        if (!("http://" + host).equals(origin)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        if (request.getRequestURI().equals("/api/desktop/shutdown")) {
            chain.doFilter(request, response);
            if (response.getStatus() == HttpServletResponse.SC_NO_CONTENT) {
                response.flushBuffer();
                Thread.ofPlatform().name("roch-desktop-shutdown").start(stop);
            }
            return;
        }
        var path = RequestPath.parse(request.getRequestURI(), request.getContextPath()).pathWithinApplication();
        boolean indexing = request.getMethod().equals("POST") && INDEX_WRITE_PATHS.stream().anyMatch(pattern -> pattern.matches(path));
        if (!(indexing ? activity.beginIndexWrite(store) : activity.beginWrite())) {
            response.setStatus(indexing ? HttpServletResponse.SC_CONFLICT : HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(indexing
                    ? "{\"error\":\"请先完成嵌入模型配置，或重启以应用已保存的配置。\"}"
                    : "{\"error\":\"Roch 正在退出或重启。\"}");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (indexing) activity.endIndexWrite();
            else activity.endWrite();
        }
    }

    private boolean hasSession(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        return cookies != null && Arrays.stream(cookies).anyMatch(cookie -> "roch_desktop".equals(cookie.getName())
                && MessageDigest.isEqual(token, cookie.getValue().getBytes(StandardCharsets.UTF_8)));
    }
}
