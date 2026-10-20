package com.company.skillplatform.auth.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Prevents a browser cookie from authorizing writes to the collaboration API. */
@Component
public class CollabProjectBearerWriteFilter extends OncePerRequestFilter {
    private final SecurityErrorWriter errors;

    public CollabProjectBearerWriteFilter(SecurityErrorWriter errors) {
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        return path == null || !(path.equals("/api/v1/collab-projects") || path.startsWith("/api/v1/collab-projects/")
                || path.equals("/api/v1/collab-groups") || path.startsWith("/api/v1/collab-groups/")
                || path.equals("/api/v1/collab-requirements") || path.startsWith("/api/v1/collab-requirements/"))
                || !("POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!Boolean.TRUE.equals(request.getAttribute("sms.auth.verifiedBearer"))) {
            errors.write(response, HttpServletResponse.SC_UNAUTHORIZED, "BEARER_REQUIRED",
                    "A valid bearer token is required for project changes", request.getRequestId());
            return;
        }
        chain.doFilter(request, response);
    }
}
