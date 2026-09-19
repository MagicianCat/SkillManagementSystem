package com.company.skillplatform.telemetry.domain;

/** Token 数据来源。 */
public enum TokenSource {
    CODEBUDDY_UPSTREAM_USAGE,
    MODEL_GATEWAY,
    OTEL,
    ESTIMATED,
    UNKNOWN
}
