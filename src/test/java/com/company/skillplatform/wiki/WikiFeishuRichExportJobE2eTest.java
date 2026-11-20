package com.company.skillplatform.wiki;

import com.company.skillplatform.auth.application.AuthService;
import com.company.skillplatform.wiki.application.WikiDocumentService;
import com.company.skillplatform.wiki.feishu.FeishuDocxMarkdownConverter;
import com.company.skillplatform.wiki.feishu.FeishuDriveGateway;
import com.company.skillplatform.wiki.feishu.WikiFeishuTransferService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real write to team Wiki and personal Feishu Drive. */
@SpringBootTest
@EnabledIfSystemProperty(named = "wiki.feishu.rich-export.e2e.user-id", matches = "[0-9]+")
class WikiFeishuRichExportJobE2eTest {
    @Autowired AuthService auth;
    @Autowired WikiDocumentService wiki;
    @Autowired WikiFeishuTransferService transfers;
    @Autowired FeishuDriveGateway drive;
    @Autowired FeishuDocxMarkdownConverter converter;

    @Test void exportWikiTableThroughJob() throws Exception {
        long actor = Long.parseLong(System.getProperty("wiki.feishu.rich-export.e2e.user-id"));
        long teamId = Long.parseLong(System.getProperty("wiki.feishu.rich-export.e2e.team-id"));
        var principal = auth.currentUser(actor);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null,
                principal.permissions().stream().map(SimpleGrantedAuthority::new).toList()));
        try {
            String markdown = "# Wiki 富文本导出验证\n\n| 字段 | 说明 |\n| --- | --- |\n| A | **加粗** |\n| B | `代码` |\n\n- [x] 已完成\n";
            var source = wiki.create(new WikiDocumentService.CreateCommand("研枢 Wiki 飞书组件互转验证-" + System.currentTimeMillis(), teamId,
                    List.of(), markdown, null, "INTERNAL_KNOWLEDGE"), actor, "wiki-feishu-rich-export-test");
            var accepted = transfers.startExport(actor, source.id(), new WikiFeishuTransferService.ExportRequest("DOCX", null,
                    source.revisionNo(), "CURRENT", UUID.randomUUID().toString()));
            for (int i = 0; i < 30; i++) {
                transfers.poll();
                var job = transfers.job(actor, accepted.jobId());
                if ("SUCCEEDED".equals(job.get("status"))) {
                    String token = String.valueOf(job.get("feishuOutputUrl")).replaceFirst("^.*/", "");
                    String converted = drive.read(actor, "DOCX", token, converter);
                    assertTrue(converted.contains("| 字段 | 说明 |"));
                    assertTrue(converted.contains("| A | **加粗** |"));
                    assertTrue(converted.contains("- [x] 已完成"));
                    System.out.println("FEISHU_RICH_EXPORT_JOB_E2E wikiId=" + source.id() + " jobId=" + accepted.jobId()
                            + " url=" + job.get("feishuOutputUrl"));
                    return;
                }
                if (!List.of("PENDING", "RUNNING").contains(job.get("status"))) fail("Export failed: " + job);
                Thread.sleep(1000);
            }
            fail("Export job timed out");
        } finally { SecurityContextHolder.clearContext(); }
    }
}
