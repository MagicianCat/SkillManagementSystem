package com.company.skillplatform.telemetry.domain;

/** Token 数据来源。 */
public enum TokenSource {
    CODEBUDDY_UPSTREAM_USAGE,
    STRUCTURED_REQUEST_USAGE,
    LOG_FINAL_USAGE,
    LOG_STEP_PARTIAL,
    MODEL_GATEWAY,
    OTEL,
    ESTIMATED,
    UNKNOWN
}
