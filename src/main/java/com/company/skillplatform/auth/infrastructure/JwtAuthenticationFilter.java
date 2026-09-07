package com.company.skillplatform.auth.infrastructure;

import com.company.skillplatform.auth.application.AuthService;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.common.logging.LogContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.company.skillplatform.agent.application.AgentRunService;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final JwtTokenService tokens;
    private final AuthService authService;
    private final AgentRunService agentRuns;
    @Autowired
    public JwtAuthenticationFilter(JwtTokenService tokens, AuthService authService, AgentRunService agentRuns) {
        this.tokens = tokens;
        this.authService = authService;
        this.agentRuns = agentRuns;
    }
    /** Backward-compatible constructor for existing unit tests and embedders. */
    public JwtAuthenticationFilter(JwtTokenService tokens, AuthService authService) {
        this(tokens, authService, null);
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = tokens.parse(header.substring(7));
                Long userId = Long.valueOf(claims.getSubject());
                var currentUser = authService.currentUser(userId);
                var authorities = currentUser.permissions().stream()
                        .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new).toList();
                var authentication = new UsernamePasswordAuthenticationToken(userId, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.info("event=auth.jwt.accepted requestId={} actorId={}", LogContext.requestId(), userId);
            } catch (JwtException | IllegalArgumentException | BusinessException ex) {
                try {
                    if (agentRuns == null) throw new IllegalStateException("agent token service unavailable");
                    var run = agentRuns.require(header.substring(7));
                    var authorities = java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("skill:browse"));
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(run.userId(), null, authorities));
                    log.info("event=auth.agent.accepted requestId={} runRef={}", LogContext.requestId(), run.runRef());
                } catch (Exception ignored) {
                    SecurityContextHolder.clearContext();
                    log.warn("event=auth.jwt.rejected requestId={} errorCode={}", LogContext.requestId(),
                            ex instanceof BusinessException be ? be.getCode()
                                    : ex instanceof io.jsonwebtoken.ExpiredJwtException ? "TOKEN_EXPIRED"
                                    : ex instanceof io.jsonwebtoken.MalformedJwtException ? "TOKEN_MALFORMED" : "TOKEN_INVALID");
                }
            }
        }
        chain.doFilter(request, response);
    }
}
