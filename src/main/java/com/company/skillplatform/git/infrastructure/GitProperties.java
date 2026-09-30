package com.company.skillplatform.git.infrastructure;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("skill-platform.git")
public class GitProperties {
    private boolean enabled; private String provider="GENERIC"; private String urlMode="BASE_RELATIVE";
    private String baseUrl=""; private String allowedHosts=""; private String authMode="NONE";
    private String username=""; private String token=""; private Duration connectTimeout=Duration.ofSeconds(5);
    private Duration readTimeout=Duration.ofSeconds(20); private Duration pollInterval=Duration.ofSeconds(15);
    private Duration pollMaxBackoff=Duration.ofMinutes(2); private int pollBatchSize=20;
    private int maxRepositoriesPerProject=10; private int maxBranchesPerRepository=100;
    private boolean cacheEnabled=true; private Path cacheRoot=Path.of(".runtime/git-cache");
    public Set<String> allowedHostSet(){return Arrays.stream(allowedHosts.split(",")).map(String::trim).filter(s->!s.isBlank()).map(String::toLowerCase).collect(Collectors.toUnmodifiableSet());}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;} public String getProvider(){return provider;} public void setProvider(String v){provider=v;} public String getUrlMode(){return urlMode;} public void setUrlMode(String v){urlMode=v;} public String getBaseUrl(){return baseUrl;} public void setBaseUrl(String v){baseUrl=v;} public String getAllowedHosts(){return allowedHosts;} public void setAllowedHosts(String v){allowedHosts=v;} public String getAuthMode(){return authMode;} public void setAuthMode(String v){authMode=v;} public String getUsername(){return username;} public void setUsername(String v){username=v;} public String getToken(){return token;} public void setToken(String v){token=v;} public Duration getConnectTimeout(){return connectTimeout;} public void setConnectTimeout(Duration v){connectTimeout=v;} public Duration getReadTimeout(){return readTimeout;} public void setReadTimeout(Duration v){readTimeout=v;} public Duration getPollInterval(){return pollInterval;} public void setPollInterval(Duration v){pollInterval=v;} public Duration getPollMaxBackoff(){return pollMaxBackoff;} public void setPollMaxBackoff(Duration v){pollMaxBackoff=v;} public int getPollBatchSize(){return pollBatchSize;} public void setPollBatchSize(int v){pollBatchSize=v;} public int getMaxRepositoriesPerProject(){return maxRepositoriesPerProject;} public void setMaxRepositoriesPerProject(int v){maxRepositoriesPerProject=v;} public int getMaxBranchesPerRepository(){return maxBranchesPerRepository;} public void setMaxBranchesPerRepository(int v){maxBranchesPerRepository=v;} public boolean isCacheEnabled(){return cacheEnabled;} public void setCacheEnabled(boolean v){cacheEnabled=v;} public Path getCacheRoot(){return cacheRoot;} public void setCacheRoot(Path v){cacheRoot=v;}
}
