package com.company.skillplatform.collabproject.asdm.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmGitlabClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Constructor;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

class CollabAsdmGroupAdminAccessTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test void administratorCanReadAsdmConfigurationForAnotherOwnersGroup() {
        var service = new CollabAsdmConfigurationService(jdbc, null,
                mock(CollabAsdmGitlabClient.class), null,
                new ObjectMapper(), mock(PlatformTransactionManager.class));
        Map<String, Object> group = Map.of("id", 7L, "owner_user_id", 10L, "status", "PUBLISHED");
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(20L))).thenReturn(1);
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "requireGroupView", group, 20L));
    }

    @Test void nonMemberStillCannotReadAsdmConfiguration() {
        var service = new CollabAsdmConfigurationService(jdbc, null,
                mock(CollabAsdmGitlabClient.class), null,
                new ObjectMapper(), mock(PlatformTransactionManager.class));
        Map<String, Object> group = Map.of("id", 7L, "owner_user_id", 10L, "status", "PUBLISHED");
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(20L))).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(7L), eq(20L), eq("PUBLISHED"))).thenReturn(0);
        assertThrows(BusinessException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "requireGroupView", group, 20L));
    }

    @Test void administratorCanReadRulesForAnotherOwnersGroup() throws Exception {
        var service = new CollabAsdmRuleService(jdbc, new ObjectMapper(),
                mock(CollabAsdmConfigurationService.class), mock(CollabAsdmGitlabClient.class));
        Class<?> type = Class.forName(CollabAsdmRuleService.class.getName() + "$GroupRow");
        Constructor<?> constructor = type.getDeclaredConstructor(long.class, String.class, long.class, String.class);
        constructor.setAccessible(true);
        Object group = constructor.newInstance(7L, "group", 10L, "PUBLISHED");
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(20L))).thenReturn(1);
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "requireGroupView", group, 20L));
    }
}
