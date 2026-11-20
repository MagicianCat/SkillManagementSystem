package com.company.skillplatform.wiki.feishu;

import com.company.skillplatform.auth.application.FeishuUserTokenService;
import com.company.skillplatform.auth.infrastructure.repository.FeishuUserDocumentCredentialRepository;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class FeishuDriveGateway {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{1,512}");
    private static final int MAX_FILE_BYTES = 1_500_000;
    private final FeishuUserTokenService tokens;
    private final FeishuUserDocumentCredentialRepository credentials;
    private final ObjectMapper json;
    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public FeishuDriveGateway(FeishuUserTokenService tokens, FeishuUserDocumentCredentialRepository credentials,
            ObjectMapper json, @Value("${skill-platform.feishu.api-base-url:https://open.feishu.cn/open-apis}") String base) {
        this.tokens = tokens; this.credentials = credentials; this.json = json; this.base = base.replaceAll("/$", "");
    }

    public Access access(long actor) {
        var stored = credentials.findByUserId(actor).orElse(null);
        Set<String> scopes = stored == null || stored.getGrantedScopes() == null ? Set.of()
                : new HashSet<>(Arrays.asList(stored.getGrantedScopes().split("\\s+")));
        boolean authorized = stored != null && stored.isAuthorized();
        return new Access(authorized,
                hasAny(scopes, "drive:drive", "drive:drive:readonly", "space:document:retrieve"),
                hasAny(scopes, "docx:document:readonly", "docs:document.content:read", "drive:drive:readonly"),
                hasAny(scopes, "docx:document:create", "drive:drive"),
                hasAny(scopes, "drive:file:upload", "drive:drive"),
                hasAny(scopes, "drive:file:download", "drive:file:readonly", "drive:drive:readonly", "drive:drive"));
    }

    public record Access(boolean authorized, boolean canBrowse, boolean canReadDocx, boolean canCreateDocx,
                         boolean canUploadFile, boolean canDownloadFile) {}
    public record DriveFile(String token, String type, String name, String modifiedAt) {}
    public record DrivePage(List<DriveFile> items, String pageToken, boolean hasMore) {}
    public record Resolved(String token, String type, String title) {}
    public record Uploaded(String token, String url) {}

    public String root(long actor) {
        JsonNode data = request(actor, "GET", "/drive/explorer/v2/root_folder/meta", null).path("data");
        String token = data.path("token").asText("");
        if (token.isBlank()) throw error("FEISHU_DRIVE_RESPONSE_INVALID", "飞书未返回云盘根目录", HttpStatus.BAD_GATEWAY);
        return token;
    }

    public DrivePage list(long actor, String folder, String pageToken) {
        require(access(actor).canBrowse(), "FEISHU_DRIVE_SCOPE_REQUIRED", "缺少云盘目录浏览权限，请重新授权");
        String parent = folder == null || folder.isBlank() ? root(actor) : validToken(folder);
        String path = "/drive/v1/files?folder_token=" + enc(parent) + "&page_size=50"
                + (pageToken == null || pageToken.isBlank() ? "" : "&page_token=" + enc(pageToken));
        JsonNode data = request(actor, "GET", path, null).path("data");
        List<DriveFile> items = new ArrayList<>();
        for (JsonNode item : data.path("files")) {
            String type = item.path("type").asText("");
            if (Set.of("folder", "docx", "doc", "file").contains(type))
                items.add(new DriveFile(item.path("token").asText(""), type, item.path("name").asText(""),
                        item.path("modified_time").asText("")));
        }
        return new DrivePage(items, data.path("next_page_token").asText(""), data.path("has_more").asBoolean(false));
    }

    public Resolved resolve(long actor, String type, String token) {
        token = validToken(token);
        if ("WIKI".equals(type)) {
            JsonNode node = request(actor, "GET", "/wiki/v2/spaces/get_node?token=" + enc(token), null).path("data").path("node");
            String targetType = node.path("obj_type").asText("").toUpperCase();
            if (!Set.of("DOCX", "DOC").contains(targetType)) throw error("FEISHU_DOCUMENT_TYPE_UNSUPPORTED", "仅支持飞书文档", HttpStatus.BAD_REQUEST);
            return resolve(actor, targetType, node.path("obj_token").asText(""));
        }
        if ("DOCX".equals(type)) {
            JsonNode doc = request(actor, "GET", "/docx/v1/documents/" + enc(token), null).path("data").path("document");
            return new Resolved(token, type, doc.path("title").asText("飞书文档"));
        }
        if ("DOC".equals(type)) {
            JsonNode doc = request(actor, "GET", "/doc/v2/meta/" + enc(token), null).path("data");
            return new Resolved(token, type, doc.path("title").asText("飞书文档"));
        }
        if ("FILE".equals(type)) {
            ObjectNode body = json.createObjectNode();
            body.putArray("request_docs").addObject().put("doc_token", token).put("doc_type", "file");
            body.put("with_url", false);
            JsonNode metas = request(actor, "POST", "/drive/v1/metas/batch_query", body).path("data").path("metas");
            if (!metas.isArray() || metas.isEmpty()) throw error("FEISHU_DOCUMENT_READ_FORBIDDEN", "无法读取飞书文件元数据", HttpStatus.FORBIDDEN);
            String title = metas.get(0).path("title").asText("");
            if (!title.toLowerCase(java.util.Locale.ROOT).endsWith(".md"))
                throw error("FEISHU_DOCUMENT_TYPE_UNSUPPORTED", "仅支持飞书云盘中的 .md 文件", HttpStatus.BAD_REQUEST);
            return new Resolved(token, type, title.substring(0, title.length() - 3));
        }
        throw error("FEISHU_DOCUMENT_TYPE_UNSUPPORTED", "仅支持 DOCX、旧版 DOC 和 Markdown 文件", HttpStatus.BAD_REQUEST);
    }

    public String read(long actor, String type, String token, FeishuDocxMarkdownConverter converter) {
        Resolved source = resolve(actor, type, token);
        if ("DOCX".equals(source.type())) return converter.convert(blocks(actor, source.token()));
        if ("DOC".equals(source.type())) return request(actor, "GET", "/doc/v2/" + enc(source.token()) + "/raw_content", null)
                .path("data").path("content").asText("");
        require(access(actor).canDownloadFile(), "FEISHU_DRIVE_SCOPE_REQUIRED", "缺少云盘原始文件下载权限，请重新授权");
        byte[] bytes = raw(actor, "/drive/v1/files/" + enc(source.token()) + "/download");
        if (bytes.length > MAX_FILE_BYTES) throw error("FEISHU_DOCUMENT_CONVERT_FAILED", "飞书文件超过 Wiki 正文限制", HttpStatus.BAD_REQUEST);
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (Exception ex) { throw error("FEISHU_DOCUMENT_CONVERT_FAILED", "飞书文件不是有效 UTF-8 Markdown", HttpStatus.BAD_REQUEST); }
    }

    public List<JsonNode> blocks(long actor, String token) {
        List<JsonNode> result = new ArrayList<>();
        String page = "";
        do {
            String path = "/docx/v1/documents/" + enc(validToken(token)) + "/blocks?page_size=500&document_revision_id=-1"
                    + (page.isBlank() ? "" : "&page_token=" + enc(page));
            JsonNode data = request(actor, "GET", path, null).path("data");
            data.path("items").forEach(result::add);
            if (result.size() > 5000) throw error("FEISHU_DOCUMENT_CONVERT_FAILED", "飞书文档过长", HttpStatus.BAD_REQUEST);
            if (!data.path("has_more").asBoolean(false)) break;
            page = data.path("page_token").asText("");
            if (page.isBlank()) throw error("FEISHU_DOCUMENT_CONVERT_FAILED", "飞书分页结果不完整", HttpStatus.BAD_GATEWAY);
        } while (true);
        return result;
    }

    public JsonNode createChildren(long actor, String documentToken, String parentBlockId, List<? extends JsonNode> children) {
        if (children.isEmpty() || children.size() > 50) throw error("FEISHU_DOCUMENT_CONVERT_FAILED", "飞书单批块数量无效", HttpStatus.BAD_REQUEST);
        ObjectNode body = json.createObjectNode();
        var array = body.putArray("children");
        children.forEach(array::add);
        String path = "/docx/v1/documents/" + enc(validToken(documentToken)) + "/blocks/"
                + enc(validToken(parentBlockId)) + "/children?document_revision_id=-1";
        for (int attempt = 0; attempt < 4; attempt++) {
            try { return request(actor, "POST", path, body).path("data"); }
            catch (BusinessException ex) {
                if (!"FEISHU_RATE_LIMITED".equals(ex.getCode()) || attempt == 3) throw ex;
                try { Thread.sleep(400L << attempt); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw error("FEISHU_UPSTREAM_ERROR", "飞书写入已中断", HttpStatus.BAD_GATEWAY); }
            }
        }
        throw error("FEISHU_RATE_LIMITED", "飞书写入限流", HttpStatus.TOO_MANY_REQUESTS);
    }

    public String createDocx(long actor, String folder, String title) {
        require(access(actor).canCreateDocx(), "FEISHU_AUTH_REQUIRED", "缺少飞书文档创建权限");
        ObjectNode body = json.createObjectNode().put("title", title).put("folder_token", folder == null ? "" : validToken(folder));
        String id;
        try { id = request(actor, "POST", "/docx/v1/documents", body).path("data").path("document").path("document_id").asText(""); }
        catch (BusinessException ex) {
            if ("FEISHU_UPSTREAM_ERROR".equals(ex.getCode())) throw error("FEISHU_REMOTE_RESULT_UNCERTAIN", "飞书创建结果不明确，请人工核对", HttpStatus.CONFLICT);
            throw ex;
        }
        if (id.isBlank()) throw error("FEISHU_REMOTE_RESULT_UNCERTAIN", "飞书创建成功状态不明确，请人工核对", HttpStatus.BAD_GATEWAY);
        return id;
    }

    public Uploaded uploadMarkdown(long actor, String folder, String name, byte[] bytes) {
        require(access(actor).canUploadFile(), "FEISHU_AUTH_REQUIRED", "缺少飞书文件上传权限");
        if (bytes.length > MAX_FILE_BYTES) throw error("FEISHU_DOCUMENT_CONVERT_FAILED", "Markdown 文件过大", HttpStatus.BAD_REQUEST);
        String parent = folder == null || folder.isBlank() ? root(actor) : validToken(folder);
        String boundary = "----wiki-feishu-" + java.util.UUID.randomUUID();
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (String[] field : new String[][]{{"file_name", name}, {"parent_type", "explorer"}, {"parent_node", parent}, {"size", String.valueOf(bytes.length)}})
                body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field[0] + "\"\r\n\r\n" + field[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"wiki.md\"\r\nContent-Type: text/markdown\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(bytes); body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/drive/v1/files/upload_all"))
                    .timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + userToken(actor))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
            JsonNode result = response(http.send(request, HttpResponse.BodyHandlers.ofString()));
            String id = result.path("data").path("file_token").asText("");
            if (id.isBlank()) throw error("FEISHU_REMOTE_RESULT_UNCERTAIN", "飞书上传结果不明确，请人工核对", HttpStatus.BAD_GATEWAY);
            return new Uploaded(id, "https://feishu.cn/file/" + id);
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw error("FEISHU_REMOTE_RESULT_UNCERTAIN", "飞书上传结果不明确，请人工核对", HttpStatus.CONFLICT); }
    }

    private JsonNode request(long actor, String method, String path, JsonNode body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + userToken(actor));
            HttpRequest request = "GET".equals(method) ? builder.GET().build() : builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            return response(http.send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (BusinessException ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw error("FEISHU_UPSTREAM_ERROR", "飞书请求中断", HttpStatus.BAD_GATEWAY); }
        catch (Exception ex) { throw error("FEISHU_UPSTREAM_ERROR", "飞书请求失败", HttpStatus.BAD_GATEWAY); }
    }

    private byte[] raw(long actor, String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + userToken(actor)).GET().build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) throw error("FEISHU_DOCUMENT_READ_FORBIDDEN", "飞书文件不可下载", HttpStatus.FORBIDDEN);
            return response.body();
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw error("FEISHU_UPSTREAM_ERROR", "飞书文件下载失败", HttpStatus.BAD_GATEWAY); }
    }

    private JsonNode response(HttpResponse<String> response) throws Exception {
        JsonNode result = json.readTree(response.body());
        int code = result.path("code").asInt(0);
        if (code == 99991679) throw error("FEISHU_DRIVE_SCOPE_REQUIRED", "缺少飞书云盘用户授权，请重新授权", HttpStatus.FORBIDDEN);
        if (response.statusCode() == 401 || response.statusCode() == 403) throw error("FEISHU_AUTH_REQUIRED", "飞书用户授权已失效", HttpStatus.FORBIDDEN);
        if (response.statusCode() == 429 || code == 99991400) throw error("FEISHU_RATE_LIMITED", "飞书调用频率过高", HttpStatus.TOO_MANY_REQUESTS);
        if (response.statusCode() / 100 != 2 || code != 0) throw error("FEISHU_UPSTREAM_ERROR", "飞书请求失败：" + code, HttpStatus.BAD_GATEWAY);
        return result;
    }

    private String userToken(long actor) {
        String token = tokens.accessTokenFor(actor);
        if (token == null || token.isBlank()) throw error("FEISHU_AUTH_REQUIRED", "请重新授权飞书文档访问", HttpStatus.FORBIDDEN);
        return token;
    }
    private static boolean hasAny(Set<String> scopes, String... choices) { return Arrays.stream(choices).anyMatch(scopes::contains); }
    private static void require(boolean okay, String code, String message) { if (!okay) throw error(code, message, HttpStatus.FORBIDDEN); }
    public static String validToken(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) throw error("FEISHU_DOCUMENT_TOKEN_INVALID", "飞书文档或文件夹标识无效", HttpStatus.BAD_REQUEST);
        return token;
    }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }
}
