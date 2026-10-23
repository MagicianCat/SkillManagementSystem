package com.company.skillplatform.dependency.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.skillplatform.dependency.infrastructure.repository.SkillVersionDependencyRepository;
import com.company.skillplatform.skill.application.SkillService;
import com.company.skillplatform.skill.infrastructure.repository.SkillRepository;
import com.company.skillplatform.version.domain.SemanticVersionPolicy;
import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;
import com.company.skillplatform.version.infrastructure.repository.SkillVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

class DependencyResolvePermissionTest {
    @Test
    void downloaderWithoutEditPermissionCanResolveDependencies() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var authentication = new TestingAuthenticationToken("downloader", "", "skill:download");
            authentication.setAuthenticated(true);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            try {
                assertThat(context.getBean(DependencyService.class).resolve(42L).items()).isEmpty();
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean DependencyService dependencyService() {
            var versions = mock(SkillVersionRepository.class);
            var skillService = mock(SkillService.class);
            var version = mock(SkillVersionEntity.class);
            var skill = mock(com.company.skillplatform.skill.infrastructure.entity.SkillEntity.class);
            when(skill.getId()).thenReturn(7L);
            when(skill.getSkillKey()).thenReturn("backend-code-dev");
            when(version.getSkill()).thenReturn(skill);
            when(skillService.version(42L)).thenReturn(version);
            return new DependencyService(mock(SkillVersionDependencyRepository.class), versions,
                    mock(SkillRepository.class), skillService, new SemanticVersionPolicy());
        }
    }
}
