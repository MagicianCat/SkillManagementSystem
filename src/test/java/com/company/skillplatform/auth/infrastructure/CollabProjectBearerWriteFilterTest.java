package com.company.skillplatform.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CollabProjectBearerWriteFilterTest {
    private final CollabProjectBearerWriteFilter filter = new CollabProjectBearerWriteFilter(new SecurityErrorWriter(new ObjectMapper().findAndRegisterModules()));

    @Test void cookieOnlyWriteIsRejected() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/collab-projects");
        request.setCookies(new jakarta.servlet.http.Cookie("sms_browser_session", "cookie-session"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> reached.set(true));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("BEARER_REQUIRED");
        assertThat(reached).isFalse();
    }

    @Test void validatedBearerWriteContinues() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/collab-projects");
        request.addHeader("Authorization", "Bearer valid-token");
        request.setAttribute("sms.auth.verifiedBearer", Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> reached.set(true));
        assertThat(reached).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test void readsRemainAvailableToBrowserCookieSession() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/collab-projects");
        request.setCookies(new jakarta.servlet.http.Cookie("sms_browser_session", "cookie-session"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> reached.set(true));
        assertThat(reached).isTrue();
    }
}
