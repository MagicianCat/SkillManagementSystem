package com.company.skillplatform.skill.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.company.skillplatform.artifact.infrastructure.entity.SkillArtifactEntity;
import com.company.skillplatform.artifact.infrastructure.repository.SkillArtifactRepository;
import com.company.skillplatform.compatibility.infrastructure.entity.PlatformEntity;
import com.company.skillplatform.skill.domain.SkillStatus;
import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import com.company.skillplatform.skill.infrastructure.repository.SkillRepository;
import com.company.skillplatform.version.domain.LifecycleStatus;
import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;
import java.util.*;
import org.junit.jupiter.api.Test;

class SkillUpdateServiceTest {
    private final SkillRepository skills = mock(SkillRepository.class);
    private final SkillArtifactRepository artifacts = mock(SkillArtifactRepository.class);
    private final SkillUpdateService service = new SkillUpdateService(skills, artifacts);

    @Test
    void returnsOnlyPublishedPlatformSkillsWithCompatibleArtifacts() {
        SkillEntity skill = mock(SkillEntity.class);
        SkillVersionEntity version = mock(SkillVersionEntity.class);
        PlatformEntity platform = mock(PlatformEntity.class);
        when(skill.getSkillKey()).thenReturn("brainstorming");
        when(skill.getDisplayName()).thenReturn("头脑风暴");
        when(skill.getStatus()).thenReturn(SkillStatus.ACTIVE);
        when(skill.getScopeType()).thenReturn("PLATFORM");
        when(skill.getLatestPublishedVersion()).thenReturn(version);
        when(version.getId()).thenReturn(42L);
        when(version.getVersion()).thenReturn("2.0.0");
        when(version.getLifecycleStatus()).thenReturn(LifecycleStatus.PUBLISHED);
        when(platform.getPlatformKey()).thenReturn("CODEBUDDY");
        when(skills.findBySkillKeyIn(List.of("brainstorming"))).thenReturn(List.of(skill));
        when(artifacts.findByVersionIdInAndStatus(List.of(42L), "AVAILABLE")).thenReturn(List.of(new SkillArtifactEntity(version, platform, "MACOS", "ZIP", "object", "skill.zip", 1, "hash", "1", 1L)));

        var result = service.check(new SkillUpdateService.CheckCommand("CODEBUDDY", "MACOS", List.of("brainstorming")));

        assertThat(result.items()).extracting(SkillUpdateService.UpdateView::skillKey).containsExactly("brainstorming");
        assertThat(result.items().get(0).latestVersionId()).isEqualTo(42L);
    }

    @Test
    void rejectsAnOversizedBatch() {
        List<String> keys = java.util.stream.IntStream.range(0, 201).mapToObj(i -> "skill-" + i).toList();
        assertThatThrownBy(() -> service.check(new SkillUpdateService.CheckCommand("CODEBUDDY", "MACOS", keys)))
            .hasMessageContaining("At most 200");
        verifyNoInteractions(skills, artifacts);
    }
}
