package com.company.skillplatform.devpipeline.interfaces;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.storage.domain.ObjectStoragePort;
import java.util.Set;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Streams the recorded SDLC skill demonstrations on demand. */
@RestController
@RequestMapping("/api/v1/dev-pipeline/videos")
public class DevPipelineVideoController {
    private static final String OBJECT_PREFIX = "dev-pipeline/videos/";
    private static final String VIDEO_SUFFIX = ".mp4";
    private static final Set<String> ALLOWED_SKILLS = Set.of(
            "brainstorming", "intent-driven-development", "requirements-specification", "product-lens",
            "prd-authoring", "product-capability", "high-level-design", "architecture-blueprint-generator",
            "architecture-decision-records", "api-design", "frontend-design", "stitch-manage-design-system",
            "stitch-generate-design", "accessibility", "backend-detailed-design", "springboot-patterns",
            "frontend-detailed-design", "frontend-patterns", "implementation-planning", "tdd-workflow",
            "springboot-tdd", "stitch-react-components", "react-testing", "security-review",
            "springboot-security", "security-scan", "e2e-testing", "webapp-testing", "browser-qa",
            "web-design-guidelines", "test-report-consolidator", "verification-loop", "deployment-patterns",
            "github-actions-hardening", "github-ops", "oo-component-documentation");

    private final ObjectStoragePort storage;

    public DevPipelineVideoController(ObjectStoragePort storage) {
        this.storage = storage;
    }

    @GetMapping("/{skillKey}")
    public ResponseEntity<?> video(
            @PathVariable String skillKey,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        if (!ALLOWED_SKILLS.contains(skillKey)) {
            throw notFound();
        }

        String objectKey = OBJECT_PREFIX + skillKey + VIDEO_SUFFIX;
        if (!storage.exists(objectKey)) {
            throw notFound();
        }

        ObjectStoragePort.StorageObjectMetadata metadata = storage.stat(objectKey);
        String etag = quoteEtag(metadata.etag());
        HttpHeaders commonHeaders = commonHeaders(metadata, etag);
        if (matchesEtag(ifNoneMatch, etag)) {
            return new ResponseEntity<>(null, commonHeaders, HttpStatus.NOT_MODIFIED);
        }

        ByteRange requested = parseRange(range, metadata.size());
        if (range != null && !range.isBlank() && requested == null) {
            HttpHeaders headers = new HttpHeaders();
            headers.putAll(commonHeaders);
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + metadata.size());
            return new ResponseEntity<>(null, headers, HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        }

        if (requested == null) {
            commonHeaders.setContentLength(metadata.size());
            return ResponseEntity.ok().headers(commonHeaders).body(new InputStreamResource(storage.get(objectKey)));
        }

        long length = requested.end() - requested.start() + 1;
        commonHeaders.setContentLength(length);
        commonHeaders.set(HttpHeaders.CONTENT_RANGE,
                "bytes " + requested.start() + "-" + requested.end() + "/" + metadata.size());
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .headers(commonHeaders)
                .body(new InputStreamResource(storage.get(objectKey, requested.start(), length)));
    }

    private static HttpHeaders commonHeaders(ObjectStoragePort.StorageObjectMetadata metadata, String etag) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf(
                metadata.contentType() == null || metadata.contentType().isBlank()
                        ? MediaType.APPLICATION_OCTET_STREAM_VALUE : metadata.contentType()));
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.CACHE_CONTROL, "private, max-age=86400");
        headers.setETag(etag);
        return headers;
    }

    private static ByteRange parseRange(String header, long size) {
        if (header == null || header.isBlank()) {
            return null;
        }
        if (!header.startsWith("bytes=") || header.indexOf(',') >= 0 || size <= 0) {
            return null;
        }
        String value = header.substring("bytes=".length()).trim();
        int dash = value.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            String startText = value.substring(0, dash).trim();
            String endText = value.substring(dash + 1).trim();
            if (startText.isEmpty()) {
                long suffixLength = Long.parseLong(endText);
                if (suffixLength <= 0) return null;
                long length = Math.min(suffixLength, size);
                return new ByteRange(size - length, size - 1);
            }
            long start = Long.parseLong(startText);
            if (start < 0 || start >= size) return null;
            long end = endText.isEmpty() ? size - 1 : Long.parseLong(endText);
            if (end < start) return null;
            return new ByteRange(start, Math.min(end, size - 1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean matchesEtag(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) return false;
        for (String candidate : ifNoneMatch.split(",")) {
            if ("*".equals(candidate.trim()) || etag.equals(candidate.trim())) return true;
        }
        return false;
    }

    private static String quoteEtag(String etag) {
        if (etag == null || etag.isBlank()) return "\"dev-pipeline-video\"";
        String normalized = etag.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"")) return normalized;
        return "\"" + normalized + "\"";
    }

    private static BusinessException notFound() {
        return new BusinessException("DEV_PIPELINE_VIDEO_NOT_FOUND", "Video not found", HttpStatus.NOT_FOUND);
    }

    private record ByteRange(long start, long end) {}
}
