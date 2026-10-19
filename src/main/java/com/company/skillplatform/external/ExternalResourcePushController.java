package com.company.skillplatform.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/integration/v1/resources")
public class ExternalResourcePushController {
    private final ExternalResourceService service;
    private final ObjectMapper mapper;
    private final Map<String, String> keys;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int requestsPerMinute;

    public ExternalResourcePushController(ExternalResourceService service, ObjectMapper mapper,
            @Value("${integration.external.api-keys-json:{}}") String keysJson,
            @Value("${integration.external.requests-per-minute:60}") int requestsPerMinute) throws Exception {
        this.service = service;
        this.mapper = mapper;
        this.keys = mapper.readValue(keysJson, mapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
        this.requestsPerMinute = requestsPerMinute;
    }

    @PostMapping(value = "/push", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse> push(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestParam(value = "metadata", required = false) String metadata,
            @RequestParam MultiValueMap<String, MultipartFile> files, HttpServletRequest request) {
        ExternalResourceService.PushRequest body;
        try {
            body = mapper.readValue(metadata, ExternalResourceService.PushRequest.class);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(new ApiResponse(4001, "invalid_metadata", null));
        }
        if (body == null || body.sourceSystem() == null || body.sourceSystem().isBlank()
                || body.sourceSystem().length() > 128
                || body.role() == null || body.role().roleId() == null || body.role().roleId().isBlank()
                || body.role().roleId().length() > 255
                || body.role().roleName() == null || body.role().roleName().isBlank()
                || body.role().roleName().length() > 512
                || !validRoleStatus(body.role().status())
                || body.resources() == null || body.resources().size() > 20) {
            return ResponseEntity.badRequest().body(new ApiResponse(4001, "invalid_metadata", null));
        }
        String expected = keys.get(body.sourceSystem());
        if (expected == null || expected.isBlank() || apiKey == null ||
                !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), apiKey.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiResponse(4002, "unauthorized", null));
        }
        if (!windows.computeIfAbsent(body.sourceSystem(), ignored -> new Window()).allow(requestsPerMinute))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ApiResponse(4290,"rate_limited",null));
        ExternalResourceService.PushResult result = service.push(body, files, request.getRequestId());
        int code = result.failed() == 0 ? 0 : result.failed() == result.total() ? 1002 : 1001;
        HttpStatus status = code == 1002 ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.OK;
        return ResponseEntity.status(status).body(new ApiResponse(code,
                code == 0 ? "success" : code == 1001 ? "partial_failure" : "all_failed", result));
    }

    public record ApiResponse(int code, String message, ExternalResourceService.PushResult data) {}
    private static boolean validRoleStatus(String status) {
        return status == null || "ACTIVE".equals(status) || "INACTIVE".equals(status);
    }
    private static final class Window {
        private long minute = -1;
        private int count;
        synchronized boolean allow(int limit) {
            long now = System.currentTimeMillis() / 60000;
            if (minute != now) { minute = now; count = 0; }
            return count++ < Math.max(1,limit);
        }
    }
}
