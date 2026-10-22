package com.company.skillplatform.auth.infrastructure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Adds and removes the environment marker carried by interactive Feishu card actions. */
@Component
public class FeishuCardActionTagger {
    private static final String ENV_MARKER = "|env=";
    private final FeishuCardProperties properties;

    public FeishuCardActionTagger(FeishuCardProperties properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    public Map<String, Object> attachEnvironmentToCallbackValue(Map<String, ?> value) {
        Map<String, Object> tagged = new LinkedHashMap<>();
        if (value != null) value.forEach(tagged::put);
        tagged.put("env", properties.getEnvironment());
        return tagged;
    }

    public String appendEnvironmentToActionName(String name) {
        if (name == null || name.isBlank()) return name;
        String clean = stripEnvSuffix(name);
        return clean + ENV_MARKER + properties.getEnvironment();
    }

    /** Removes only a final environment suffix, leaving legacy callback names untouched. */
    public static String stripEnvSuffix(String name) {
        if (name == null) return null;
        int marker = name.lastIndexOf(ENV_MARKER);
        if (marker < 0) return name;
        String environment = name.substring(marker + ENV_MARKER.length());
        if (!environment.equals("local") && !environment.equals("testing") && !environment.equals("prod")) return name;
        return name.substring(0, marker);
    }
}
