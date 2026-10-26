package com.company.skillplatform.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ExternalDevelopmentStageControllerTest {
    private final DevelopmentStageCatalogService catalog = mock(DevelopmentStageCatalogService.class);
    private final ExternalDevelopmentStageController controller;

    ExternalDevelopmentStageControllerTest() throws Exception {
        controller = new ExternalDevelopmentStageController(catalog, new ObjectMapper(), "{\"platform-a\":\"secret\"}");
    }

    @Test void returnsStageDictionaryForValidIntegrationKey() {
        var dictionary = new DevelopmentStageCatalogService.Dictionary(java.util.List.of(
                new DevelopmentStageCatalogService.Stage("BACKEND_CODING", "后端编码", "backend",
                        java.util.List.of(new DevelopmentStageCatalogService.SubStage("backend.jvm", "Java 与 JVM")))));
        when(catalog.list()).thenReturn(dictionary);
        var response = controller.stages("secret");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().code());
        assertEquals("backend.jvm", response.getBody().data().stages().get(0).subStages().get(0).subStageKey());
    }

    @Test void rejectsUnknownKeyAndMapsServiceFailure() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.stages("wrong").getStatusCode());
        when(catalog.list()).thenThrow(new IllegalStateException("taxonomy unavailable"));
        var response = controller.stages("secret");
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(5001, response.getBody().code());
    }
}
