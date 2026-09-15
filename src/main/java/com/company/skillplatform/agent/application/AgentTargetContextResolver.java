package com.company.skillplatform.agent.application;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Resolves only explicit target choices from the current user turn. */
@Component
public class AgentTargetContextResolver {
    private static final List<Choice> PLATFORMS = List.of(
            new Choice("CODEBUDDY", Pattern.compile("(?i)code[\\s_-]*buddy|腾讯云代码助手|代码助手")),
            new Choice("OPENCODE", Pattern.compile("(?i)open[\\s_-]*code")),
            new Choice(null, Pattern.compile("(?i)不限平台|任意平台|全平台|platform[\\s:=：]*(?:any|all)")));
    private static final List<Choice> SYSTEMS = List.of(
            new Choice("WINDOWS", Pattern.compile("(?i)windows|win(?:10|11)?\\b|微软系统")),
            new Choice("MACOS", Pattern.compile("(?i)mac[\\s_-]*os|macos|os[\\s_-]*x|苹果系统")),
            new Choice("LINUX", Pattern.compile("(?i)linux|ubuntu|centos|debian|红帽|麒麟系统")),
            new Choice("ANY", Pattern.compile("(?i)不限系统|任意系统|全系统|跨平台|os[\\s:=：]*(?:any|all)")));

    public Resolution resolve(String content) {
        String value = content == null ? "" : content.trim();
        Match platform = last(value, PLATFORMS);
        Match osType = last(value, SYSTEMS);
        return new Resolution(platform.found(), platform.value(), osType.found(), osType.value());
    }

    private Match last(String content, List<Choice> choices) {
        int lastIndex = -1;
        String selected = null;
        boolean found = false;
        for (Choice choice : choices) {
            Matcher matcher = choice.pattern().matcher(content);
            while (matcher.find()) {
                if (matcher.start() >= lastIndex) {
                    lastIndex = matcher.start();
                    selected = choice.value();
                    found = true;
                }
            }
        }
        return new Match(found, selected == null ? null : selected.toUpperCase(Locale.ROOT));
    }

    private record Choice(String value, Pattern pattern) {}
    private record Match(boolean found, String value) {}
    public record Resolution(boolean platformSpecified, String platform, boolean osTypeSpecified, String osType) {}
}
