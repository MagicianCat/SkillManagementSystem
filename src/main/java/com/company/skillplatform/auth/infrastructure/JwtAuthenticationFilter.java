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
import com.company.skillplatform.user.infrastructure.repository.ScopedRoleAssignmentRepository;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final JwtTokenService tokens;
    private final AuthService authService;
    private final AgentRunService agentRuns;
    private final ScopedRoleAssignmentRepository scopedRoles;
    @Autowired
    public JwtAuthenticationFilter(JwtTokenService tokens, AuthService authService, AgentRunService agentRuns, ScopedRoleAssignmentRepository scopedRoles) {
        this.tokens = tokens; this.authService = authService; this.agentRuns = agentRuns; this.scopedRoles = scopedRoles;
    }
    /** Backward-compatible constructor for existing unit tests and embedders. */
    public JwtAuthenticationFilter(JwtTokenService tokens, AuthService authService) {
        this(tokens, authService, null, null);
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = tokens.parse(header.substring(7));
                Long userId = Long.valueOf(claims.getSubject());
                var currentUser = authService.currentUser(userId);
                var permissions = new java.util.HashSet<>(currentUser.permissions());
                if (scopedRoles != null) scopedRoles.findByUserId(userId).forEach(a -> {
                    if (java.util.Set.of("TEAM_ADMIN","TEAM_MAINTAINER","PLATFORM_MAINTAINER").contains(a.getRoleKey())) permissions.addAll(java.util.List.of("skill:browse","skill:download","skill:upload","skill:edit"));
                    if (java.util.Set.of("TEAM_ADMIN").contains(a.getRoleKey())) {
                        permissions.add("skill:review");
                        permissions.add("admin:telemetry");
                    }
                });
                var authorities = permissions.stream()
                        .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new).toList();
                var authentication = new UsernamePasswordAuthenticationToken(userId, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.info("event=auth.jwt.accepted requestId={} actorId={}", LogContext.requestId(), userId);
            } catch (JwtException | IllegalArgumentException | BusinessException ex) {
                try {
                    if (request.getRequestURI() == null || !request.getRequestURI().startsWith("/internal/mcp")) throw new IllegalStateException("agent token not valid on this route");
                    if (agentRuns == null) throw new IllegalStateException("agent token service unavailable");
                    var run = agentRuns.require(header.substring(7));
                    var authorities = java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("agent:mcp"), new org.springframework.security.core.authority.SimpleGrantedAuthority("skill:browse"));
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
