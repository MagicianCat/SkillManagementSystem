package com.company.skillplatform.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 验证请求日志过滤器：requestId 透传/生成、MDC 清理与完成日志。 */
class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @AfterEach void tearDown() { MDC.clear(); }

    @Test void propagatesIncomingRequestIdAndLogsCompletion() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/skills");
        request.addHeader("X-Request-Id", "client-supplied-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ListAppender<ILoggingEvent> appender = attach();
        AtomicReference<String> mdcInsideChain = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> mdcInsideChain.set(MDC.get("requestId")));
        assertThat(mdcInsideChain.get()).isEqualTo("client-supplied-id");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("client-supplied-id");
        ILoggingEvent event = appender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("event=http.request.completed")).findFirst().orElseThrow();
        assertThat(event.getFormattedMessage()).contains("requestId=client-supplied-id", "method=GET",
                "path=/api/v1/skills", "status=200", "durationMs=");
        assertThat(MDC.get("requestId")).as("MDC must be cleaned after request").isNull();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RequestLoggingFilter.class)).detachAppender(appender);
    }

    @Test void generatesRequestIdWhenHeaderMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ListAppender<ILoggingEvent> appender = attach();
        filter.doFilter(request, response, (req, res) -> { });
        String header = response.getHeader("X-Request-Id");
        assertThat(header).isNotBlank();
        assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList().toString())
                .contains("requestId=" + header);
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RequestLoggingFilter.class)).detachAppender(appender);
    }

    @Test void neverLogsAuthorizationOrBody() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.addHeader("Authorization", "Bearer secret-jwt-token");
        request.setContent("{\"username\":\"u\",\"password\":\"p\"}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        ListAppender<ILoggingEvent> appender = attach();
        filter.doFilter(request, response, (req, res) -> { });
        String all = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList().toString();
        assertThat(all).doesNotContain("secret-jwt-token", "password", "Bearer");
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RequestLoggingFilter.class)).detachAppender(appender);
    }

    private ListAppender<ILoggingEvent> attach() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>(); appender.start(); logger.addAppender(appender);
        return appender;
    }
}
