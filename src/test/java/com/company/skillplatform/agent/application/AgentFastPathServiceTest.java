package com.company.skillplatform.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentRepository;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentSkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AgentFastPathServiceTest {
    private final AgentFastPathService service = new AgentFastPathService(
            mock(WikiDocumentRepository.class), mock(WikiDocumentSkillRepository.class), new ObjectMapper());

    @Test
    void recognizesFullDevelopmentFlowQueries() {
        assertThat(service.matches("请推荐研发全链路的全部 skill")).isTrue();
        assertThat(service.matches("研发全流程有哪些技能？")).isTrue();
        assertThat(service.matches("我想安装开发全流程最佳实践的全部 skill")).isTrue();
        assertThat(service.matches("查看完整研发流程 Skill")).isTrue();
    }

    @Test
    void doesNotInterceptUnrelatedQuestions() {
        assertThat(service.matches("研发全链路是什么？")).isFalse();
        assertThat(service.matches("推荐后端编码 Skill")).isFalse();
        assertThat(service.matches(null)).isFalse();
    }
}
