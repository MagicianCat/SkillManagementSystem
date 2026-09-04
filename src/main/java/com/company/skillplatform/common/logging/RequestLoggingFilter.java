package com.company.skillplatform.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求链路日志过滤器：生成/透传 X-Request-Id 写入 MDC，并记录请求开始与结束。
 * 只记录方法、路径、状态码、耗时、用户 ID 和客户端 IP；不读取请求体，不记录
 * 密码、Token、Authorization 头或任何文件内容。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = LogContext.resolveRequestId(request);
        MDC.put(LogContext.MDC_REQUEST_ID, requestId);
        response.setHeader(LogContext.HEADER_REQUEST_ID, requestId);
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            int status = response.getStatus();
            String message = "event=http.request.completed requestId={} actorId={} method={} path={} status={} durationMs={} clientIp={}";
            if (status >= 500) {
                log.error(message, requestId, LogContext.actorId(), request.getMethod(),
                        request.getRequestURI(), status, durationMs, LogContext.clientIp(request));
            } else {
                log.info(message, requestId, LogContext.actorId(), request.getMethod(),
                        request.getRequestURI(), status, durationMs, LogContext.clientIp(request));
            }
            MDC.remove(LogContext.MDC_REQUEST_ID);
        }
    }
}
