package com.company.skillplatform.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integration/v1/development-stages")
public class ExternalDevelopmentStageController {
    private final DevelopmentStageCatalogService catalog;
    private final Map<String, String> keys;

    public ExternalDevelopmentStageController(DevelopmentStageCatalogService catalog, ObjectMapper mapper,
            @Value("${integration.external.api-keys-json:{}}") String keysJson) throws Exception {
        this.catalog = catalog;
        this.keys = mapper.readValue(keysJson, mapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
    }

    @GetMapping
    public ResponseEntity<ApiResponse> stages(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        if (!valid(apiKey)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiResponse(4002, "unauthorized", null));
        try {
            return ResponseEntity.ok(new ApiResponse(0, "success", catalog.list()));
        } catch (RuntimeException failure) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiResponse(5001, "internal_error", null));
        }
    }

    private boolean valid(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return false;
        byte[] supplied = apiKey.getBytes(StandardCharsets.UTF_8);
        boolean accepted = false;
        for (String expected : keys.values()) {
            if (expected != null && !expected.isBlank()) accepted |= MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied);
        }
        return accepted;
    }

    public record ApiResponse(int code, String message, DevelopmentStageCatalogService.Dictionary data) {}
}
