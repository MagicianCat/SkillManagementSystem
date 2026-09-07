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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;
    private final com.company.skillplatform.auth.application.FeishuAuthService feishu;
    public AuthController(AuthService service,com.company.skillplatform.auth.application.FeishuAuthService feishu) { this.service = service; this.feishu=feishu; }
    @GetMapping("/providers") Providers providers(){return new Providers(true, feishu.isConfigured() ? java.util.List.of("feishu") : java.util.List.of());}
    @GetMapping("/oauth/feishu/authorize") AuthorizeResponse feishuAuthorize(@RequestParam(required=false, defaultValue="/skills") String redirectPath){return new AuthorizeResponse(feishu.authorize(redirectPath));}
    @GetMapping("/feishu/authorize") ResponseEntity<Void> legacyFeishuAuthorize(){return ResponseEntity.status(302).header(HttpHeaders.LOCATION,feishu.authorize("/skills")).build();}
    @GetMapping("/feishu/callback") AuthService.TokenResult feishuCallback(@RequestParam String code,@RequestParam String state,HttpServletRequest request){return feishu.callback(code,state,request.getHeader(HttpHeaders.USER_AGENT));}
    @PostMapping("/oauth/feishu/callback") AuthService.TokenResult feishuCallback(@Valid @RequestBody OAuthCallbackRequest request,HttpServletRequest servletRequest){return feishu.callback(request.code(),request.state(),servletRequest.getHeader(HttpHeaders.USER_AGENT));}
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
    public record OAuthCallbackRequest(@NotBlank String code, @NotBlank String state) {}
    public record AuthorizeResponse(String authorizeUrl) {}
    public record Providers(boolean passwordLogin, java.util.List<String> providers) {}
}
