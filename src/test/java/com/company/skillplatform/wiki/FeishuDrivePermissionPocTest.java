package com.company.skillplatform.wiki;

import com.company.skillplatform.auth.application.FeishuUserTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/** Opt-in probe; write calls require wiki.feishu.poc.write=true. Never prints user tokens. */
@SpringBootTest
@EnabledIfSystemProperty(named = "wiki.feishu.poc.user-id", matches = "[0-9]+")
class FeishuDrivePermissionPocTest {
    @Autowired FeishuUserTokenService tokens;
    @Autowired ObjectMapper json;
    @Value("${skill-platform.feishu.api-base-url:https://open.feishu.cn/open-apis}") String base;

    @Test void probeCurrentUsersDriveScopes() throws Exception {
        long userId = Long.parseLong(System.getProperty("wiki.feishu.poc.user-id"));
        String token = tokens.accessTokenFor(userId);
        if (token == null || token.isBlank()) {
            System.out.println("FEISHU_POC credential=AUTH_REQUIRED");
            return;
        }
        probe("root", "/drive/explorer/v2/root_folder/meta", token);
        probe("list", "/drive/v1/files?page_size=10", token);
        HttpClient client = HttpClient.newHttpClient();
        String api = base.replaceAll("/$", "");
        JsonNode root = json.readTree(client.send(HttpRequest.newBuilder(URI.create(api + "/drive/explorer/v2/root_folder/meta"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        String rootToken = root.path("data").path("token").asText("");
        if (!rootToken.isBlank()) probe("legacy_folder_children", "/drive/explorer/v2/folder/" + rootToken + "/children", token);
    }

    @Test void probeExistingDocxBlocks() throws Exception {
        String documentId = System.getProperty("wiki.feishu.poc.docx-token");
        Assumptions.assumeTrue(documentId != null && !documentId.isBlank());
        long userId = Long.parseLong(System.getProperty("wiki.feishu.poc.user-id"));
        String token = tokens.accessTokenFor(userId);
        probe("blocks_revision_minus_one", "/docx/v1/documents/" + documentId + "/blocks?page_size=500&document_revision_id=-1", token);
        probe("blocks_default", "/docx/v1/documents/" + documentId + "/blocks?page_size=500", token);
        probe("docx_meta", "/docx/v1/documents/" + documentId, token);
    }

    @Test void probeMarkdownConvertScope() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("wiki.feishu.poc.convert"));
        long userId = Long.parseLong(System.getProperty("wiki.feishu.poc.user-id"));
        String token = tokens.accessTokenFor(userId);
        String body = "{\"content_type\":\"markdown\",\"content\":\"# 标题\\n\\n| A | B |\\n|---|---|\\n| x | y |\"}";
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base.replaceAll("/$", "") + "/docx/v1/documents/blocks/convert"))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode payload = json.readTree(response.body());
        System.out.println("FEISHU_POC operation=convert_markdown http=" + response.statusCode() + " code=" + payload.path("code").asInt(-1)
                + " message=" + payload.path("msg").asText("") + " blockCount=" + payload.path("data").path("blocks").size());
    }

    @Test void probeDocxAndRawFileWrite() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("wiki.feishu.poc.write"));
        long userId = Long.parseLong(System.getProperty("wiki.feishu.poc.user-id"));
        String token = tokens.accessTokenFor(userId);
        Assumptions.assumeTrue(token != null && !token.isBlank());
        HttpClient client = HttpClient.newHttpClient();
        String api = base.replaceAll("/$", "");
        JsonNode root = json.readTree(client.send(HttpRequest.newBuilder(URI.create(api + "/drive/explorer/v2/root_folder/meta"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        String rootToken = root.path("data").path("token").asText("");
        System.out.println("FEISHU_POC rootTokenPresent=" + !rootToken.isBlank());

        String title = "研枢团队Wiki云盘POC-" + System.currentTimeMillis();
        String createBody = json.createObjectNode().put("title", title).put("folder_token", "").toString();
        HttpResponse<String> created = client.send(HttpRequest.newBuilder(URI.create(api + "/docx/v1/documents"))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(createBody)).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode creation = json.readTree(created.body());
        String documentId = creation.path("data").path("document").path("document_id").asText("");
        System.out.println("FEISHU_POC operation=create_docx http=" + created.statusCode() + " code=" + creation.path("code").asText("missing")
                + " documentUrl=" + (documentId.isBlank() ? "none" : "https://feishu.cn/docx/" + documentId));
        if (!documentId.isBlank()) probe("read_docx_blocks", "/docx/v1/documents/" + documentId + "/blocks?page_size=10", token);

        if (rootToken.isBlank()) return;
        byte[] file = "# 研枢团队 Wiki 飞书云盘 POC\n\n验证 Markdown 上传与下载。\n".getBytes(StandardCharsets.UTF_8);
        String boundary = "----wiki-feishu-poc-" + System.currentTimeMillis();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        for (String[] field : new String[][]{{"file_name", title + ".md"}, {"parent_type", "explorer"},
                {"parent_node", rootToken}, {"size", String.valueOf(file.length)}}) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field[0] + "\"\r\n\r\n" + field[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"poc.md\"\r\nContent-Type: text/markdown\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(file);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> uploaded = client.send(HttpRequest.newBuilder(URI.create(api + "/drive/v1/files/upload_all"))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode upload = json.readTree(uploaded.body());
        String fileToken = upload.path("data").path("file_token").asText("");
        System.out.println("FEISHU_POC operation=upload_markdown http=" + uploaded.statusCode() + " code=" + upload.path("code").asText("missing")
                + " fileTokenPresent=" + !fileToken.isBlank() + " message=" + upload.path("msg").asText(""));
        if (!fileToken.isBlank()) {
            HttpResponse<byte[]> downloaded = client.send(HttpRequest.newBuilder(URI.create(api + "/drive/v1/files/" + fileToken + "/download"))
                    .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            boolean same = java.util.Arrays.equals(file, downloaded.body());
            System.out.println("FEISHU_POC operation=download_markdown http=" + downloaded.statusCode() + " byteExact=" + same);
        }
    }

    private void probe(String operation, String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base.replaceAll("/$", "") + path))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode result = json.readTree(response.body());
        System.out.println("FEISHU_POC operation=" + operation + " http=" + response.statusCode()
                + " code=" + result.path("code").asText("missing") + " message=" + result.path("msg").asText(""));
    }
}
