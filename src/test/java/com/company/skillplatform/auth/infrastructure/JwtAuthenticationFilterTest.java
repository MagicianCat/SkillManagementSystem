package com.company.skillplatform.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.skillplatform.auth.application.AuthService;
import com.company.skillplatform.auth.domain.AuthenticatedUser;
import com.company.skillplatform.common.application.BusinessException;
import io.jsonwebtoken.Claims;
import java.util.List;
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

    private MockHttpServletRequest requestWithToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token");
        return request;
    }
}
