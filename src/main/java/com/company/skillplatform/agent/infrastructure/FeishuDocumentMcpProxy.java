package com.company.skillplatform.agent.infrastructure;

import com.company.skillplatform.auth.application.FeishuUserTokenService;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
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
        if ("WIKI".equals(normalizedType)) return fetchWiki(userId, docId, safeOffset, safeMax);
        if (!"DOCX".equals(normalizedType)) return unsupported(docId, normalizedType);
        return call(userId, "fetch-doc", Map.of("doc_id", docId, "offset", safeOffset, "limit", safeMax));
    }

    public JsonNode fetch(Long userId, String docId) { return fetch(userId, docId, "DOCX", 0, 32 * 1024); }

    /** Resolve a user-provided Wiki URL without exposing the user's token. */
    public JsonNode resolveWikiUrl(Long userId, String url) {
        return resolveDocumentUrl(userId, url);
    }

    /** Resolve Wiki and native Docs URLs while keeping the user access token server-side. */
    public JsonNode resolveDocumentUrl(Long userId, String url) {
        if (url == null || url.isBlank()) throw new BusinessException("FEISHU_DOCUMENT_URL_INVALID", "Feishu document URL is required", HttpStatus.BAD_REQUEST);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^https://([A-Za-z0-9.-]+\\.feishu\\.cn)/(wiki|docx|docs)/([A-Za-z0-9_-]+)(?:[?#].*)?$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(url.trim());
        if (!matcher.matches()) throw new BusinessException("FEISHU_DOCUMENT_URL_INVALID", "Only an HTTPS Feishu Wiki or native document URL is supported", HttpStatus.BAD_REQUEST);
        String route = matcher.group(2).toLowerCase(Locale.ROOT);
        String token = matcher.group(3);
        String type = "wiki".equals(route) ? "WIKI" : "docs".equals(route) ? "DOC" : "DOCX";
        JsonNode resolved = fetch(userId, token, type, 0, 1);
        ObjectNode result = resolved.isObject() ? (ObjectNode) resolved.deepCopy() : json.createObjectNode();
        if ("WIKI".equals(type)) result.put("nodeToken", token);
        result.put("docId", token).put("docType", type).put("sourceUrl", url.trim());
        if (!result.has("readable")) result.put("readable", true);
        String title = firstText(result, "title", "name");
        if (title.isBlank() && !"WIKI".equals(type)) title = fetchDocumentTitle(userId, token, type);
        result.put("title", title.isBlank() ? token : title);
        return result;
    }

    /** Validates a Wiki parent node and the caller's ability to create children. */
    public JsonNode validatePublishTarget(Long userId, String url) {
        if (url == null || url.isBlank()) throw new BusinessException("FEISHU_WIKI_URL_INVALID", "Feishu Wiki URL is required", HttpStatus.BAD_REQUEST);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^https://([A-Za-z0-9.-]+\\.feishu\\.cn)/wiki/([A-Za-z0-9_-]+)(?:[?#].*)?$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(url.trim());
        if (!matcher.matches()) throw new BusinessException("FEISHU_WIKI_URL_INVALID", "Only a Feishu Wiki URL can be used as the publication root", HttpStatus.BAD_REQUEST);
        String token = matcher.group(2), base = apiBaseUrl.replaceAll("/$", ""), uat = userToken(userId);
        try {
            JsonNode nodeResponse = request("GET", base + "/wiki/v2/spaces/get_node?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8), null, uat);
            JsonNode node = nodeResponse.path("data").path("node");
            if (!node.isObject()) node = nodeResponse.path("data");
            String title = firstText(node, "title", "node_title", "name");
            String space = firstText(node, "space_id", "spaceId");
            if (space.isBlank()) space = firstText(nodeResponse.path("data"), "space_id", "spaceId");
            JsonNode permission = request("GET", base + "/drive/v1/permissions/" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "/members/auth?type=wiki&action=edit", null, uat);
            boolean editable = permission.path("data").path("auth_result").asBoolean(false)
                    || permission.path("data").path("authResult").asBoolean(false);
            if (!editable) throw new BusinessException("FEISHU_WIKI_EDIT_REQUIRED", "The current Feishu user has no edit permission for this Wiki node", HttpStatus.FORBIDDEN);
            return json.createObjectNode().put("url", url.trim()).put("nodeToken", token).put("spaceId", space).put("title", title.isBlank() ? token : title).put("editable", true);
        } catch (BusinessException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new BusinessException("FEISHU_UPSTREAM_ERROR", "Feishu validation was interrupted", HttpStatus.BAD_GATEWAY); }
        catch (Exception e) { throw new BusinessException("FEISHU_UPSTREAM_ERROR", "Feishu Wiki validation failed", HttpStatus.BAD_GATEWAY); }
    }

    /** Creates a native DOCX, writes editable text blocks, and mounts it below a Wiki node. */
    public JsonNode publishNativeDocx(Long userId, String spaceId, String parentNodeToken, String title, String markdown) {
        String base = apiBaseUrl.replaceAll("/$", ""), uat = userToken(userId);
        try {
            ObjectNode body = json.createObjectNode().put("title", title == null || title.isBlank() ? "项目产物" : title);
            JsonNode created = request("POST", base + "/docx/v1/documents", body, uat);
            JsonNode data = created.path("data"); String docToken = firstText(data, "document_id", "documentId", "token");
            if (docToken.isBlank()) throw new IllegalStateException("Feishu did not return a DOCX token");
            ArrayNode children = json.createArrayNode();
            for (String line : (markdown == null ? "" : markdown).split("\\R", -1)) {
                ObjectNode block = json.createObjectNode().put("block_type", 2);
                ObjectNode text = block.putObject("text"); ArrayNode elements = text.putArray("elements");
                elements.addObject().putObject("text_run").put("content", line);
                children.add(block);
            }
            ObjectNode blocks = json.createObjectNode().set("children", children);
            request("POST", base + "/docx/v1/documents/" + URLEncoder.encode(docToken, StandardCharsets.UTF_8) + "/blocks/" + URLEncoder.encode(docToken, StandardCharsets.UTF_8) + "/children", blocks, uat);
            ObjectNode wikiBody = json.createObjectNode().put("obj_type", "docx").put("obj_token", docToken).put("parent_node_token", parentNodeToken).put("node_type", "origin");
            JsonNode mounted = request("POST", base + "/wiki/v2/spaces/" + URLEncoder.encode(spaceId, StandardCharsets.UTF_8) + "/nodes", wikiBody, uat);
            JsonNode mountedNode = mounted.path("data").path("node");
            String nodeToken = firstText(mountedNode, "node_token", "nodeToken", "token");
            String url = firstText(mountedNode, "url");
            if (url.isBlank() && !nodeToken.isBlank()) url = "https://www.feishu.cn/wiki/" + nodeToken;
            return json.createObjectNode().put("documentToken", docToken).put("nodeToken", nodeToken).put("documentUrl", url);
        } catch (BusinessException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new BusinessException("FEISHU_UPSTREAM_ERROR", "Feishu publication was interrupted", HttpStatus.BAD_GATEWAY); }
        catch (Exception e) { throw new BusinessException("FEISHU_PUBLICATION_FAILED", "Feishu native document publication failed", HttpStatus.BAD_GATEWAY); }
    }

    public String createNativeDocx(Long userId, String title) {
        try { ObjectNode body=json.createObjectNode().put("title", title==null||title.isBlank()?"项目产物":title);
            JsonNode data = request("POST", apiBaseUrl.replaceAll("/$", "")+"/docx/v1/documents", body, userToken(userId)).path("data");
            String token = firstText(data,"document_id","documentId","token");
            if (token.isBlank()) token = firstText(data.path("document"),"document_id","documentId","token");
            return token;
        }
        catch (BusinessException e){throw e;} catch(Exception e){throw new BusinessException("FEISHU_PUBLICATION_FAILED","Feishu DOCX creation failed",HttpStatus.BAD_GATEWAY);}
    }
    /** Creates a DOCX directly under a Wiki parent node (with title), returning both tokens. This is the
     *  correct way to publish: mounting a pre-existing docx via /nodes creates a NEW EMPTY document. */
    public JsonNode createWikiDocx(Long userId, String spaceId, String parentNodeToken, String title) {
        try {
            ObjectNode body = json.createObjectNode()
                    .put("obj_type", "docx")
                    .put("parent_node_token", parentNodeToken)
                    .put("node_type", "origin")
                    .put("title", title == null || title.isBlank() ? "项目产物" : title);
            JsonNode node = request("POST", apiBaseUrl.replaceAll("/$", "") + "/wiki/v2/spaces/" + URLEncoder.encode(spaceId, StandardCharsets.UTF_8) + "/nodes", body, userToken(userId))
                    .path("data").path("node");
            String objToken = firstText(node, "obj_token", "objToken");
            String nodeToken = firstText(node, "node_token", "nodeToken", "token");
            String url = firstText(node, "url");
            if (url.isBlank() && !nodeToken.isBlank()) url = "https://www.feishu.cn/wiki/" + nodeToken;
            if (objToken.isBlank()) throw new IllegalStateException("Feishu did not return a wiki DOCX token");
            return json.createObjectNode().put("documentToken", objToken).put("nodeToken", nodeToken).put("documentUrl", url);
        }
        catch (BusinessException e){throw e;} catch(Exception e){throw new BusinessException("FEISHU_PUBLICATION_FAILED","Feishu wiki document creation failed",HttpStatus.BAD_GATEWAY);}
    }
    public void writeNativeDocx(Long userId,String documentToken,String markdown) {
        try {
            // Feishu rejects more than 50 children per request (code 99992402), so write in batches.
            final int BATCH = 50;
            String base = apiBaseUrl.replaceAll("/$", "");
            String url = base + "/docx/v1/documents/" + URLEncoder.encode(documentToken,StandardCharsets.UTF_8) + "/blocks/" + URLEncoder.encode(documentToken,StandardCharsets.UTF_8) + "/children";
            List<ObjectNode> blocks = markdownToBlocks(markdown);
            for (int start = 0; start < blocks.size(); start += BATCH) {
                ArrayNode children = json.createArrayNode();
                for (int i = start; i < Math.min(blocks.size(), start + BATCH); i++) children.add(blocks.get(i));
                request("POST", url, json.createObjectNode().set("children",children), userToken(userId));
            }
        }
        catch(BusinessException e){throw e;} catch(Exception e){throw new BusinessException("FEISHU_PUBLICATION_FAILED","Feishu DOCX block writing failed",HttpStatus.BAD_GATEWAY);}
    }

    /** Parses a small markdown subset into Feishu DOCX blocks so published documents render as
     *  real headings/lists/quotes/code instead of raw "#"/"-" text. Falls back to a text block. */
    private List<ObjectNode> markdownToBlocks(String markdown) {
        List<ObjectNode> blocks = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) return blocks;
        String[] lines = markdown.split("\\R", -1);
        StringBuilder codeBuf = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                if (codeBuf == null) { codeBuf = new StringBuilder(); }
                else { blocks.add(codeBlock(codeBuf.toString())); codeBuf = null; }
                continue;
            }
            if (codeBuf != null) { if (codeBuf.length() > 0) codeBuf.append('\n'); codeBuf.append(line); continue; }
            blocks.add(lineToBlock(line));
        }
        if (codeBuf != null) blocks.add(codeBlock(codeBuf.toString())); // unclosed fence
        return blocks;
    }

    private ObjectNode lineToBlock(String line) {
        String t = line.trim();
        java.util.function.Function<String, ObjectNode> textBlock = (content) -> block(2, "text", content);
        java.util.regex.Matcher m;
        if (t.isEmpty()) return textBlock.apply("");
        if ((m = java.util.regex.Pattern.compile("^(#{1,9})\\s+(.*)$").matcher(t)).matches()) {
            int level = Math.min(m.group(1).length(), 9);
            return block(2 + level, "heading" + level, m.group(2)); // heading1=3 ... heading9=11
        }
        if ((m = java.util.regex.Pattern.compile("^[-*+]\\s+(.*)$").matcher(t)).matches()) return block(12, "bullet", m.group(1));
        if ((m = java.util.regex.Pattern.compile("^\\d+[.)]\\s+(.*)$").matcher(t)).matches()) return block(13, "ordered", m.group(1));
        if ((m = java.util.regex.Pattern.compile("^>\\s?(.*)$").matcher(t)).matches()) return block(15, "quote", m.group(1));
        return textBlock.apply(line);
    }

    private ObjectNode block(int type, String key, String content) {
        ObjectNode block = json.createObjectNode().put("block_type", type);
        block.putObject(key).putArray("elements").addObject().putObject("text_run").put("content", content);
        return block;
    }

    private ObjectNode codeBlock(String content) {
        ObjectNode block = json.createObjectNode().put("block_type", 14);
        ObjectNode code = block.putObject("code");
        code.putArray("elements").addObject().putObject("text_run").put("content", content);
        code.putObject("style").put("language", 1); // 1 = plaintext
        return block;
    }
    public JsonNode mountNativeDocx(Long userId,String spaceId,String parentNodeToken,String documentToken){
        try {ObjectNode body=json.createObjectNode().put("obj_type","docx").put("obj_token",documentToken).put("parent_node_token",parentNodeToken).put("node_type","origin");JsonNode n=request("POST",apiBaseUrl.replaceAll("/$", "")+"/wiki/v2/spaces/"+URLEncoder.encode(spaceId,StandardCharsets.UTF_8)+"/nodes",body,userToken(userId));JsonNode node=n.path("data").path("node");return json.createObjectNode().put("nodeToken",firstText(node,"node_token","nodeToken","token")).put("documentUrl",firstText(node,"url"));}
        catch(BusinessException e){throw e;} catch(Exception e){throw new BusinessException("FEISHU_PUBLICATION_FAILED","Feishu Wiki mount failed",HttpStatus.BAD_GATEWAY);}
    }

    private JsonNode request(String method, String url, Object body, String uat) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + uat);
        HttpRequest request = "GET".equals(method) ? builder.GET().build() : builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode payload = json.readTree(response.body());
        if (response.statusCode() == 401 || response.statusCode() == 403) throw new BusinessException("FEISHU_AUTH_REQUIRED", "Feishu authorization is invalid", HttpStatus.FORBIDDEN);
        if (response.statusCode() / 100 != 2 || payload.path("code").asInt(0) != 0) {
            log.warn("event=feishu.api.rejected method={} url={} status={} body={}", method, url, response.statusCode(),
                    response.body() == null ? "" : response.body().substring(0, Math.min(2000, response.body().length())));
            String msg = payload.path("msg").asText("");
            StringBuilder detail = new StringBuilder(msg);
            JsonNode violations = payload.path("error").path("field_violations");
            if (violations.isArray() && !violations.isEmpty()) {
                detail.append(" [");
                for (int i = 0; i < violations.size(); i++) {
                    JsonNode v = violations.get(i);
                    detail.append(i == 0 ? "" : ", ").append(v.path("field").asText("")).append(": ").append(v.path("description").asText(""));
                }
                detail.append("]");
            }
            if (detail.length() == 0) detail.append("code=").append(payload.path("code").asInt());
            throw upstreamFailure("Feishu API request failed: " + detail, response.statusCode());
        }
        return payload;
    }

    private String fetchDocumentTitle(Long userId, String docId, String docType) {
        try {
            Map<String, Object> body = Map.of(
                    "request_docs", List.of(Map.of("doc_token", docId, "doc_type", docType.toLowerCase(Locale.ROOT))),
                    "with_url", false);
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl.replaceAll("/$", "") + "/drive/v1/metas/batch_query"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + userToken(userId))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode payload = json.readTree(response.body());
            if (response.statusCode() / 100 != 2 || payload.path("code").asInt(0) != 0) {
                log.warn("event=feishu.document.metadata.failed status={} code={} docType={}", response.statusCode(), payload.path("code").asInt(-1), docType);
                return "";
            }
            JsonNode metas = payload.path("data").path("metas");
            return metas.isArray() && !metas.isEmpty() ? firstText(metas.get(0), "title", "name") : "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            log.warn("event=feishu.document.metadata.unavailable docType={} errorType={}", docType, e.getClass().getSimpleName());
            return "";
        }
    }

    private JsonNode normalizeSearchResult(JsonNode data, int offset, int limit) {
        if (!data.isObject()) return data;
        ObjectNode normalized = data.deepCopy();
        JsonNode files = normalized.path("files");
        if (!files.isArray()) {
            // Feishu has returned both `items` and `docs` for otherwise equivalent
            // search responses. Keep one stable contract for the frontend/MCP.
            for (String alternate : List.of("items", "docs", "documents")) {
                if (normalized.path(alternate).isArray()) {
                    files = normalized.path(alternate);
                    normalized.set("files", files);
                    break;
                }
            }
        }
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
                boolean readable = Set.of("DOCX", "DOC", "WIKI").contains(normalizedType);
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
            case "file" -> "FILE";
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

    /**
     * A Wiki URL contains a Wiki node token, not the underlying document token.
     * Resolve the node first, then use the same read path as a normal docx/doc.
     */
    private JsonNode fetchWiki(Long userId, String wikiToken, int offset, int maxBytes) {
        if (wikiToken == null || !wikiToken.matches("[A-Za-z0-9_-]{1,512}"))
            throw new BusinessException("FEISHU_WIKI_READ_FAILED", "Feishu Wiki token is invalid", HttpStatus.BAD_REQUEST);
        String uat = userToken(userId);
        try {
            String encoded = URLEncoder.encode(wikiToken, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl.replaceAll("/$", "") + "/wiki/v2/spaces/get_node?token=" + encoded))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + uat).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = json.readTree(response.body());
            if (response.statusCode() == 401 || response.statusCode() == 403)
                throw new BusinessException("FEISHU_AUTH_REQUIRED", "Feishu authorization is invalid", HttpStatus.FORBIDDEN);
            if (response.statusCode() / 100 != 2 || result.path("code").asInt(0) != 0) {
                log.warn("event=feishu.wiki.node.failed status={} code={} message={}", response.statusCode(), result.path("code").asInt(-1), result.path("msg").asText(""));
                throw new BusinessException("FEISHU_WIKI_READ_FAILED", "Feishu Wiki node lookup failed", HttpStatus.BAD_GATEWAY);
            }
            JsonNode data = result.path("data");
            JsonNode node = data.path("node").isObject() ? data.path("node") : data;
            String objectToken = firstText(node, "obj_token", "objToken", "object_token", "token");
            String objectType = normalizeType(firstText(node, "obj_type", "objType", "object_type", "type"));
            String title = firstText(node, "title", "node_title", "name");
            log.info("event=feishu.wiki.node.resolved tokenPrefix={} objectType={} objectTokenPresent={} titlePresent={}",
                    wikiToken.substring(0, Math.min(6, wikiToken.length())), objectType, !objectToken.isBlank(), !title.isBlank());
            if (objectToken.isBlank() || objectType.isBlank() || "UNKNOWN".equals(objectType)) {
                log.warn("event=feishu.wiki.node.unreadable tokenPrefix={} objectTokenPresent={} objectType={} nodeFields={}",
                        wikiToken.substring(0, Math.min(6, wikiToken.length())), !objectToken.isBlank(), objectType, node.fieldNames());
                throw new BusinessException("FEISHU_WIKI_READ_FAILED", "Feishu Wiki node has no readable document", HttpStatus.BAD_GATEWAY);
            }
            JsonNode resolved = "DOC".equals(objectType)
                    ? fetchLegacy(userId, objectToken, offset, maxBytes)
                    : "DOCX".equals(objectType)
                        ? call(userId, "fetch-doc", Map.of("doc_id", objectToken, "offset", offset, "limit", maxBytes))
                        : unsupported(objectToken, objectType);
            if (resolved.isObject()) {
                ObjectNode resultNode = (ObjectNode) resolved.deepCopy();
                resultNode.put("docId", wikiToken).put("docType", "WIKI").put("resolvedDocId", objectToken).put("resolvedDocType", objectType);
                resultNode.put("readable", Set.of("DOC", "DOCX").contains(objectType));
                if (!title.isBlank()) resultNode.put("title", title);
                resultNode.put("sourceUrl", "https://www.feishu.cn/wiki/" + wikiToken);
                return resultNode;
            }
            return resolved;
        } catch (BusinessException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new BusinessException("FEISHU_WIKI_READ_FAILED", "Feishu Wiki lookup was interrupted", HttpStatus.BAD_GATEWAY); }
        catch (Exception e) { throw new BusinessException("FEISHU_WIKI_READ_FAILED", "Feishu Wiki service is unavailable", HttpStatus.BAD_GATEWAY); }
    }

    private JsonNode unsupported(String docId, String docType) {
        ObjectNode result = json.createObjectNode()
                .put("status", "UNSUPPORTED_DOCUMENT_TYPE")
                .put("docId", docId)
                .put("docType", docType)
                .put("readable", false)
                .put("message", "暂不支持该类型文件");
        String sourceUrl = derivedDocumentUrl(docType, docId);
        if (sourceUrl != null) result.put("sourceUrl", sourceUrl);
        return result;
    }

    private String firstText(JsonNode node, String... names) {
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
