package com.company.skillplatform.external;

import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalBulkImportCategoryTest {
    @Test void rejectsMissingAndInvalidCategoryBeforeCreatingJob() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        var service = new ExternalBulkImportService(db, new ObjectMapper(), mock(ExternalResourceAdminService.class),
                null, null, null, null, null, mock(TransactionTemplate.class));
        var selections = List.of(new ExternalBulkImportService.WikiSelection(1L, List.of("readme.md")));

        for (String value : new String[]{null, "", "  "}) {
            assertThatThrownBy(() -> service.enqueueWikis(1L, null, null, false, value, selections, 2L))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo("EXTERNAL_WIKI_CATEGORY_REQUIRED"));
        }
        for (String value : new String[]{"skill_tool", "UNKNOWN", "FDE_WIKI"}) {
            assertThatThrownBy(() -> service.enqueueWikis(1L, null, null, false, value, selections, 2L))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo("WIKI_KNOWLEDGE_CATEGORY_INVALID"));
        }
        verifyNoInteractions(db);
    }

    @Test void acceptsAllFourAdminChoices() {
        for (String value : new String[]{"SKILL_TOOL", "PROJECT_CONTEXT", "INTERNAL_KNOWLEDGE", "OTHER"})
            assertThat(ExternalBulkImportService.requiredWikiCategory(value)).isEqualTo(value);
    }

    @Test void storesOneCategoryForSelectionsFromMultipleZips() throws Exception {
        JdbcTemplate db = mock(JdbcTemplate.class);
        var service = new ExternalBulkImportService(db, new ObjectMapper(), mock(ExternalResourceAdminService.class),
                null, null, null, null, null, mock(TransactionTemplate.class));
        when(db.queryForObject(contains("FROM external_role"), eq(Integer.class), eq(7L))).thenReturn(1);
        when(db.queryForObject(contains("FROM external_import_job"), eq(Integer.class), eq(7L), eq("DOCUMENT"), isNull())).thenReturn(0);
        when(db.queryForList(contains("FROM external_role_resource"), eq(7L), eq("DOCUMENT"))).thenReturn(List.of(
                Map.of("resourceId", 11L, "versionId", 101L), Map.of("resourceId", 12L, "versionId", 102L)));
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("INSERT INTO external_import_job"), anyInt())).thenReturn(statement);
        when(db.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).thenAnswer(call -> {
            PreparedStatementCreator creator = call.getArgument(0);
            KeyHolder keys = call.getArgument(1);
            creator.createPreparedStatement(connection);
            keys.getKeyList().add(Map.of("GENERATED_KEY", 45L));
            return 1;
        });

        long id = service.enqueueWikis(7L, null, null, false, "INTERNAL_KNOWLEDGE", List.of(
                new ExternalBulkImportService.WikiSelection(11L, List.of("a.md", "b.md")),
                new ExternalBulkImportService.WikiSelection(12L, List.of("c.md"))), 3L);

        assertThat(id).isEqualTo(45L);
        verify(statement).setString(8, "INTERNAL_KNOWLEDGE");
        verify(statement).setString(eq(7), argThat(json -> json.contains("a.md") && json.contains("b.md") && json.contains("c.md")));
    }
}
