package com.company.skillplatform.auth.infrastructure;

import com.company.skillplatform.auth.application.BrowserSessionService;
import com.company.skillplatform.common.application.BusinessException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class BrowserSessionAuthenticationFilter extends OncePerRequestFilter {
    private final BrowserSessionService sessions;
    public BrowserSessionAuthenticationFilter(BrowserSessionService sessions) { this.sessions = sessions; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            Cookie[] cookies=request.getCookies(); String raw=null;
            if(cookies!=null) for(Cookie c:cookies) if("sms_browser_session".equals(c.getName())) raw=c.getValue();
            if(raw!=null) try { var u=sessions.restore(raw).user(); var authorities=u.permissions().stream().map(org.springframework.security.core.authority.SimpleGrantedAuthority::new).toList(); SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u.id(),null,authorities)); } catch(BusinessException ignored) { }
        }
        chain.doFilter(request,response);
    }
}
