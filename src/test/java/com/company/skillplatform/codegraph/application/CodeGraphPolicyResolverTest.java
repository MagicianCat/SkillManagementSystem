package com.company.skillplatform.codegraph.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CodeGraphPolicyResolverTest {
    private final CodeGraphPolicyResolver resolver=new CodeGraphPolicyResolver(mock(JdbcTemplate.class),new ObjectMapper());

    @Test void requirementReviewerCannotDeepScan(){var policy=resolver.defaultPolicy("REQUIREMENT","reviewer");assertEquals(List.of("query"),policy.allowedTools());assertEquals(1,policy.maxDepth());assertEquals(1,policy.softToolCalls());assertEquals(0,policy.maxPreloadQueries());}
    @Test void architectureAnalystOwnsDeepResearchBudget(){var policy=resolver.defaultPolicy("ARCHITECTURE_DESIGN","analyst");assertTrue(policy.allowedTools().containsAll(List.of("impact","trace","route-map")));assertEquals(3,policy.maxDepth());assertEquals(7,policy.softToolCalls());assertEquals(2,policy.maxPreloadQueries());}
    @Test void uiReviewerMayFinishWithoutPreload(){var policy=resolver.defaultPolicy("UI_DESIGN","reviewer");assertEquals(List.of("query"),policy.allowedTools());assertEquals(0,policy.maxPreloadQueries());assertTrue(policy.enabled());}
    @Test void gitWatchGetsNoCloudGraphBudget(){var policy=resolver.defaultPolicy("BACKEND_CODING","writer");assertFalse(policy.enabled());assertTrue(policy.allowedTools().isEmpty());assertEquals(0,policy.softToolCalls());}
    @Test void customValuesAreClampedToPlatformAndStageLimits(){var custom=new CodeGraphPolicyResolver.NodePolicy(1,true,List.of("code_graph_impact"),999,99,999,99,999999,99,"REUSE_FIRST","stop",999,999);var value=resolver.validate(custom,"REQUIREMENT","clarifier");assertEquals(List.of("impact"),value.allowedTools());assertEquals(9,value.softToolCalls());assertEquals(5,value.maxDepth());assertEquals(50,value.maxNodes());assertEquals(5,value.maxPaths());assertEquals(8000,value.maxResultChars());assertEquals(2,value.maxPreloadQueries());assertEquals(9,value.stageMaxToolCalls());assertEquals(7,value.stageMaxEngineQueries());}
}
