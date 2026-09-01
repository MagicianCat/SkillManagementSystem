package com.company.skillplatform.auth.interfaces;

import com.company.skillplatform.auth.application.AuthService;
import com.company.skillplatform.auth.application.AuthService.TokenResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;
    public AuthController(AuthService service) { this.service = service; }
    @PostMapping("/login")
    TokenResult login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return service.login(request.username(), request.password(), request.provider(), servletRequest.getHeader(HttpHeaders.USER_AGENT));
    }
    @PostMapping("/refresh")
    TokenResult refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest servletRequest) {
        return service.refresh(request.refreshToken(), servletRequest.getHeader(HttpHeaders.USER_AGENT));
    }
    @PostMapping("/logout")
    void logout(@Valid @RequestBody RefreshRequest request) { service.logout(request.refreshToken()); }

    public record LoginRequest(@NotBlank String username, @NotBlank String password, @NotBlank String provider) {}
    public record RefreshRequest(@NotBlank String refreshToken) {}
}
