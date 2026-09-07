package com.company.skillplatform.auth.infrastructure;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix="skill-platform.feishu")
public record FeishuProperties(boolean enabled,String appId,String appSecret,String redirectUri,String authorizeUrl,String tokenUrl,String userInfoUrl,String apiBaseUrl, boolean eventEnabled, Duration oauthStateTtl) {}
