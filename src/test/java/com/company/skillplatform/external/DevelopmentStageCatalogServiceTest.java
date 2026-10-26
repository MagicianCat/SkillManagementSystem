package com.company.skillplatform.external;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DevelopmentStageCatalogServiceTest {
    @Test void returnsOnlyActiveStagesAndSelectableLeavesInSortOrder() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.queryForList(anyString())).thenReturn(List.of(
                Map.of("stageKey", "backend", "stageName", "后端编码", "stageOrder", 5,
                        "subStageKey", "backend.jvm", "subStageName", "Java 与 JVM", "subStageOrder", 1),
                Map.of("stageKey", "backend", "stageName", "后端编码", "stageOrder", 5,
                        "subStageKey", "backend.api", "subStageName", "API 与连接器", "subStageOrder", 2)));
        var result = new DevelopmentStageCatalogService(db).list();
        assertEquals(1, result.stages().size());
        assertEquals("BACKEND_CODING", result.stages().get(0).developmentStage());
        assertEquals("backend", result.stages().get(0).stageKey());
        assertEquals(List.of("backend.jvm", "backend.api"), result.stages().get(0).subStages().stream().map(DevelopmentStageCatalogService.SubStage::subStageKey).toList());
    }

    @Test void validatesParentStageAndSubStageLeaf() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.queryForList(anyString(), eq("backend.jvm"))).thenReturn(List.of(Map.of(
                "subStageKey", "backend.jvm", "stageKey", "backend", "developmentStage", "BACKEND_CODING")));
        var catalog = new DevelopmentStageCatalogService(db);
        assertEquals(new DevelopmentStageCatalogService.ResolvedStage("BACKEND_CODING", "backend.jvm"), catalog.resolve("BACKEND_CODING", "backend.jvm"));
        assertThrows(DevelopmentStageCatalogService.StageValidationException.class, () -> catalog.resolve("FRONTEND_CODING", "backend.jvm"));
        when(db.queryForList(anyString(), eq("backend.unknown"))).thenReturn(List.of());
        assertThrows(DevelopmentStageCatalogService.StageValidationException.class, () -> catalog.resolve(null, "backend.unknown"));
    }
}
