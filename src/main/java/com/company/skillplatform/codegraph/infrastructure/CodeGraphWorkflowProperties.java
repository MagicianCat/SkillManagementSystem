package com.company.skillplatform.codegraph.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("skill-platform.code-graph.workflow")
public record CodeGraphWorkflowProperties(String engineType, String engineVersion, String adapterVersion,
                                          String engineConfigHash, String groupConfigHash) {
    public CodeGraphWorkflowProperties {
        engineType = value(engineType, "GITNEXUS");
        engineVersion = value(engineVersion, "1.6.12");
        adapterVersion = value(adapterVersion, "0.1.0");
        engineConfigHash = value(engineConfigHash, "37a8eec1ce19687d132fe29051dca629d164e2c4958ba141d5f4133a33f0688f");
        groupConfigHash = value(groupConfigHash, "ff7d8346945c150664ef3bfd7576e192b99e2129f6375cc6e2e6fda948397f26");
    }
    private static String value(String actual, String fallback) { return actual == null || actual.isBlank() ? fallback : actual.trim(); }
}
