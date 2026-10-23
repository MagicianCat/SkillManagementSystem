package com.company.skillplatform.bundle.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.company.skillplatform.artifact.infrastructure.entity.SkillArtifactEntity;
import com.company.skillplatform.artifact.infrastructure.repository.SkillArtifactRepository;
import com.company.skillplatform.bundle.infrastructure.entity.SkillBundleEntity;
import com.company.skillplatform.bundle.infrastructure.repository.SkillBundleFailureRepository;
import com.company.skillplatform.bundle.infrastructure.repository.SkillBundleItemRepository;
import com.company.skillplatform.bundle.infrastructure.repository.SkillBundleRepository;
import com.company.skillplatform.compatibility.infrastructure.entity.PlatformEntity;
import com.company.skillplatform.compatibility.infrastructure.repository.PlatformRepository;
import com.company.skillplatform.dependency.application.DependencyService;
import com.company.skillplatform.storage.domain.ObjectStoragePort;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;
import com.company.skillplatform.version.infrastructure.repository.SkillVersionRepository;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

class AgentBundleRetryTest {
    @Test
    void unavailableCachedBundleIsRebuiltAfterPermissionsAreFixed() throws Exception {
        var bundles = mock(SkillBundleRepository.class);
        var items = mock(SkillBundleItemRepository.class);
        var failures = mock(SkillBundleFailureRepository.class);
        var platforms = mock(PlatformRepository.class);
        var users = mock(IamUserRepository.class);
        var versions = mock(SkillVersionRepository.class);
        var dependencies = mock(DependencyService.class);
        var artifacts = mock(SkillArtifactRepository.class);
        var storage = mock(ObjectStoragePort.class);
        var platform = mock(PlatformEntity.class);
        var user = mock(IamUserEntity.class);
        var version = mock(SkillVersionEntity.class);
        var skill = mock(com.company.skillplatform.skill.infrastructure.entity.SkillEntity.class);
        var artifact = mock(SkillArtifactEntity.class);
        when(version.getId()).thenReturn(281L);
        when(version.getSkill()).thenReturn(skill);
        when(skill.getSkillKey()).thenReturn("backend-code-dev");
        when(platform.getPlatformKey()).thenReturn("CODEBUDDY");
        when(platform.getStatus()).thenReturn("ACTIVE");
        when(platforms.findByPlatformKey("CODEBUDDY")).thenReturn(Optional.of(platform));
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(dependencies.resolve(281L)).thenReturn(new DependencyService.ResolveView(List.of(), List.of()));
        when(artifacts.findFirstByVersionIdAndPlatformPlatformKeyAndOsTypeAndStatus(eq(281L), eq("CODEBUDDY"), any(), eq("AVAILABLE")))
                .thenAnswer(invocation -> "ANY".equals(invocation.getArgument(2)) ? Optional.of(artifact) : Optional.empty());
        when(artifact.getFileName()).thenReturn("backend.zip");
        when(artifact.getObjectKey()).thenReturn("backend.zip");
        when(artifact.getSha256()).thenReturn("0".repeat(64));
        when(storage.get("backend.zip")).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        var cached = new SkillBundleEntity("cached", platform, "WINDOWS", true, "lock", user);
        ReflectionTestUtils.setField(cached, "id", 32L);
        cached.unavailable("NO_COMPATIBLE_ITEMS", "previous permission error");
        when(bundles.findByBundleKey(any())).thenReturn(Optional.of(cached));
        when(bundles.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new BundleService(bundles, items, failures, platforms, users, versions, dependencies, artifacts, storage);

        var result = service.createAgentBundle(List.of(version), "CODEBUDDY", "WINDOWS", 7L);

        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(cached.getRetryCount()).isEqualTo(1);
        verify(failures).flush();
        verify(dependencies).resolve(281L);
        verify(storage).get("backend.zip");
    }

    @Test
    void permissionFailureIsNotReportedAsArtifactFailure() {
        var bundles = mock(SkillBundleRepository.class);
        var platforms = mock(PlatformRepository.class);
        var platform = mock(PlatformEntity.class);
        var dependencies = mock(DependencyService.class);
        var users = mock(IamUserRepository.class);
        var user = mock(IamUserEntity.class);
        var version = mock(SkillVersionEntity.class);
        var skill = mock(com.company.skillplatform.skill.infrastructure.entity.SkillEntity.class);
        when(version.getId()).thenReturn(281L);
        when(version.getSkill()).thenReturn(skill);
        when(skill.getSkillKey()).thenReturn("backend-code-dev");
        when(platform.getStatus()).thenReturn("ACTIVE");
        when(platforms.findByPlatformKey("CODEBUDDY")).thenReturn(Optional.of(platform));
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(bundles.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencies.resolve(281L)).thenThrow(new AccessDeniedException("Access Denied"));
        var service = new BundleService(bundles, mock(SkillBundleItemRepository.class),
                mock(SkillBundleFailureRepository.class), platforms, users,
                mock(SkillVersionRepository.class), dependencies,
                mock(SkillArtifactRepository.class), mock(ObjectStoragePort.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.createAgentBundle(List.of(version), "CODEBUDDY", "WINDOWS", 7L))
                .isInstanceOf(AccessDeniedException.class);
    }
}
