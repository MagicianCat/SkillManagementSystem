package com.company.skillplatform.agent.application;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"agent.runtime.base-url=http://127.0.0.1:33091"})
@EnabledIfEnvironmentVariable(named="DECISION_MODEL_API_KEY", matches=".+")
class IntentConversationLiveE2ETest {
    @LocalServerPort int backendPort;
    @Autowired AgentConversationService conversations;
    @Autowired IntentPlanService plans;
    @Autowired AgentRunService tokenService;
    @Autowired JdbcTemplate jdbc;

    @Test void roleWikiRequestCannotSubmitSkillRecommendation() throws Exception {
        Path runtime=Path.of("../deepseek-harness/apps/skill-platform-runtime").toAbsolutePath().normalize();
        ProcessBuilder builder=new ProcessBuilder("pnpm","dev");
        builder.directory(runtime.toFile());
        builder.environment().put("PORT","33091");
        builder.environment().put("SKILL_PLATFORM_MCP_URL","http://127.0.0.1:"+backendPort+"/internal/mcp");
        builder.redirectErrorStream(true);
        builder.redirectOutput(Path.of("target/intent-e2e-runtime.log").toFile());
        Process process=builder.start();
        try {
            Thread.sleep(2000);
            assertTrue(process.isAlive(),"DSH runtime failed to start");
            Long userId=jdbc.queryForObject("select id from iam_user order by id limit 1",Long.class);
            assertNotNull(userId);
            var session=conversations.create(userId,"skill-advisor",null,null);
            String runKey=conversations.send(session.session().sessionKey(),userId,"确认函后端开发工程师有哪些上下文").runKey();
            long deadline=System.nanoTime()+Duration.ofMinutes(4).toNanos();
            AgentConversationService.RunView run;
            do {
                Thread.sleep(1000);
                run=conversations.run(runKey,userId);
            } while((run.status().equals("PENDING")||run.status().equals("RUNNING"))&&System.nanoTime()<deadline);
            assertEquals("SUCCEEDED",run.status(),"Run failed: "+run.errorCode()+" "+run.errorMessage());
            assertTrue(((java.util.Collection<?>)plans.load(runKey).get("intents")).contains("ROLE_CONTEXT"));
            assertFalse(plans.allows(runKey,"recommendation.submit"));
            var deniedRun=tokenService.issueForRun(runKey,userId,"skill-advisor",null,null).run();
            assertThrows(com.company.skillplatform.common.application.BusinessException.class,()->tokenService.requireCapability(deniedRun,"recommendation.submit"));
            assertNull(run.recommendation());
            var view=conversations.get(session.session().sessionKey(),userId);
            assertTrue(view.messages().stream().anyMatch(m->"ASSISTANT".equals(m.role())&&!m.content().isBlank()&&!m.content().contains("请明确本轮需要")));
            String skillRunKey=conversations.send(session.session().sessionKey(),userId,"确认函需求分析师有哪些skill").runKey();
            long skillDeadline=System.nanoTime()+Duration.ofMinutes(4).toNanos();
            AgentConversationService.RunView skillRun;
            do {
                Thread.sleep(1000);
                skillRun=conversations.run(skillRunKey,userId);
            } while((skillRun.status().equals("PENDING")||skillRun.status().equals("RUNNING"))&&System.nanoTime()<skillDeadline);
            assertEquals("SUCCEEDED",skillRun.status(),"Skill run failed: "+skillRun.errorCode()+" "+skillRun.errorMessage());
            assertTrue(plans.allows(skillRunKey,"recommendation.submit"),String.valueOf(plans.load(skillRunKey)));
            assertNotNull(skillRun.recommendation(),"Skill recommendation card was not submitted");
            assertFalse(skillRun.recommendation().items().isEmpty(),"No Skill was recommended");
            assertTrue(skillRun.recommendation().items().stream().allMatch(item->item.get("detailPath") instanceof String path&&!path.isBlank()),"Skill detail links are missing");
        } finally {
            process.destroy();
            process.waitFor(5,TimeUnit.SECONDS);
            if(process.isAlive())process.destroyForcibly();
        }
    }
}
