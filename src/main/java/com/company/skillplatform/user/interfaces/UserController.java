package com.company.skillplatform.user.interfaces;

import com.company.skillplatform.auth.application.AuthService;
import com.company.skillplatform.auth.domain.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final AuthService authService;
    public UserController(AuthService authService) { this.authService = authService; }
    @GetMapping("/me")
    AuthenticatedUser me(Authentication authentication) {
        return authService.currentUser((Long) authentication.getPrincipal());
    }
}
