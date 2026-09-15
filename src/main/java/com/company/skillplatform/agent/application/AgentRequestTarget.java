package com.company.skillplatform.agent.application;

import com.company.skillplatform.common.application.BusinessException;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;

public record AgentRequestTarget(String platform,String osType) {
    private static final Set<String> PLATFORMS=Set.of("CODEBUDDY","OPENCODE");
    private static final Set<String> OPERATING_SYSTEMS=Set.of("ANY","WINDOWS","MACOS","LINUX");
    public static AgentRequestTarget fromRun(String platform,String osType){return new AgentRequestTarget(required(platform,PLATFORMS),required(osType,OPERATING_SYSTEMS));}
    public static String optionalPlatform(String value){return optional(value,PLATFORMS);}
    public static String optionalOsType(String value){return optional(value,OPERATING_SYSTEMS);}
    private static String required(String value,Set<String> allowed){String normalized=optional(value,allowed);if(normalized==null)throw invalid();return normalized;}
    private static String optional(String value,Set<String> allowed){if(value==null||value.isBlank())return null;String normalized=value.trim().toUpperCase(Locale.ROOT);if(!allowed.contains(normalized))throw invalid();return normalized;}
    private static BusinessException invalid(){return new BusinessException("AGENT_TARGET_INVALID","Agent platform or operating system is invalid",HttpStatus.BAD_REQUEST);}
}
