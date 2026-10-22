package com.company.skillplatform.auth.infrastructure;

import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class FeishuCardEnvironmentResolver {
    private static final Set<String> ENVIRONMENTS = Set.of("local", "testing", "prod");
    private static final Pattern SUFFIX = Pattern.compile("^(.*)\\|env=([^|]*)$");
    private final FeishuCardProperties properties;

    public FeishuCardEnvironmentResolver(FeishuCardProperties properties) { this.properties = properties; }

    public Resolution resolve(P2CardActionTrigger trigger) {
        var action = trigger == null || trigger.getEvent() == null ? null : trigger.getEvent().getAction();
        if (action == null) return Resolution.invalid("missing_action");
        Map<String, Object> value = action.getValue();
        boolean valuePresent = value != null && value.containsKey("env");
        String fromValue = valuePresent && value.get("env") instanceof String text ? text : null;
        boolean nameMarkerPresent = action.getName() != null && action.getName().contains("|env=");
        String fromName = null;
        if (nameMarkerPresent) {
            Matcher matcher = SUFFIX.matcher(action.getName());
            if (!matcher.matches()) return Resolution.invalid("malformed_name_environment");
            fromName = matcher.group(2);
        }
        if (valuePresent && !isKnown(fromValue)) return Resolution.invalid("invalid_value_environment");
        if (nameMarkerPresent && !isKnown(fromName)) return Resolution.invalid("invalid_name_environment");
        if (valuePresent && nameMarkerPresent && !fromValue.equals(fromName)) return Resolution.invalid("environment_mismatch");
        if (valuePresent || nameMarkerPresent) return Resolution.valid(valuePresent ? fromValue : fromName);
        if ("testing".equals(properties.getLegacyEnv())) return Resolution.valid("testing");
        return Resolution.invalid("legacy_environment_missing");
    }

    public static boolean isKnown(String environment) { return environment != null && ENVIRONMENTS.contains(environment); }
    public record Resolution(boolean valid, String environment, String reason) {
        static Resolution valid(String environment) { return new Resolution(true, environment, ""); }
        static Resolution invalid(String reason) { return new Resolution(false, null, reason); }
    }
}
