package com.company.skillplatform.collabproject.progress.infrastructure;

import com.company.skillplatform.auth.application.FeishuUserTokenService;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Narrow DOCX block API for native progress tables. Never exposes user access tokens. */
@Component
public class FeishuProgressDocGateway {
    private final FeishuUserTokenService tokens;
    private final ObjectMapper json;
    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public FeishuProgressDocGateway(FeishuUserTokenService tokens, ObjectMapper json,
            @Value("${skill-platform.feishu.api-base-url:https://open.feishu.cn/open-apis}") String base) {
        this.tokens = tokens; this.json = json; this.base = base.replaceAll("/$", "");
    }

    public JsonNode createTable(long actor, String document, int rows, int columns) {
        ObjectNode table = json.createObjectNode().put("block_type", 31);
        table.putObject("table").putObject("property").put("row_size", rows).put("column_size", columns);
        return createChildren(actor, document, document, List.of(table)).path("children").path(0);
    }

    public JsonNode createChildren(long actor, String document, String parent, List<ObjectNode> children) {
        ArrayNode array = json.createArrayNode(); children.forEach(array::add);
        return request(actor, "POST", blocks(document) + "/" + enc(parent) + "/children",
                json.createObjectNode().set("children", array)).path("data");
    }

    public JsonNode children(long actor, String document, String parent) {
        return request(actor, "GET", blocks(document) + "/" + enc(parent) + "/children?page_size=500&document_revision_id=-1", null).path("data");
    }

    public JsonNode block(long actor, String document, String block) {
        return request(actor, "GET", blocks(document) + "/" + enc(block) + "?document_revision_id=-1", null).path("data").path("block");
    }

    /** Deletes one direct child after its block ID has been checked by the caller. */
    public void deleteChild(long actor, String document, String parent, int index) {
        ObjectNode body = json.createObjectNode().put("start_index", index).put("end_index", index + 1);
        request(actor, "DELETE", blocks(document) + "/" + enc(parent)
                + "/children/batch_delete?document_revision_id=-1", body);
    }

    public String addText(long actor, String document, String cell, String content) {
        ObjectNode text = json.createObjectNode().put("block_type", 2);
        text.putObject("text").putArray("elements").addObject().putObject("text_run").put("content", content);
        String id = createChildren(actor, document, cell, List.of(text)).path("children").path(0).path("block_id").asText("");
        if (id.isBlank()) throw new BusinessException("FEISHU_PROGRESS_BLOCK_MISSING", "Text block was not returned", HttpStatus.BAD_GATEWAY);
        return id;
    }

    public void updateText(long actor, String document, String block, String content) {
        ObjectNode body = json.createObjectNode();
        body.putObject("update_text_elements").putArray("elements").addObject().putObject("text_run").put("content", content);
        request(actor, "PATCH", blocks(document) + "/" + enc(block), body);
    }

    public JsonNode insertRow(long actor, String document, String table, int rowIndex) {
        ObjectNode body = json.createObjectNode(); body.putObject("insert_table_row").put("row_index", rowIndex);
        return request(actor, "PATCH", blocks(document) + "/" + enc(table), body).path("data").path("block");
    }

    public void batchUpdateText(long actor, String document, Map<String, String> updates) {
        var entries = List.copyOf(updates.entrySet());
        for (int start = 0; start < entries.size(); start += 100) {
            ArrayNode requests = json.createArrayNode();
            for (int i = start; i < Math.min(entries.size(), start + 100); i++) {
                var entry = entries.get(i);
                ObjectNode item = requests.addObject().put("block_id", entry.getKey());
                item.putObject("update_text_elements").putArray("elements").addObject()
                        .putObject("text_run").put("content", entry.getValue());
            }
            request(actor, "PATCH", blocks(document) + "/batch_update", json.createObjectNode().set("requests", requests));
        }
    }

    public JsonNode wikiChildren(long actor, String space, String parent, String pageToken) {
        String path = "/wiki/v2/spaces/" + enc(space) + "/nodes?parent_node_token=" + enc(parent) + "&page_size=50"
                + (pageToken == null || pageToken.isBlank() ? "" : "&page_token=" + enc(pageToken));
        return request(actor, "GET", path, null).path("data");
    }

    public List<JsonNode> allBlocks(long actor, String document) {
        List<JsonNode> result = new ArrayList<>();
        String page = "";
        do {
            String path = blocks(document) + "?page_size=500&document_revision_id=-1"
                    + (page.isBlank() ? "" : "&page_token=" + enc(page));
            JsonNode data = request(actor, "GET", path, null).path("data");
            data.path("items").forEach(result::add);
            if (!data.path("has_more").asBoolean(false)) break;
            page = data.path("page_token").asText("");
            if (page.isBlank()) throw new BusinessException("FEISHU_PROGRESS_PAGE_INVALID", "Feishu blocks page token missing", HttpStatus.BAD_GATEWAY);
        } while (true);
        return result;
    }

    private JsonNode request(long actor, String method, String path, JsonNode body) {
        String token = tokens.accessTokenFor(actor);
        if (token == null || token.isBlank()) throw new BusinessException("FEISHU_AUTH_REQUIRED", "Feishu document authorization is required", HttpStatus.FORBIDDEN);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token);
            HttpRequest request = "GET".equals(method) ? builder.GET().build() : builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode payload = json.readTree(response.body());
            if (response.statusCode() == 401 || response.statusCode() == 403) throw new BusinessException("FEISHU_AUTH_REQUIRED", "Feishu authorization is invalid", HttpStatus.FORBIDDEN);
            if (response.statusCode() == 429) throw new BusinessException("FEISHU_RATE_LIMITED", "Feishu rate limit", HttpStatus.TOO_MANY_REQUESTS);
            if (response.statusCode() / 100 != 2 || payload.path("code").asInt(0) != 0)
                throw new BusinessException("FEISHU_PROGRESS_WRITE_FAILED", "Feishu DOCX block operation failed: " + payload.path("msg").asText(""), HttpStatus.BAD_GATEWAY);
            return payload;
        } catch (BusinessException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new BusinessException("FEISHU_UPSTREAM_ERROR", "Feishu operation interrupted", HttpStatus.BAD_GATEWAY); }
        catch (Exception e) { throw new BusinessException("FEISHU_UPSTREAM_ERROR", "Feishu operation failed", HttpStatus.BAD_GATEWAY); }
    }

    private String blocks(String document) { return "/docx/v1/documents/" + enc(document) + "/blocks"; }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
