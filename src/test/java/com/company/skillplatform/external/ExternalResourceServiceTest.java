package com.company.skillplatform.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.company.skillplatform.storage.domain.ObjectStoragePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;

class ExternalResourceServiceTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final ObjectStoragePort storage = mock(ObjectStoragePort.class);
    private final DevelopmentStageCatalogService stages = mock(DevelopmentStageCatalogService.class);
    private final ExternalResourceService service = new ExternalResourceService(db, transactions, storage, stages, 50L * 1024 * 1024);

    @Test void stateOnlyRequestPersistsInactiveStatusAndReturnsZeroCounts() {
        var request = new ExternalResourceService.PushRequest("platform-a",
                new ExternalResourceService.RoleInput("role-1", "Role One", "INACTIVE"), List.of());

        var result = service.push(request, new LinkedMultiValueMap<>(), "req-state");

        assertEquals(0, result.total());
        assertEquals(0, result.created());
        assertEquals(0, result.updated());
        assertEquals(0, result.unchanged());
        assertEquals(0, result.failed());
        verify(db).update(eq("INSERT INTO external_role(source_system,external_role_id,role_name,status) VALUES(?,?,?,?) " +
                        "ON DUPLICATE KEY UPDATE role_name=VALUES(role_name),status=VALUES(status),updated_at=CURRENT_TIMESTAMP"),
                eq("platform-a"), eq("role-1"), eq("Role One"), eq("INACTIVE"));
        verifyNoInteractions(transactions, storage);
    }

    @Test void omittedStatusPreservesExistingStatusEvenWhenResourceUploadFails() {
        org.mockito.Mockito.when(stages.resolve(null, null)).thenReturn(new DevelopmentStageCatalogService.ResolvedStage(null, null));
        var request = new ExternalResourceService.PushRequest("platform-a",
                new ExternalResourceService.RoleInput("role-1", "Renamed Role", null),
                List.of(new ExternalResourceService.ResourceInput("resource-1", "Resource", "SKILL", "1.0", null, "file1")));

        var result = service.push(request, new LinkedMultiValueMap<>(), "req-resource");

        assertEquals(1, result.total());
        assertEquals(1, result.failed());
        verify(db).update(eq("INSERT INTO external_role(source_system,external_role_id,role_name) VALUES(?,?,?) " +
                        "ON DUPLICATE KEY UPDATE role_name=VALUES(role_name)"),
                eq("platform-a"), eq("role-1"), eq("Renamed Role"));
        verifyNoInteractions(transactions, storage);
    }

    @Test void rejectsDocumentWhenStageFieldIsExplicitlyPresentEvenIfNull() {
        var resource = new ExternalResourceService.ResourceInput();
        resource.setResourceId("doc-1"); resource.setResourceName("Doc"); resource.setResourceType("DOCUMENT");
        resource.setFileKey("file1"); resource.setDevelopmentStage(null);
        var request = new ExternalResourceService.PushRequest("platform-a",
                new ExternalResourceService.RoleInput("role-1", "Role One", null), List.of(resource));
        var result = service.push(request, new LinkedMultiValueMap<>(), "req-doc-stage");
        assertEquals("INVALID_RESOURCE", result.failures().get(0).errorCode());
        verifyNoInteractions(stages, transactions, storage);
    }

    @Test void rejectsDuplicateToolsetsBeforeTouchingStorage() throws Exception {
        var resource = asdmResource("""
                [{"toolName":"code-dev-sc274","firstCategoryCode":"backend"},
                 {"toolName":" Code-Dev-SC274 ","firstCategoryCode":"testing"}]
                """);
        var result = service.push(request(resource), new LinkedMultiValueMap<>(), "req-toolset-dup");
        assertEquals(1, result.failed());
        assertEquals("ASDM_TOOLSET_DUPLICATE", result.failures().get(0).errorCode());
        verifyNoInteractions(stages, transactions, storage);
    }

    @Test void rejectsInvalidToolsetNameAndNonArrayMetadata() throws Exception {
        var invalidName = asdmResource("[{\"toolName\":\"../evil\",\"firstCategoryCode\":\"other\"}]");
        var nonArray = asdmResource("{\"toolName\":\"code-dev\"}");
        var nameResult = service.push(request(invalidName), new LinkedMultiValueMap<>(), "req-toolset-name");
        var arrayResult = service.push(request(nonArray), new LinkedMultiValueMap<>(), "req-toolset-array");
        assertEquals("ASDM_TOOLSET_NAME_INVALID", nameResult.failures().get(0).errorCode());
        assertEquals("ASDM_TOOLSET_METADATA_INVALID", arrayResult.failures().get(0).errorCode());
        verifyNoInteractions(stages, transactions, storage);
    }

    private static ExternalResourceService.PushRequest request(ExternalResourceService.ResourceInput resource) {
        return new ExternalResourceService.PushRequest("platform-a",
                new ExternalResourceService.RoleInput("role-1", "Role One", null), List.of(resource));
    }

    private ExternalResourceService.ResourceInput asdmResource(String commandsJson) throws Exception {
        var resource = new ExternalResourceService.ResourceInput();
        resource.setResourceId("asdm-1");
        resource.setResourceName("ASDM Tool");
        resource.setResourceType("ASDM-TOOL");
        resource.setFileKey("file1");
        resource.setAsdmCommands(new ObjectMapper().readTree(commandsJson));
        return resource;
    }
}
