package com.company.skillplatform.common.interfaces;

import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(String code, String message, String requestId,
                               Map<String, Object> details, Instant timestamp) {
    public static ApiErrorResponse of(String code, String message, String requestId, Map<String, Object> details) {
        return new ApiErrorResponse(code, message, requestId, details == null ? Map.of() : details, Instant.now());
    }
}
