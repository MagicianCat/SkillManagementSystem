package com.company.skillplatform.common.logging;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 日志上下文：统一封装 requestId、actorId、clientIp 的获取与 MDC 读写。 */
public final class LogContext {

    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_TASK_ID = "taskId";
    public static final String MDC_ATTEMPT = "attempt";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    private LogContext() {}

    /** 请求 ID：优先 MDC（由 RequestIdFilter 写入），其次 Servlet 容器生成的 requestId。 */
    public static String requestId() {
        String value = MDC.get(MDC_REQUEST_ID);
        return value == null ? "-" : value;
    }

    /** 从请求头或 MDC 解析请求 ID，用于过滤器内初始化 MDC。 */
    public static String resolveRequestId(HttpServletRequest request) {
        String header = request.getHeader(HEADER_REQUEST_ID);
        if (header != null && !header.isBlank()) return header.trim();
        String containerId = request.getRequestId();
        return containerId == null || containerId.isBlank() ? "-" : containerId;
    }

    /** 当前登录用户 ID，未认证返回 "-"；不抛异常，保证日志语句永不影响业务。 */
    public static String actorId() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()) return "-";
            Object principal = authentication.getPrincipal();
            return principal == null ? "-" : String.valueOf(principal);
        } catch (RuntimeException ex) {
            return "-";
        }
    }

    /** 客户端 IP：仅取代理头首个地址或远端地址，不记录端口。 */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return request.getRemoteAddr() == null ? "-" : request.getRemoteAddr();
    }

    public static void putTask(String taskId, Integer attempt) {
        if (taskId != null) MDC.put(MDC_TASK_ID, taskId);
        if (attempt != null) MDC.put(MDC_ATTEMPT, String.valueOf(attempt));
    }

    public static void clearTask() {
        MDC.remove(MDC_TASK_ID);
        MDC.remove(MDC_ATTEMPT);
    }
}
