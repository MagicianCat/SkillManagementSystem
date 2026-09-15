package com.company.skillplatform.auth.infrastructure;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix="skill-platform.feishu")
public record FeishuProperties(boolean enabled,String appId,String appSecret,String redirectUri,
                               boolean mockHttpsEnabled, String mockHttpsBaseUrl,
                               String authorizeUrl,String tokenUrl,String userInfoUrl,String apiBaseUrl,
                               boolean eventEnabled, Duration oauthStateTtl) {
    public String effectiveRedirectUri() {
        if (mockHttpsEnabled && mockHttpsBaseUrl != null && !mockHttpsBaseUrl.isBlank()) {
            return mockHttpsBaseUrl.replaceAll("/$", "") + "/oauth/callback";
        }
        return redirectUri == null || redirectUri.isBlank()
                ? "http://127.0.0.1:5173/oauth/callback" : redirectUri;
    }
}
