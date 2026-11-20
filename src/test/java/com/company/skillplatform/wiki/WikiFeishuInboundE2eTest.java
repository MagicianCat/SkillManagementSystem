package com.company.skillplatform.wiki;

import com.company.skillplatform.auth.application.AuthService;
import com.company.skillplatform.agent.infrastructure.FeishuDocumentMcpProxy;
import com.company.skillplatform.wiki.application.WikiDocumentService;
import com.company.skillplatform.wiki.feishu.WikiFeishuTransferService;
import com.company.skillplatform.wiki.feishu.FeishuDriveGateway;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real import. Creates exactly one team Wiki from the actor's readable Feishu DOCX. */
@SpringBootTest
@EnabledIfSystemProperty(named = "wiki.feishu.import.e2e.user-id", matches = "[0-9]+")
class WikiFeishuInboundE2eTest {
    @Autowired AuthService auth;
    @Autowired WikiFeishuTransferService transfers;
    @Autowired WikiDocumentService wiki;
    @Autowired JdbcTemplate db;
    @Autowired FeishuDriveGateway drive;
    @Autowired FeishuDocumentMcpProxy docx;

    @Test void importDocxToTeamWiki() throws Exception {
        long actor = Long.parseLong(System.getProperty("wiki.feishu.import.e2e.user-id"));
        String sourceToken = System.getProperty("wiki.feishu.import.e2e.docx-token");
        var user = auth.currentUser(actor);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null,
                user.permissions().stream().map(SimpleGrantedAuthority::new).toList()));
        try {
            List<Long> teams = db.query("SELECT DISTINCT team_id FROM wiki_document WHERE created_by=? AND team_id IS NOT NULL AND status='ACTIVE' ORDER BY team_id LIMIT 10",
                    (rs, row) -> rs.getLong(1), actor);
            assertFalse(teams.isEmpty(), "Actor needs an existing team Wiki to select a safe target team");
            long teamId = teams.get(0);
            WikiFeishuTransferService.Source source = null;
            WikiFeishuTransferService.Preview preview = null;
            if (sourceToken != null && !sourceToken.isBlank()) {
                source = new WikiFeishuTransferService.Source("DOCX", sourceToken);
                preview = transfers.preview(actor, source);
            } else {
                var root = drive.root(actor);
                var page = drive.list(actor, root, null);
                System.out.println("FEISHU_IMPORT_E2E rootItems=" + page.items().size());
                for (var item : page.items()) {
                    if (!"docx".equals(item.type())) continue;
                    try {
                        var candidate = new WikiFeishuTransferService.Source("DOCX", item.token());
                        var candidatePreview = transfers.preview(actor, candidate);
                        source = candidate; preview = candidatePreview; break;
                    } catch (Exception ignored) { }
                }
                if (source == null) {
                    String created = drive.createDocx(actor, null, "研枢飞书导入验证-" + System.currentTimeMillis());
                    docx.writeNativeDocx(actor, created, "# 飞书云盘导入验证\n\n这是一篇用于验证飞书文档导入团队 Wiki 的文档。\n");
                    source = new WikiFeishuTransferService.Source("DOCX", created);
                    preview = transfers.preview(actor, source);
                    System.out.println("FEISHU_IMPORT_E2E createdSourceUrl=https://feishu.cn/docx/" + created);
                }
            }
            assertFalse(preview.excerpt().isBlank());
            var accepted = transfers.startImport(actor, new WikiFeishuTransferService.ImportRequest(source,
                    new WikiFeishuTransferService.Target("CREATE", teamId, null, "INTERNAL_KNOWLEDGE", List.of(), null, null, null),
                    UUID.randomUUID().toString()));
            for (int i = 0; i < 30; i++) {
                transfers.poll();
                var job = transfers.job(actor, accepted.jobId());
                if ("SUCCEEDED".equals(job.get("status"))) {
                    long wikiId = ((Number) job.get("wikiDocumentId")).longValue();
                    var imported = wiki.get(wikiId, actor);
                    assertEquals(teamId, imported.teamId());
                    assertEquals("INTERNAL_KNOWLEDGE", imported.knowledgeCategory());
                    assertTrue(imported.markdownContent().contains(preview.excerpt().substring(0, Math.min(30, preview.excerpt().length()))));
                    System.out.println("FEISHU_IMPORT_E2E jobId=" + accepted.jobId() + " wikiId=" + wikiId + " teamId=" + teamId + " title=" + imported.title());
                    return;
                }
                if (!List.of("PENDING", "RUNNING").contains(job.get("status"))) fail("Import failed: " + job);
                Thread.sleep(1000);
            }
            fail("Import job timed out");
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void stageFullDocxContentWithoutCreatingWiki() {
        long actor = Long.parseLong(System.getProperty("wiki.feishu.import.e2e.user-id"));
        var page = drive.list(actor, drive.root(actor), null);
        for (var item : page.items()) {
            if (!"docx".equals(item.type())) continue;
            try {
                var source = new WikiFeishuTransferService.Source("DOCX", item.token());
                var preview = transfers.preview(actor, source);
                var editable = transfers.editableContent(actor, source);
                assertFalse(editable.markdownContent().isBlank());
                assertTrue(editable.markdownContent().startsWith(preview.excerpt()));
                System.out.println("FEISHU_STAGE_E2E title=" + editable.title() + " fullLength=" + editable.markdownContent().length());
                return;
            } catch (com.company.skillplatform.common.application.BusinessException ignored) { }
        }
        fail("No readable DOCX in the user's root folder");
    }

    @Test void importMarkdownFileToTeamWiki() throws Exception {
        long actor = Long.parseLong(System.getProperty("wiki.feishu.import.e2e.user-id"));
        var user = auth.currentUser(actor);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null,
                user.permissions().stream().map(SimpleGrantedAuthority::new).toList()));
        try {
            Long teamId = db.queryForObject("SELECT team_id FROM wiki_document WHERE created_by=? AND team_id IS NOT NULL AND status='ACTIVE' ORDER BY id DESC LIMIT 1", Long.class, actor);
            assertNotNull(teamId);
            String markdown = "# 飞书 Markdown 导入验证\n\n验证 UTF-8 原文从云盘导入团队 Wiki。\n";
            var uploaded = drive.uploadMarkdown(actor, null, "研枢飞书Markdown导入验证-" + System.currentTimeMillis() + ".md", markdown.getBytes(StandardCharsets.UTF_8));
            var source = new WikiFeishuTransferService.Source("FILE", uploaded.token());
            assertEquals(markdown, drive.read(actor, "FILE", uploaded.token(), new com.company.skillplatform.wiki.feishu.FeishuDocxMarkdownConverter()));
            var accepted = transfers.startImport(actor, new WikiFeishuTransferService.ImportRequest(source,
                    new WikiFeishuTransferService.Target("CREATE", teamId, null, "INTERNAL_KNOWLEDGE", List.of(), null, null, null),
                    UUID.randomUUID().toString()));
            for (int i = 0; i < 30; i++) {
                transfers.poll();
                var job = transfers.job(actor, accepted.jobId());
                if ("SUCCEEDED".equals(job.get("status"))) {
                    long wikiId = ((Number) job.get("wikiDocumentId")).longValue();
                    var imported = wiki.get(wikiId, actor);
                    assertEquals(teamId, imported.teamId());
                    assertEquals(markdown, imported.markdownContent());
                    assertTrue(imported.title().startsWith("研枢飞书Markdown导入验证-"));
                    System.out.println("FEISHU_MARKDOWN_IMPORT_E2E jobId=" + accepted.jobId() + " wikiId=" + wikiId + " teamId=" + teamId + " sourceUrl=" + uploaded.url());
                    return;
                }
                if (!List.of("PENDING", "RUNNING").contains(job.get("status"))) fail("Markdown import failed: " + job);
                Thread.sleep(1000);
            }
            fail("Markdown import job timed out");
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void appendMarkdownFileToExistingTeamWiki() throws Exception {
        long actor = Long.parseLong(System.getProperty("wiki.feishu.import.e2e.user-id"));
        long wikiId = Long.parseLong(System.getProperty("wiki.feishu.import.e2e.target-wiki-id"));
        var user = auth.currentUser(actor);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null,
                user.permissions().stream().map(SimpleGrantedAuthority::new).toList()));
        try {
            var before = wiki.get(wikiId, actor);
            String addition = "## 飞书追加导入验证\n\n追加内容已从飞书云盘读取。\n";
            var uploaded = drive.uploadMarkdown(actor, null, "研枢飞书追加导入验证-" + System.currentTimeMillis() + ".md", addition.getBytes(StandardCharsets.UTF_8));
            var source = new WikiFeishuTransferService.Source("FILE", uploaded.token());
            var accepted = transfers.startImport(actor, new WikiFeishuTransferService.ImportRequest(source,
                    new WikiFeishuTransferService.Target("UPDATE", null, null, null, null, wikiId, before.versionNo(), "APPEND"),
                    UUID.randomUUID().toString()));
            for (int i = 0; i < 30; i++) {
                transfers.poll();
                var job = transfers.job(actor, accepted.jobId());
                if ("SUCCEEDED".equals(job.get("status"))) {
                    var updated = wiki.get(wikiId, actor);
                    assertEquals(before.teamId(), updated.teamId());
                    assertEquals(before.knowledgeCategory(), updated.knowledgeCategory());
                    assertEquals(before.revisionNo() + 1, updated.revisionNo());
                    assertTrue(updated.markdownContent().startsWith(before.markdownContent().stripTrailing()));
                    assertTrue(updated.markdownContent().contains(addition.strip()));
                    System.out.println("FEISHU_APPEND_IMPORT_E2E jobId=" + accepted.jobId() + " wikiId=" + wikiId + " revisionNo=" + updated.revisionNo());
                    return;
                }
                if (!List.of("PENDING", "RUNNING").contains(job.get("status"))) fail("Append import failed: " + job);
                Thread.sleep(1000);
            }
            fail("Append import job timed out");
        } finally { SecurityContextHolder.clearContext(); }
    }
}
