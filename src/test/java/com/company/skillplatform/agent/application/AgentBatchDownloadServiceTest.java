package com.company.skillplatform.agent.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.skillplatform.agent.infrastructure.entity.AgentRecommendationEntity;
import com.company.skillplatform.agent.infrastructure.entity.AgentRunEntity;
import com.company.skillplatform.agent.infrastructure.repository.AgentRecommendationRepository;
import com.company.skillplatform.agent.infrastructure.repository.AgentRunRepository;
import com.company.skillplatform.bundle.application.BundleService;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.skill.application.SkillService;
import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import com.company.skillplatform.version.domain.LifecycleStatus;
import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;
import com.company.skillplatform.version.infrastructure.repository.SkillVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentBatchDownloadServiceTest {
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final AgentRecommendationRepository recommendations = mock(AgentRecommendationRepository.class);
    private final SkillService skills = mock(SkillService.class);
    private final SkillVersionRepository versions = mock(SkillVersionRepository.class);
    private final BundleService bundles = mock(BundleService.class);
    private AgentBatchDownloadService service;

    @BeforeEach void setUp() {
        service = new AgentBatchDownloadService(runs, recommendations, skills, versions, bundles, new ObjectMapper());
        when(runs.findByRunKeyAndSessionOwnerUserId("run", 7L)).thenReturn(Optional.of(mock(AgentRunEntity.class)));
        when(recommendations.findByRunRef("run")).thenReturn(Optional.of(new AgentRecommendationEntity("run", "summary", """
                {"items":[{"skillKey":"one","versionId":11},{"skillKey":"two","versionId":22}]}
                """)));
        when(runs.findByRunKeyAndSessionOwnerUserId("run", 7L).orElseThrow().getSession())
                .thenReturn(new com.company.skillplatform.agent.infrastructure.entity.AgentSessionEntity(7L,"skill-advisor","CODEBUDDY","MACOS"));
    }

    @Test void createsSubsetFromExactRecommendedVersionId() {
        SkillVersionEntity version = mock(SkillVersionEntity.class);
        SkillEntity skill = mock(SkillEntity.class);
        when(skill.getSkillKey()).thenReturn("two");
        when(version.getSkill()).thenReturn(skill);
        when(version.getLifecycleStatus()).thenReturn(LifecycleStatus.PUBLISHED);
        when(versions.findById(22L)).thenReturn(Optional.of(version));

        service.create("run", 7L, "CODEBUDDY", "MACOS", List.of("two"));

        verify(bundles).createAgentBundle(List.of(version), "CODEBUDDY", "MACOS", 7L);
    }

    @Test void rejectsSkillOutsideRecommendation() {
        assertThatThrownBy(() -> service.create("run", 7L, "CODEBUDDY", "MACOS", List.of("other")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.getCode()).isEqualTo("AGENT_BUNDLE_SKILL_NOT_RECOMMENDED"));
    }

    @Test void rejectsRecommendationWithoutTrustedVersionId() {
        when(recommendations.findByRunRef("run")).thenReturn(Optional.of(
                new AgentRecommendationEntity("run", "summary", "{\"items\":[{\"skillKey\":\"one\"}]}")));
        assertThatThrownBy(() -> service.create("run", 7L, "CODEBUDDY", "MACOS", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.getCode()).isEqualTo("AGENT_RECOMMENDATION_INVALID"));
    }

    @Test void rejectsTargetThatDoesNotMatchRunContext() {
        assertThatThrownBy(() -> service.create("run",7L,"OPENCODE","MACOS",null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.getCode()).isEqualTo("AGENT_BUNDLE_CONTEXT_MISMATCH"));
    }

    @Test void rejectsInvalidTargetEnum() {
        assertThatThrownBy(() -> service.create("run",7L,"CODEBUDDY","SOLARIS",null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.getCode()).isEqualTo("AGENT_TARGET_INVALID"));
    }
}
