package com.company.skillplatform.auth.infrastructure;

import com.company.skillplatform.common.logging.LogContext;
import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.beans.factory.annotation.Value;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class,FeishuProperties.class})
public class SecurityConfiguration {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwt, BrowserSessionAuthenticationFilter browser, @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource,
                                                  SecurityErrorWriter errors) throws Exception {
        return http.cors(cors -> cors.configurationSource(corsSource)).csrf(csrf -> csrf.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST,"/api/v1/auth/ide/authorizations","/api/v1/auth/ide/token").permitAll()
                        .requestMatchers("/api/v1/auth/refresh",
                                "/api/v1/auth/oauth/feishu/authorize", "/api/v1/auth/oauth/feishu/callback",
                                "/api/v1/auth/feishu/authorize", "/api/v1/auth/feishu/callback",
                                "/actuator/health").permitAll()
                        .requestMatchers("/internal/mcp", "/internal/mcp/**").hasAuthority("agent:mcp")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, cause) -> {
                            log.warn("event=auth.authentication.required requestId={} method={} path={} clientIp={}",
                                    LogContext.requestId(), req.getMethod(), req.getRequestURI(), LogContext.clientIp(req));
                            errors.write(res, 401,
                                    "AUTHENTICATION_REQUIRED", "Authentication is required", req.getRequestId());
                        })
                        .accessDeniedHandler((req, res, cause) -> {
                            log.warn("event=auth.access.denied requestId={} actorId={} method={} path={} errorCode={}",
                                    LogContext.requestId(), LogContext.actorId(), req.getMethod(), req.getRequestURI(), "ACCESS_DENIED");
                            errors.write(res, 403,
                                    "ACCESS_DENIED", "Access is denied", req.getRequestId());
                        }))
                .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(browser, JwtAuthenticationFilter.class).build();
    }
    @Bean CorsConfigurationSource corsConfigurationSource(@Value("${auth.browser.allowed-origins:http://127.0.0.1:5173,http://localhost:5173}") String origins) {
        CorsConfiguration config = new CorsConfiguration(); config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList());
        config.setAllowedMethods(java.util.List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS")); config.setAllowedHeaders(java.util.List.of("Authorization","Content-Type","Accept","X-Request-Id")); config.setAllowCredentials(true); config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/api/**", config); return source;
    }
}
