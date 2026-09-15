package com.company.skillplatform.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.skillplatform.auth.application.AuthService;
import com.company.skillplatform.auth.domain.AuthenticatedUser;
import com.company.skillplatform.agent.application.AgentRunService;
import com.company.skillplatform.agent.domain.AgentRun;
import com.company.skillplatform.common.application.BusinessException;
import io.jsonwebtoken.Claims;
import java.util.List;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {
    private final JwtTokenService tokens = mock(JwtTokenService.class);
    private final AuthService auth = mock(AuthService.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokens, auth);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void usesCurrentDatabasePermissionsInsteadOfJwtPermissionSnapshot() throws Exception {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("7");
        when(tokens.parse("token")).thenReturn(claims);
        when(auth.currentUser(7L)).thenReturn(new AuthenticatedUser(7L, "user", "User",
                List.of("CONSUMER"), List.of("skill:browse")));
        MockHttpServletRequest request = requestWithToken();

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(7L);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority").containsExactly("skill:browse");
    }

    @Test
    void rejectsTokenWhenCurrentUserIsDisabledOrMissing() throws Exception {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("7");
        when(tokens.parse("token")).thenReturn(claims);
        when(auth.currentUser(7L)).thenThrow(new BusinessException("AUTHENTICATION_INVALID",
                "Authentication is no longer valid", HttpStatus.UNAUTHORIZED));

        filter.doFilter(requestWithToken(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void agentTokenCannotAuthenticateOrdinaryApiRoutes() throws Exception {
        AgentRunService agentRuns = mock(AgentRunService.class);
        JwtAuthenticationFilter scoped = new JwtAuthenticationFilter(tokens, auth, agentRuns, null);
        when(tokens.parse("token")).thenThrow(new BusinessException("AUTHENTICATION_INVALID", "invalid", HttpStatus.UNAUTHORIZED));
        MockHttpServletRequest request = requestWithToken();
        request.setRequestURI("/api/v1/wiki/documents");

        scoped.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void agentTokenIsOnlyAcceptedOnMcpRoute() throws Exception {
        AgentRunService agentRuns = mock(AgentRunService.class);
        JwtAuthenticationFilter scoped = new JwtAuthenticationFilter(tokens, auth, agentRuns, null);
        when(tokens.parse("token")).thenThrow(new BusinessException("AUTHENTICATION_INVALID", "invalid", HttpStatus.UNAUTHORIZED));
        when(agentRuns.require("token")).thenReturn(new AgentRun("run", 7L, "skill-advisor", null, null, Instant.now().plusSeconds(60), "ACTIVE"));
        MockHttpServletRequest request = requestWithToken();
        request.setRequestURI("/internal/mcp");

        scoped.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority").contains("agent:mcp");
    }

    private MockHttpServletRequest requestWithToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token");
        return request;
    }
}
