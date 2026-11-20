package com.company.skillplatform.wiki;

import com.company.skillplatform.auth.application.AuthService;
import com.company.skillplatform.wiki.application.WikiDocumentService;
import com.company.skillplatform.wiki.feishu.WikiFeishuTransferService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly opt in. Writes one real Markdown file to the actor's Feishu root. */
@SpringBootTest
@EnabledIfSystemProperty(named = "wiki.feishu.export.e2e.user-id", matches = "[0-9]+")
class WikiFeishuOutboundE2eTest {
    @Autowired AuthService auth;
    @Autowired WikiDocumentService wiki;
    @Autowired WikiFeishuTransferService transfers;
    @Autowired JdbcTemplate db;

    @Test void pushExistingEditableWikiToFeishu() throws Exception {
        long actor = Long.parseLong(System.getProperty("wiki.feishu.export.e2e.user-id"));
        var principal = auth.currentUser(actor);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null,
                principal.permissions().stream().map(SimpleGrantedAuthority::new).toList()));
        try {
            String format = System.getProperty("wiki.feishu.export.e2e.format", "MARKDOWN_FILE");
            List<Long> candidates = db.query("SELECT d.id FROM wiki_document d JOIN wiki_document_revision r ON r.id=d.current_revision_id WHERE d.created_by=? AND d.status='ACTIVE' AND (? <> 'DOCX' OR (CHAR_LENGTH(r.markdown_content)<2000 AND r.markdown_content NOT LIKE '%!\\[%' AND r.markdown_content NOT LIKE '%<table%')) ORDER BY d.id DESC LIMIT 30",
                    (rs, row) -> rs.getLong(1), actor, format);
            Long selected = null;
            for (Long candidate : candidates) {
                try { if (wiki.get(candidate, actor).canEdit()) { selected = candidate; break; } }
                catch (Exception ignored) { }
            }
            assertNotNull(selected, "No existing editable Wiki for actor");
            var source = wiki.get(selected, actor);
            var accepted = transfers.startExport(actor, selected,
                    new WikiFeishuTransferService.ExportRequest(format, null, source.revisionNo(), "CURRENT", UUID.randomUUID().toString()));
            for (int i = 0; i < 30; i++) {
                transfers.poll();
                var job = transfers.job(actor, accepted.jobId());
                if ("SUCCEEDED".equals(job.get("status"))) {
                    assertTrue(String.valueOf(job.get("feishuOutputUrl")).contains("feishu.cn"));
                    System.out.println("FEISHU_EXPORT_E2E jobId=" + accepted.jobId() + " wikiId=" + selected + " url=" + job.get("feishuOutputUrl"));
                    return;
                }
                if (!List.of("PENDING", "RUNNING").contains(job.get("status"))) fail("Export failed: " + job);
                Thread.sleep(1000);
            }
            fail("Export job timed out");
        } finally { SecurityContextHolder.clearContext(); }
    }
}
