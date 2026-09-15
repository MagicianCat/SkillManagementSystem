package com.company.skillplatform.agent.infrastructure;

import com.company.skillplatform.auth.application.FeishuUserTokenService;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Backend-owned, read-only facade for Feishu documents. Raw UAT never leaves this process. */
@Component
public class FeishuDocumentMcpProxy {
    private static final Logger log = LoggerFactory.getLogger(FeishuDocumentMcpProxy.class);
    private static final Set<String> ALLOWED = Set.of("search-doc", "fetch-doc");
    private static final int MAX_BYTES = 64 * 1024;
    private final FeishuUserTokenService tokens;
    private final ObjectMapper json;
    private final String endpoint;
    private final String apiBaseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public FeishuDocumentMcpProxy(FeishuUserTokenService tokens, ObjectMapper json,
                                  @Value("${skill-platform.feishu.mcp-url:https://mcp.feishu.cn/mcp}") String endpoint,
                                  @Value("${skill-platform.feishu.api-base-url:https://open.feishu.cn/open-apis}") String apiBaseUrl) {
        this.tokens = tokens; this.json = json; this.endpoint = endpoint; this.apiBaseUrl = apiBaseUrl;
    }

    /** Search through the user-scoped Drive API. This avoids passing the UAT to a remote MCP server. */
    public JsonNode search(Long userId, String query, int limit, int offset) {
        String uat = userToken(userId);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("search_key", query);
            body.put("count", Math.min(Math.max(limit, 1), 50));
            int boundedOffset = Math.max(offset, 0);
            body.put("offset", boundedOffset);
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl.replaceAll("/$", "") + "/suite/docs-api/search/object"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + uat)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403)
                throw new BusinessException("FEISHU_AUTH_REQUIRED", "Feishu authorization is invalid", HttpStatus.FORBIDDEN);
            JsonNode result = json.readTree(response.body());
            if (response.statusCode() / 100 != 2) {
                log.warn("event=feishu.document.search.upstream_failed status={} code={} message={}", response.statusCode(),
                        result.path("code").asInt(-1), result.path("msg").asText(""));
                throw upstreamFailure("Feishu document search is unavailable", response.statusCode());
            }
            if (result.path("code").asInt(0) != 0) {
                log.warn("event=feishu.document.search.rejected code={} message={}", result.path("code").asInt(), result.path("msg").asText(""));
                throw new BusinessException("FEISHU_TOOL_FAILED", "Feishu document search failed", HttpStatus.BAD_GATEWAY);
            }
            return normalizeSearchResult(result.path("data"), boundedOffset, Math.min(Math.max(limit, 1), 50));
        } catch (BusinessException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new BusinessException("FEISHU_UPSTREAM_ERROR", "Feishu document search was interrupted", HttpStatus.BAD_GATEWAY); }
        catch (Exception e) { throw new BusinessException("FEISHU_UPSTREAM_ERROR", "Feishu document search is unavailable", HttpStatus.BAD_GATEWAY); }
    }
    public JsonNode search(Long userId, String query, int limit) { return search(userId, query, limit, 0); }
    public JsonNode fetch(Long userId, String docId, String docType, int offset, int maxBytes) {
        String normalizedType = normalizeType(docType);
        int safeOffset = Math.max(offset, 0), safeMax = Math.min(Math.max(maxBytes, 1), MAX_BYTES);
        if ("DOC".equals(normalizedType)) return fetchLegacy(userId, docId, safeOffset, safeMax);
        if (!"DOCX".equals(normalizedType)) return unsupported(docId, normalizedType);
        return call(userId, "fetch-doc", Map.of("doc_id", docId, "offset", safeOffset, "limit", safeMax));
    }

    public JsonNode fetch(Long userId, String docId) { return fetch(userId, docId, "DOCX", 0, 32 * 1024); }

    private JsonNode normalizeSearchResult(JsonNode data, int offset, int limit) {
        if (!data.isObject()) return data;
        ObjectNode normalized = data.deepCopy();
        JsonNode files = normalized.path("files");
        if (files.isArray()) {
            ArrayNode items = (ArrayNode) files;
            for (JsonNode item : items) {
                if (!(item instanceof ObjectNode object)) continue;
                String token = firstText(object, "docs_token", "doc_token", "token");
                String type = firstText(object, "docs_type", "doc_type", "type");
                String upstreamUrl = firstText(object, "url", "open_url");
                String normalizedType = normalizeType(type);
                if (!token.isBlank()) object.put("docId", token);
                object.put("docType", normalizedType);
                boolean readable = Set.of("DOCX", "DOC").contains(normalizedType);
                object.put("readable", readable);
                object.put("readMethod", readable ? ("DOC".equals(normalizedType) ? "LEGACY_DOC_API" : "FEISHU_MCP") : "UNSUPPORTED");
                if (!readable) object.put("unreadableReason", "当前版本暂不支持读取该类型的飞书文档");
                if (!upstreamUrl.isBlank()) {
                    object.put("open_url", upstreamUrl);
                    object.put("open_url_source", "UPSTREAM");
                } else {
                    String derivedUrl = derivedDocumentUrl(type, token);
                    if (derivedUrl != null) {
                        object.put("open_url", derivedUrl);
                        object.put("open_url_source", "DERIVED_FROM_TOKEN");
                    }
                }
            }
            normalized.put("offset", offset);
            normalized.put("limit", limit);
            normalized.put("next_offset", offset + items.size());
        }
        return normalized;
    }

    private String normalizeType(String value) {
        String type = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "docx", "document", "new_document" -> "DOCX";
            case "doc", "docs", "legacy", "legacy_document" -> "DOC";
            case "sheet", "sheets", "spreadsheet", "sht" -> "SHEET";
            case "bitable", "base" -> "BITABLE";
            case "slide", "slides" -> "SLIDES";
            case "mindnote", "mindnotes" -> "MINDNOTE";
            case "wiki" -> "WIKI";
            default -> "UNKNOWN";
        };
    }

    private JsonNode fetchLegacy(Long userId, String docId, int offset, int maxBytes) {
        String uat = userToken(userId);
        if (docId == null || !docId.matches("[A-Za-z0-9_-]{1,512}"))
            throw new BusinessException("FEISHU_LEGACY_DOC_READ_FAILED", "Legacy Feishu document id is invalid", HttpStatus.BAD_REQUEST);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl.replaceAll("/$", "") + "/doc/v2/" + docId + "/raw_content"))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + uat).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = json.readTree(response.body());
            if (response.statusCode() == 401 || response.statusCode() == 403) throw new BusinessException("FEISHU_AUTH_REQUIRED", "Feishu authorization is invalid", HttpStatus.FORBIDDEN);
            if (response.statusCode() / 100 != 2 || result.path("code").asInt(0) != 0) {
                log.warn("event=feishu.document.legacy.failed status={} code={} message={}", response.statusCode(), result.path("code").asInt(-1), result.path("msg").asText(""));
                throw response.statusCode() / 100 == 2
                        ? new BusinessException("FEISHU_LEGACY_DOC_READ_FAILED", "Legacy Feishu document read failed", HttpStatus.BAD_GATEWAY)
                        : upstreamFailure("Legacy Feishu document read failed", response.statusCode());
            }
            String full = result.path("data").path("content").asText("");
            String content = offset >= full.length() ? "" : full.substring(offset, Math.min(full.length(), offset + maxBytes));
            return json.createObjectNode().put("status", "OK").put("docId", docId).put("docType", "DOC").put("content", content).put("offset", offset).put("hasMore", offset + content.length() < full.length()).put("sourceUrl", "https://www.feishu.cn/docs/" + docId);
        } catch (BusinessException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new BusinessException("FEISHU_LEGACY_DOC_READ_FAILED", "Legacy Feishu document read was interrupted", HttpStatus.BAD_GATEWAY); }
        catch (Exception e) { throw new BusinessException("FEISHU_LEGACY_DOC_READ_FAILED", "Legacy Feishu document service is unavailable", HttpStatus.BAD_GATEWAY); }
    }

    private JsonNode unsupported(String docId, String docType) {
        ObjectNode result = json.createObjectNode()
                .put("status", "UNSUPPORTED_DOCUMENT_TYPE")
                .put("docId", docId)
                .put("docType", docType)
                .put("readable", false)
                .put("message", "当前版本暂不支持读取该类型的飞书文档");
        String sourceUrl = derivedDocumentUrl(docType, docId);
        if (sourceUrl != null) result.put("sourceUrl", sourceUrl);
        return result;
    }

    private String firstText(ObjectNode node, String... names) {
        for (String name : names) {
            String value = node.path(name).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String derivedDocumentUrl(String type, String token) {
        if (token.isBlank()) return null;
        String route = switch (type.toLowerCase(Locale.ROOT)) {
            case "docx" -> "docx";
            case "doc", "docs" -> "docs";
            case "sheet", "sheets" -> "sheets";
            case "bitable", "base" -> "base";
            case "mindnote", "mindnotes" -> "mindnotes";
            case "slide", "slides" -> "slides";
            case "wiki" -> "wiki";
            default -> null;
        };
        return route == null ? null : "https://www.feishu.cn/" + route + "/" + token;
    }

    private String userToken(Long userId) {
        String uat = tokens.accessTokenFor(userId);
        if (uat == null || uat.isBlank()) throw new BusinessException("FEISHU_AUTH_REQUIRED", "Feishu document authorization is required", HttpStatus.FORBIDDEN);
        return uat;
    }

    private JsonNode call(Long userId, String tool, Map<String,Object> arguments) {
        if (!ALLOWED.contains(tool)) throw new BusinessException("FEISHU_TOOL_DENIED", "Feishu tool is not allowed", HttpStatus.FORBIDDEN);
        String uat = userToken(userId);
        try {
            Map<String,Object> init = new LinkedHashMap<>(); init.put("jsonrpc", "2.0"); init.put("id", 1); init.put("method", "initialize");
            init.put("params", Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of(), "clientInfo", Map.of("name", "skill-platform", "version", "1.0")));
            HttpResponse<String> initialized = send(init, uat, null);
            if (initialized.statusCode() / 100 != 2) throw upstreamFailure("Feishu initialization failed", initialized.statusCode());
            String session = initialized.headers().firstValue("mcp-session-id").orElse(null);
            Map<String,Object> list = new LinkedHashMap<>(); list.put("jsonrpc", "2.0"); list.put("id", 2); list.put("method", "tools/list");
            HttpResponse<String> listed = send(list, uat, session);
            if (listed.statusCode() / 100 != 2) throw upstreamFailure("Feishu tool discovery failed", listed.statusCode());
            JsonNode remoteTools = json.readTree(listed.body()).path("result").path("tools");
            for (String expected : ALLOWED) if (java.util.stream.StreamSupport.stream(remoteTools.spliterator(), false).noneMatch(t -> expected.equals(t.path("name").asText())))
                throw new BusinessException("FEISHU_MCP_CONTRACT_CHANGED", "Feishu read tools are unavailable", HttpStatus.BAD_GATEWAY);
            Map<String,Object> body = new LinkedHashMap<>(); body.put("jsonrpc", "2.0"); body.put("id", 3); body.put("method", "tools/call");
            body.put("params", Map.of("name", tool, "arguments", arguments));
            HttpResponse<String> response = send(body, uat, session);
            if (response.statusCode() == 401 || response.statusCode() == 403) throw new BusinessException("FEISHU_AUTH_REQUIRED", "Feishu authorization is invalid", HttpStatus.FORBIDDEN);
            if (response.statusCode() / 100 != 2) throw upstreamFailure("Feishu document service is unavailable", response.statusCode());
            JsonNode result = json.readTree(response.body());
            if (result.has("error")) {
                log.warn("event=feishu.document.mcp.rejected tool={} code={}", tool, result.path("error").path("code").asInt(-1));
                throw new BusinessException("FEISHU_TOOL_FAILED", "Feishu document lookup failed", HttpStatus.BAD_GATEWAY);
            }
            JsonNode toolResult = result.path("result");
            if (toolResult.path("isError").asBoolean(false)) {
                log.warn("event=feishu.document.mcp.tool_failed tool={} isError=true", tool);
                throw new BusinessException("FEISHU_TOOL_FAILED", "Feishu document lookup failed", HttpStatus.BAD_GATEWAY);
            }
            return toolResult;
        } catch (BusinessException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new BusinessException("FEISHU_UPSTREAM_ERROR", "Feishu document service was interrupted", HttpStatus.BAD_GATEWAY); }
        catch (Exception e) { throw new BusinessException("FEISHU_UPSTREAM_ERROR", "Feishu document service is unavailable", HttpStatus.BAD_GATEWAY); }
    }

    private HttpResponse<String> send(Map<String,Object> body, String uat, String session) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("X-Lark-MCP-UAT", uat)
                .header("X-Lark-MCP-Allowed-Tools", String.join(",", ALLOWED))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (session != null) builder.header("Mcp-Session-Id", session);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private BusinessException upstreamFailure(String message, int statusCode) {
        if (statusCode == 429) return new BusinessException("FEISHU_RATE_LIMITED", message, HttpStatus.TOO_MANY_REQUESTS);
        if (statusCode >= 400 && statusCode < 500) return new BusinessException("FEISHU_REQUEST_REJECTED", message, HttpStatus.BAD_REQUEST);
        return new BusinessException("FEISHU_UPSTREAM_ERROR", message, HttpStatus.BAD_GATEWAY);
    }
}
