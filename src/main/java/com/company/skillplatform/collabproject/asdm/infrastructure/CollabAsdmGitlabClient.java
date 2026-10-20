package com.company.skillplatform.collabproject.asdm.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Read-only GitLab API access constrained to the environment's exact host allowlist. */
@Component
public class CollabAsdmGitlabClient {
    private final CollabAsdmProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public CollabAsdmGitlabClient(CollabAsdmProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public String normalizeRepositoryUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI uri = URI.create(raw.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !properties.allowedHosts().contains(host)
                    || host.equals("localhost") || host.contains(":") || host.matches("[0-9.]+")
                    || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || path.isBlank() || path.contains("..") || path.contains("\\") || path.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException();
            }
            path = path.replaceAll("/+$/", "");
            if (path.toLowerCase(java.util.Locale.ROOT).endsWith(".git")) path = path.substring(0, path.length() - 4);
            String[] segments = path.substring(1).split("/");
            if (segments.length < 2) throw new IllegalArgumentException();
            for (String segment : segments) if (segment.isBlank() || segment.equals(".") || segment.equals("..") || !segment.matches("[A-Za-z0-9._~-]+")) throw new IllegalArgumentException();
            return "https://" + host + "/" + String.join("/", segments);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("ASDM repository must be an HTTPS project URL on an allowed GitLab host", ex);
        }
    }

    public ProjectInfo validateRepository(String repositoryUrl, String token) {
        String normalized = normalizeRepositoryUrl(repositoryUrl);
        URI base = URI.create(normalized);
        String namespace = normalized.substring(("https://" + base.getHost() + "/").length());
        JsonNode node = get(base.resolve("/api/v4/projects/" + encodePath(namespace)), token);
        long id = node.path("id").asLong(0);
        if (id <= 0) throw new CollabAsdmGitlabException("GITLAB_PROJECT_INVALID_RESPONSE", 502);
        return new ProjectInfo(id, node.path("path_with_namespace").asText(namespace), node.path("web_url").asText(normalized), base);
    }

    public URI repositoryBase(String repositoryUrl) {
        return URI.create(normalizeRepositoryUrl(repositoryUrl));
    }

    public BranchInfo getBranch(long projectId, String branch, String token, URI repositoryBase) {
        JsonNode node = get(repositoryBase.resolve("/api/v4/projects/" + projectId + "/repository/branches/" + encodePath(branch)), token);
        String sha = node.path("commit").path("id").asText("");
        if (sha.isBlank()) throw new CollabAsdmGitlabException("GITLAB_BRANCH_INVALID_RESPONSE", 502);
        return new BranchInfo(node.path("name").asText(branch), sha);
    }

    public List<TreeEntry> listTree(long projectId, String ref, String path, String token, int maxEntries, URI repositoryBase) {
        List<TreeEntry> entries = new ArrayList<>();
        int page = 1;
        while (true) {
            String uri = "/api/v4/projects/" + projectId + "/repository/tree?path=" + encodeQuery(path)
                    + "&ref=" + encodeQuery(ref) + "&recursive=false&per_page=100&page=" + page;
            JsonNode result = get(repositoryBase.resolve(uri), token);
            if (!result.isArray()) throw new CollabAsdmGitlabException("GITLAB_TREE_INVALID_RESPONSE", 502);
            for (JsonNode item : result) {
                if (entries.size() >= maxEntries) throw new CollabAsdmGitlabException("GITLAB_TREE_LIMIT_EXCEEDED", 413);
                entries.add(new TreeEntry(item.path("path").asText(), item.path("type").asText(), item.path("id").asText()));
            }
            if (result.size() < 100) break;
            page++;
        }
        return List.copyOf(entries);
    }

    public FileContent getFile(long projectId, String filePath, String ref, String token, URI repositoryBase) {
        String path = "/api/v4/projects/" + projectId + "/repository/files/" + encodePath(filePath);
        HttpRequest head = HttpRequest.newBuilder(repositoryBase.resolve(path + "?ref=" + encodeQuery(ref))).timeout(properties.readTimeout())
                .header("PRIVATE-TOKEN", token).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
        try {
            HttpResponse<InputStream> metadata = http.send(head, HttpResponse.BodyHandlers.ofInputStream());
            int status = metadata.statusCode();
            if (status < 200 || status >= 300) {
                try (InputStream ignored = metadata.body()) { }
                throw statusError(status);
            }
            long declaredSize = metadata.headers().firstValueAsLong("X-Gitlab-Size").orElse(-1L);
            String blobSha = metadata.headers().firstValue("X-Gitlab-Blob-Id").orElse("");
            String commitSha = metadata.headers().firstValue("X-Gitlab-Commit-Id").orElse(ref);
            String canonicalPath = metadata.headers().firstValue("X-Gitlab-File-Path").orElse(filePath);
            try (InputStream ignored = metadata.body()) { }
            if (declaredSize > properties.maxMarkdownBytes()) throw new CollabAsdmGitlabException("GITLAB_FILE_TOO_LARGE", 413);

            URI rawUri = repositoryBase.resolve(path + "/raw?ref=" + encodeQuery(ref));
            HttpRequest raw = HttpRequest.newBuilder(rawUri).timeout(properties.readTimeout()).header("PRIVATE-TOKEN", token)
                    .header("Accept", "application/octet-stream").GET().build();
            HttpResponse<InputStream> response = http.send(raw, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream ignored = response.body()) { }
                throw statusError(response.statusCode());
            }
            try (InputStream body = response.body()) {
                byte[] content = readLimited(body, properties.maxMarkdownBytes());
                if (declaredSize >= 0 && content.length != declaredSize) throw new CollabAsdmGitlabException("GITLAB_FILE_SIZE_MISMATCH", 502);
                return new FileContent(canonicalPath, commitSha, blobSha, content, declaredSize < 0 ? content.length : declaredSize);
            }
        } catch (CollabAsdmGitlabException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CollabAsdmGitlabException("GITLAB_REQUEST_INTERRUPTED", 503);
        } catch (IOException ex) {
            throw new CollabAsdmGitlabException("GITLAB_TEMPORARY_ERROR", 503);
        }
    }

    private JsonNode get(URI uri, String token) {
        return get(uri, token, Math.max(8 * 1024 * 1024, properties.maxMarkdownBytes() * 2 + 65_536));
    }

    private JsonNode get(URI uri, String token, int maxResponseBytes) {
        if (token == null || token.isBlank()) throw new CollabAsdmGitlabException("GITLAB_TOKEN_REQUIRED", 401);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(properties.readTimeout()).header("PRIVATE-TOKEN", token)
                .header("Accept", "application/json").GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                try (InputStream ignored = response.body()) { }
                throw statusError(status);
            }
            try (InputStream body = response.body()) {
                byte[] bytes = readLimited(body, maxResponseBytes);
                return mapper.readTree(bytes);
            }
        } catch (CollabAsdmGitlabException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CollabAsdmGitlabException("GITLAB_REQUEST_INTERRUPTED", 503);
        } catch (IOException | IllegalArgumentException ex) {
            throw new CollabAsdmGitlabException("GITLAB_TEMPORARY_ERROR", 503);
        }
    }

    private CollabAsdmGitlabException statusError(int status) {
        if (status >= 300 && status < 400) return new CollabAsdmGitlabException("GITLAB_REDIRECT_REJECTED", status);
        if (status == 401) return new CollabAsdmGitlabException("GITLAB_INVALID_TOKEN", status);
        if (status == 403) return new CollabAsdmGitlabException("GITLAB_PERMISSION_DENIED", status);
        if (status == 404) return new CollabAsdmGitlabException("GITLAB_RESOURCE_NOT_FOUND", status);
        if (status == 429) return new CollabAsdmGitlabException("GITLAB_RATE_LIMITED", status);
        if (status >= 500) return new CollabAsdmGitlabException("GITLAB_TEMPORARY_ERROR", status);
        return new CollabAsdmGitlabException("GITLAB_REQUEST_FAILED", status);
    }

    private byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int read; (read = input.read(buffer)) >= 0;) {
            total += read;
            if (total > maxBytes) throw new CollabAsdmGitlabException("GITLAB_RESPONSE_TOO_LARGE", 413);
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String encodePath(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String encodeQuery(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }

    public record ProjectInfo(long id, String pathWithNamespace, String webUrl, URI repositoryBase) {}
    public record BranchInfo(String name, String headSha) {}
    public record TreeEntry(String path, String type, String id) {}
    public record FileContent(String path, String commitSha, String blobSha, byte[] content, long size) {}
}
