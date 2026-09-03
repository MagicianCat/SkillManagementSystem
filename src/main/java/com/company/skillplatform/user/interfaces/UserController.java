package com.company.skillplatform.user.interfaces;

import com.company.skillplatform.auth.application.AuthService;
import com.company.skillplatform.auth.domain.AuthenticatedUser;
import com.company.skillplatform.common.interfaces.PageResponse;
import com.company.skillplatform.user.application.UserCandidateService;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final AuthService authService;
    private final UserCandidateService candidates;
    public UserController(AuthService authService,UserCandidateService candidates) { this.authService = authService;this.candidates=candidates; }
    @GetMapping("/me")
    AuthenticatedUser me(Authentication authentication) {
        return authService.currentUser((Long) authentication.getPrincipal());
    }
    @GetMapping("/candidates") PageResponse<UserCandidateService.CandidateView> candidates(@RequestParam(required=false)String keyword,Pageable pageable){return PageResponse.from(candidates.search(keyword,pageable),v->v);}
}
