package com.company.skillplatform.agent.application;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetrievalPlannerTest {
    @Test void mixedRequestKeepsWikiAndRecommendationScopesIndependent() {
        var resolver=mock(QueryEntityResolver.class);
        var store=mock(RetrievalPlanStore.class);
        var role=new QueryEntityResolver.Mention(26,"投监项目组","platform-a",1,6);
        when(resolver.resolve(eq(5L),eq("USER_VISIBLE"),contains("投监项目组"))).thenReturn(List.of(role));
        var planner=new RetrievalPlanner(resolver,store,true);

        var result=planner.plan("run",5,"USER_VISIBLE","找投监项目组的上下文，再推荐一些适合后端开发的 Skill。").orElseThrow();

        assertThat(result.strategy()).isEqualTo("MIXED");
        assertThat(result.tasks()).containsExactly(
                new RetrievalPlanner.Task("t1",26,"DOCUMENT","PROJECT_CONTEXT"),
                new RetrievalPlanner.Task("t2",0,"RECOMMEND",null,"BACKEND_CODING"));
    }

    @Test void negativeRecommendationDoesNotGrantRecommendationCapability() {
        var resolver=mock(QueryEntityResolver.class);
        when(resolver.resolve(eq(5L),eq("USER_VISIBLE"),anyString()))
                .thenReturn(List.of(new QueryEntityResolver.Mention(26,"投监项目组","platform-a",1,6)));
        var planner=new RetrievalPlanner(resolver,mock(RetrievalPlanStore.class),true);
        var result=planner.plan("run",5,"USER_VISIBLE","找投监项目组的上下文，不要推荐后端开发的 Skill").orElseThrow();
        assertThat(result.tasks()).noneMatch(task->"RECOMMEND".equals(task.resourceKind()));
    }
    @Test void comparisonAcceptsRoleNamesContainingAnalysisAndPreservesMentionOrder() {
        var resolver=mock(QueryEntityResolver.class);
        when(resolver.resolve(eq(5L),eq("USER_VISIBLE"),contains("比较"))).thenReturn(List.of(
                new QueryEntityResolver.Mention(21,"数据分析工程师","platform-a",8,15),
                new QueryEntityResolver.Mention(26,"投监项目组","platform-a",2,7)));
        var planner=new RetrievalPlanner(resolver,mock(RetrievalPlanStore.class),true);
        var result=planner.plan("run",5,"USER_VISIBLE","比较投监项目组和数据分析工程师的上下文").orElseThrow();
        assertThat(result.tasks()).containsExactly(
                new RetrievalPlanner.Task("t1",26,"DOCUMENT","PROJECT_CONTEXT"),
                new RetrievalPlanner.Task("t2",21,"DOCUMENT","PROJECT_CONTEXT"));
    }
}
