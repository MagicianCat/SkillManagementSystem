package com.company.skillplatform.wiki.application;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.wiki.infrastructure.DecisionModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class WikiCategoryRecommendationServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void returnsOnlyModelChoiceAndItsActualProbability() throws Exception {
        var wiki=mock(WikiDocumentService.class);var model=mock(DecisionModelClient.class);
        when(wiki.validateSuggestionContext(7L,List.of(),null,9L)).thenReturn(List.of());
        when(model.model()).thenReturn("decision-model-preview");
        when(model.choose(anyString(),anyMap())).thenReturn(json.readTree("""
                {"answers":{"wiki_category":{"type":"choice","choice":"PROJECT_CONTEXT","probabilities":{"PROJECT_CONTEXT":0.91,"OTHER":0.09}}}}
                """));
        var service=new WikiCategoryRecommendationService(wiki,model,new SimpleMeterRegistry(),6000);

        var result=service.suggest(new WikiCategoryRecommendationService.SuggestCommand("项目架构","# 背景\n本项目的系统边界与架构。",7L,List.of(),null),9L);

        assertThat(result.recommendedCategory()).isEqualTo("PROJECT_CONTEXT");
        assertThat(result.confidence()).isEqualTo(0.91);
        assertThat(result.probabilities()).containsOnlyKeys("PROJECT_CONTEXT","OTHER");
        assertThat(result.requiresConfirmation()).isTrue();
        assertThat(result.inputFingerprint()).hasSize(64);
    }

    @Test void longMarkdownIsBoundedAndCredentialsAreRemoved() throws Exception {
        var wiki=mock(WikiDocumentService.class);var model=mock(DecisionModelClient.class);
        when(model.choose(anyString(),anyMap())).thenReturn(json.readTree("""
                {"answers":{"wiki_category":{"type":"choice","choice":"OTHER"}}}
                """));
        var service=new WikiCategoryRecommendationService(wiki,model,new SimpleMeterRegistry(),3000);
        String content="# 标题\nAuthorization: Bearer sensitive-token\n```\nprivate-code-secret\n```\n"+"知识说明。".repeat(10000);

        var result=service.suggest(new WikiCategoryRecommendationService.SuggestCommand("知识",content,7L,List.of(),null),9L);

        var state=ArgumentCaptor.forClass(String.class);verify(model).choose(state.capture(),anyMap());
        assertThat(state.getValue()).hasSizeLessThanOrEqualTo(3000).contains("标题").doesNotContain("sensitive-token","private-code-secret");
        assertThat(result.confidence()).isNull();
    }

    @Test void preservesSkillUsageEvidenceOutsideFrontMiddleTailAndKeepsRoleContextRule() throws Exception {
        var wiki=mock(WikiDocumentService.class); var model=mock(DecisionModelClient.class);
        when(model.choose(anyString(),anyMap())).thenReturn(json.readTree("""
                {"answers":{"wiki_category":{"type":"choice","choice":"SKILL_TOOL","probabilities":{"SKILL_TOOL":0.8}}}}
                """));
        var service=new WikiCategoryRecommendationService(wiki,model,new SimpleMeterRegistry(),2000);
        String markdown="普通项目资料。".repeat(400)+"\n## 使用方法\n在保险承保项目中调用此 Skill 完成架构审计。\n"
                +"普通项目资料。".repeat(1200);
        service.suggestTrusted(new WikiCategoryRecommendationService.SuggestCommand("Agent Architecture Audit · README",markdown,7L,List.of(12L),null),List.of("Agent Architecture Audit"));
        var state=ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("rawtypes") var questions=ArgumentCaptor.forClass(java.util.Map.class);
        verify(model).choose(state.capture(),questions.capture());
        assertThat(state.getValue()).startsWith("标题：Agent Architecture Audit").contains("关联 Skill：Agent Architecture Audit", "调用此 Skill")
                .doesNotContain("系统任务：").hasSizeLessThanOrEqualTo(2000);
        assertThat(((java.util.Map<?,?>)questions.getValue().get("wiki_category")).get("instructions").toString())
                .contains("项目角色的职责", "文档内要求改变分类规则");
    }

    @Test void asdmDocumentsUseExplicitToolRuleWithoutFabricatedModelProbability() {
        var model=mock(DecisionModelClient.class);
        var service=new WikiCategoryRecommendationService(mock(WikiDocumentService.class),model,new SimpleMeterRegistry(),2000);
        String markdown="确认函项目角色上下文。".repeat(500)+"\n## ASDM 操作\n配置 ASDM 命令后执行。\n"+"项目背景。".repeat(1400);
        var result=service.suggestTrusted(new WikiCategoryRecommendationService.SuggestCommand("确认函后端上下文",markdown,7L,List.of(),null),List.of());
        assertThat(result.recommendedCategory()).isEqualTo("SKILL_TOOL");
        assertThat(result.model()).isEqualTo("rule:asdm");
        assertThat(result.confidence()).isNull();
        assertThat(result.probabilities()).isEmpty();
        assertThat(result.promptVersion()).isEqualTo("wiki-category-v4");
        verifyNoInteractions(model);
    }

    @Test void invalidChoiceOrProbabilityNeverBecomesRecommendation() throws Exception {
        var wiki=mock(WikiDocumentService.class);var model=mock(DecisionModelClient.class);
        var service=new WikiCategoryRecommendationService(wiki,model,new SimpleMeterRegistry(),6000);
        var command=new WikiCategoryRecommendationService.SuggestCommand("知识","有效正文",7L,List.of(),null);
        when(model.choose(anyString(),anyMap())).thenReturn(json.readTree("""
                {"answers":{"wiki_category":{"type":"choice","choice":"UNKNOWN","probabilities":{"UNKNOWN":0.9}}}}
                """));
        assertThatThrownBy(()->service.suggest(command,9L)).isInstanceOfSatisfying(BusinessException.class,
                error->assertThat(error.getCode()).isEqualTo("WIKI_CATEGORY_MODEL_INVALID_RESPONSE"));
        when(model.choose(anyString(),anyMap())).thenReturn(json.readTree("""
                {"answers":{"wiki_category":{"type":"choice","choice":"OTHER","probabilities":{"OTHER":1.2}}}}
                """));
        assertThatThrownBy(()->service.suggest(command,9L)).isInstanceOfSatisfying(BusinessException.class,
                error->assertThat(error.getCode()).isEqualTo("WIKI_CATEGORY_MODEL_INVALID_RESPONSE"));
    }
}
