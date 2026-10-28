package com.company.skillplatform.wiki.interfaces;

import com.company.skillplatform.wiki.application.WikiDocumentService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WikiDocumentControllerDownloadTest {
    private final WikiDocumentService service = mock(WikiDocumentService.class);
    private final Authentication auth = mock(Authentication.class);
    private final WikiDocumentController controller = new WikiDocumentController(service);

    @Test
    void packagesOnlyDocumentsReadForTheCurrentUser() throws Exception {
        when(auth.getPrincipal()).thenReturn(42L);
        when(service.get(11L, 42L)).thenReturn(document(11L, "需求规范", "# 需求"));
        when(service.get(12L, 42L)).thenReturn(document(12L, "测试计划", "# 测试"));

        var response = controller.downloadSelected(new WikiDocumentController.DownloadRequest(List.of(11L, 12L, 11L)), auth);
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("wiki-documents.zip");
        try (var zip = new ZipInputStream(new ByteArrayInputStream(response.getBody().getByteArray()), StandardCharsets.UTF_8)) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("wiki-11-需求规范.md");
            assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("# 需求");
            assertThat(zip.getNextEntry().getName()).isEqualTo("wiki-12-测试计划.md");
            assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("# 测试");
            assertThat(zip.getNextEntry()).isNull();
        }
        verify(service).get(11L, 42L);
        verify(service).get(12L, 42L);
    }

    private static WikiDocumentService.DocumentView document(long id, String title, String content) {
        return new WikiDocumentService.DocumentView(id, title, "SKILL_GUIDE", 1L, false, content, 1, 1, List.of(), false, true, null, null, null, null, false);
    }
}
