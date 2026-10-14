package com.company.skillplatform.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.storage.domain.ObjectStoragePort;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.springframework.jdbc.core.JdbcTemplate;

class ExternalResourceAdminServiceTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ObjectStoragePort storage=mock(ObjectStoragePort.class);
    private final ExternalResourceAdminService service=new ExternalResourceAdminService(db,storage,null,null,null,null,null,null);

    @Test void listsNestedMarkdownButNotOtherFiles() throws Exception {
        when(db.queryForList(eq("SELECT v.*,r.resource_type FROM external_resource_version v JOIN external_resource r ON r.id=v.resource_id WHERE v.id=? AND v.storage_status='AVAILABLE'"),eq(7L)))
                .thenReturn(List.of(Map.of("resource_type","DOCUMENT","object_key","raw/7")));
        when(storage.get("raw/7")).thenAnswer(call -> new ByteArrayInputStream(zip(Map.of("a/intro.md","hello","image.png","binary"))));
        assertEquals(List.of("a/intro.md"),service.documents(7L).stream().map(ExternalResourceAdminService.DocumentEntry::path).toList());
        assertEquals("hello",service.documentContent(7L,"a/intro.md"));
    }

    @Test void rejectsTraversalAtPreviewTime() throws Exception {
        when(db.queryForList(eq("SELECT v.*,r.resource_type FROM external_resource_version v JOIN external_resource r ON r.id=v.resource_id WHERE v.id=? AND v.storage_status='AVAILABLE'"),eq(8L)))
                .thenReturn(List.of(Map.of("resource_type","DOCUMENT","object_key","raw/8")));
        when(storage.get("raw/8")).thenAnswer(call -> new ByteArrayInputStream(zip(Map.of("../escape.md","no"))));
        assertThrows(BusinessException.class,()->service.documents(8L));
    }

    @Test void rejectsSymlinkAtPreviewTime() throws Exception {
        when(db.queryForList(eq("SELECT v.*,r.resource_type FROM external_resource_version v JOIN external_resource r ON r.id=v.resource_id WHERE v.id=? AND v.storage_status='AVAILABLE'"),eq(9L)))
                .thenReturn(List.of(Map.of("resource_type","DOCUMENT","object_key","raw/9")));
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip=new ZipArchiveOutputStream(out)) {
            ZipArchiveEntry link=new ZipArchiveEntry("secret.md"); link.setUnixMode(UnixStat.LINK_FLAG | 0777);
            zip.putArchiveEntry(link); zip.write("outside".getBytes(StandardCharsets.UTF_8)); zip.closeArchiveEntry();
        }
        when(storage.get("raw/9")).thenAnswer(call -> new ByteArrayInputStream(out.toByteArray()));
        assertThrows(BusinessException.class,()->service.documents(9L));
    }

    private static byte[] zip(Map<String,String> entries) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try (ZipOutputStream zip=new ZipOutputStream(out,StandardCharsets.UTF_8)) {
            for (var entry:entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
