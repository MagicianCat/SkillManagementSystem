package com.company.skillplatform.auth.infrastructure;

import com.company.skillplatform.common.logging.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfiguration {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(10); }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwt,
                                                  SecurityErrorWriter errors) throws Exception {
        return http.csrf(csrf -> csrf.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh", "/actuator/health").permitAll()
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
                .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class).build();
    }
}
