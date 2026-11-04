package com.company.skillplatform.agent.application;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="DECISION_MODEL_API_KEY", matches=".+")
class IntentPlanLiveIntegrationTest {
    @Autowired IntentPlanService plans;

    @Test void classifiesAndFreezesRealModelResponse() {
        String runKey="intent-e2e-"+UUID.randomUUID();
        var plan=plans.classify(runKey,"只给我确认函后端开发工程师的上下文 Wiki，不要推荐 Skill。",List.of());
        assertEquals("READY",plan.get("status"));
        assertFalse((Boolean)plan.get("recommendSkills"));
        assertFalse(plans.allows(runKey,"recommendation.submit"));
        assertEquals(plan.get("status"),plans.load(runKey).get("status"));
        assertEquals(plan.get("recommendSkills"),plans.load(runKey).get("recommendSkills"));
    }
    @Test void understandsImplicitRoleContextRequest() {
        String runKey="intent-e2e-"+UUID.randomUUID();
        var plan=plans.classify(runKey,"确认函后端开发工程师有哪些上下文",List.of());
        assertEquals("READY",plan.get("status"));
        assertTrue(((java.util.Collection<?>)plan.get("intents")).contains("ROLE_CONTEXT"),String.valueOf(plan));
        assertFalse((Boolean)plan.get("recommendSkills"));
    }
    @Test void recognizesExplicitRoleSkillRequest() {
        String runKey="intent-e2e-"+UUID.randomUUID();
        var plan=plans.classify(runKey,"确认函需求分析师有哪些skill",List.of("确认函后端开发工程师有哪些上下文","这里是角色上下文文档"));
        assertEquals("READY",plan.get("status"));
        assertTrue((Boolean)plan.get("recommendSkills"),String.valueOf(plan));
        assertTrue(plans.allows(runKey,"recommendation.submit"));
    }
}
