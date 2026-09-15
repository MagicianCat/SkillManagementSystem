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
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseCookie;
import java.time.Duration;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;
    private final com.company.skillplatform.auth.application.FeishuAuthService feishu;
    private final com.company.skillplatform.auth.application.IdeAuthorizationService ide;
    private final com.company.skillplatform.auth.application.IdeAuthorizationRateLimiter ideRateLimiter;
    private final com.company.skillplatform.auth.application.BrowserSessionService browserSessions;
    private final boolean cookieSecure;
    private final long cookieMaxAgeSeconds;
    private static final String BROWSER_COOKIE = "sms_browser_session";
    public AuthController(AuthService service,com.company.skillplatform.auth.application.FeishuAuthService feishu,
                          com.company.skillplatform.auth.application.IdeAuthorizationService ide,
                          com.company.skillplatform.auth.application.IdeAuthorizationRateLimiter ideRateLimiter,
                          com.company.skillplatform.auth.application.BrowserSessionService browserSessions,
                          @Value("${auth.browser.cookie-secure:false}") boolean cookieSecure,
                          @Value("${auth.browser.cookie-max-age-seconds:604800}") long cookieMaxAgeSeconds) { this.service = service; this.feishu=feishu; this.ide=ide; this.ideRateLimiter=ideRateLimiter; this.browserSessions=browserSessions; this.cookieSecure=cookieSecure; this.cookieMaxAgeSeconds=cookieMaxAgeSeconds; }
    @GetMapping("/oauth/feishu/authorize") AuthorizeResponse feishuAuthorize(@RequestParam(required=false, defaultValue="/skills") String redirectPath){return new AuthorizeResponse(feishu.authorize(redirectPath));}
    @GetMapping("/feishu/authorize") ResponseEntity<Void> legacyFeishuAuthorize(){return ResponseEntity.status(302).header(HttpHeaders.LOCATION,feishu.authorize("/skills")).build();}
    @GetMapping("/feishu/callback") BrowserAuthResult feishuCallback(@RequestParam String code,@RequestParam String state,HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response){return browserLogin(code,state,request,response);}
    @PostMapping("/oauth/feishu/callback") BrowserAuthResult feishuCallback(@Valid @RequestBody OAuthCallbackRequest request,HttpServletRequest servletRequest, jakarta.servlet.http.HttpServletResponse response){return browserLogin(request.code(),request.state(),servletRequest,response);}
    @GetMapping("/session") BrowserAuthResult session(@CookieValue(name=BROWSER_COOKIE, required=false) String cookie) { return toBrowserResult(browserSessions.restore(cookie)); }
    @PostMapping("/refresh")
    TokenResult refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest servletRequest) {
        return service.refresh(request.refreshToken(), servletRequest.getHeader(HttpHeaders.USER_AGENT));
    }
    @PostMapping("/logout")
    void logout(@RequestBody(required=false) RefreshRequest request, @CookieValue(name=BROWSER_COOKIE, required=false) String cookie, jakarta.servlet.http.HttpServletResponse response) { browserSessions.revoke(cookie); if (request != null && request.refreshToken() != null) service.logout(request.refreshToken()); clearCookie(response); }
    @GetMapping("/feishu/document-access")
    DocumentAccess documentAccess(Authentication authentication) { return new DocumentAccess(feishu.documentAccessStatus(Long.valueOf(authentication.getName()))); }

    @PostMapping("/ide/authorizations")
    com.company.skillplatform.auth.application.IdeAuthorizationService.AuthorizationResult createIdeAuthorization(
            @Valid @RequestBody IdeAuthorizationRequest request,HttpServletRequest servletRequest) {
        ideRateLimiter.checkCreate(servletRequest.getRemoteAddr());
        return ide.create(request.clientName(), request.codeChallenge(), request.codeChallengeMethod());
    }

    @GetMapping("/ide/authorizations")
    com.company.skillplatform.auth.application.IdeAuthorizationService.AuthorizationView getIdeAuthorization(
            @RequestParam @jakarta.validation.constraints.Pattern(regexp="(?i)[A-HJ-NP-Z2-9]{4}-?[A-HJ-NP-Z2-9]{4}") String userCode,
            HttpServletRequest servletRequest) {
        ideRateLimiter.checkApprove(servletRequest.getRemoteAddr());
        return ide.get(userCode);
    }

    @PostMapping("/ide/authorizations/approve")
    void approveIdeAuthorization(@Valid @RequestBody IdeApprovalRequest request, Authentication authentication,HttpServletRequest servletRequest) {
        ideRateLimiter.checkApprove(servletRequest.getRemoteAddr());
        ide.approve(request.userCode(), Long.valueOf(authentication.getName()));
    }

    @PostMapping("/ide/token")
    ResponseEntity<?> exchangeIdeToken(@Valid @RequestBody IdeTokenRequest request, HttpServletRequest servletRequest) {
        ideRateLimiter.checkPoll(servletRequest.getRemoteAddr(),request.deviceCode());
        var result = ide.exchange(request.deviceCode(), request.codeVerifier(), servletRequest.getHeader(HttpHeaders.USER_AGENT));
        return result.status() == com.company.skillplatform.auth.application.IdeAuthorizationService.ExchangeStatus.PENDING
                ? ResponseEntity.accepted().body(new IdeTokenPending("authorization_pending"))
                : ResponseEntity.ok(result.tokens());
    }

    public record RefreshRequest(@NotBlank String refreshToken) {}
    public record OAuthCallbackRequest(@NotBlank String code, @NotBlank String state) {}
    public record AuthorizeResponse(String authorizeUrl) {}
    public record DocumentAccess(String status) {}
    public record IdeAuthorizationRequest(@NotBlank @jakarta.validation.constraints.Size(max=128) String clientName,
                                          @NotBlank @jakarta.validation.constraints.Pattern(regexp="[A-Za-z0-9_-]{43,128}") String codeChallenge,
                                          @NotBlank String codeChallengeMethod) {}
    public record IdeApprovalRequest(@NotBlank @jakarta.validation.constraints.Pattern(regexp="(?i)[A-HJ-NP-Z2-9]{4}-?[A-HJ-NP-Z2-9]{4}") String userCode) {}
    public record IdeTokenRequest(@NotBlank @jakarta.validation.constraints.Pattern(regexp="[A-Za-z0-9_-]{43}") String deviceCode,
                                  @NotBlank @jakarta.validation.constraints.Pattern(regexp="[A-Za-z0-9._~-]{43,128}") String codeVerifier) {}
    public record IdeTokenPending(String status) {}
    public record BrowserAuthResult(String accessToken, long expiresIn, com.company.skillplatform.auth.domain.AuthenticatedUser user) {}
    private BrowserAuthResult browserLogin(String code,String state,HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) { var result=feishu.callback(code,state,request.getHeader(HttpHeaders.USER_AGENT)); var issued=browserSessions.issue(result.user().id(),request.getHeader(HttpHeaders.USER_AGENT)); setCookie(response,issued.sessionToken()); return toBrowserResult(issued); }
    private BrowserAuthResult toBrowserResult(com.company.skillplatform.auth.application.BrowserSessionService.Issued issued) { return new BrowserAuthResult(issued.accessToken(),issued.expiresIn(),issued.user()); }
    private void setCookie(jakarta.servlet.http.HttpServletResponse response,String value) { response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(BROWSER_COOKIE,value).httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/").maxAge(Duration.ofSeconds(cookieMaxAgeSeconds)).build().toString()); }
    private void clearCookie(jakarta.servlet.http.HttpServletResponse response) { response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(BROWSER_COOKIE,"").httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/").maxAge(Duration.ZERO).build().toString()); }
}
