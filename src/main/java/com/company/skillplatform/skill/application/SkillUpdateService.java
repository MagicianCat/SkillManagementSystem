package com.company.skillplatform.skill.application;

import com.company.skillplatform.artifact.infrastructure.entity.SkillArtifactEntity;
import com.company.skillplatform.artifact.infrastructure.repository.SkillArtifactRepository;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.skill.domain.SkillStatus;
import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import com.company.skillplatform.skill.infrastructure.repository.SkillRepository;
import com.company.skillplatform.version.domain.LifecycleStatus;
import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Batch lookup used by IDE clients to decide whether a platform-installed skill is stale. */
@Service
public class SkillUpdateService {
    private static final int MAX_KEYS = 200;
    private final SkillRepository skills;
    private final SkillArtifactRepository artifacts;

    public SkillUpdateService(SkillRepository skills, SkillArtifactRepository artifacts) {
        this.skills = skills;
        this.artifacts = artifacts;
    }

    @PreAuthorize("hasAuthority('skill:browse')")
    @Transactional(readOnly = true)
    public CheckView check(CheckCommand command) {
        if (command == null || command.skillKeys() == null || command.skillKeys().isEmpty()) {
            return new CheckView(command == null ? "CODEBUDDY" : command.platform(), command == null ? "ANY" : command.osType(), List.of());
        }
        if (command.skillKeys().size() > MAX_KEYS) {
            throw new BusinessException("SKILL_UPDATE_KEY_LIMIT_EXCEEDED", "At most 200 skill keys are allowed", HttpStatus.BAD_REQUEST);
        }
        String platform = upper(command.platform(), "CODEBUDDY");
        String osType = upper(command.osType(), "ANY");
        List<String> keys = command.skillKeys().stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
        List<SkillEntity> rows = skills.findBySkillKeyIn(keys);
        Map<Long, SkillArtifactEntity> compatibleArtifacts = new HashMap<>();
        List<Long> versionIds = rows.stream().map(SkillEntity::getLatestPublishedVersion).filter(Objects::nonNull).map(SkillVersionEntity::getId).toList();
        for (SkillArtifactEntity artifact : artifacts.findByVersionIdInAndStatus(versionIds, "AVAILABLE")) {
            String artifactPlatform = artifact.getPlatform().getPlatformKey();
            if (!platform.equalsIgnoreCase(artifactPlatform)) continue;
            if (osType.equalsIgnoreCase(artifact.getOsType()) || "ANY".equalsIgnoreCase(artifact.getOsType())) {
                compatibleArtifacts.putIfAbsent(artifact.getVersion().getId(), artifact);
            }
        }
        List<UpdateView> result = rows.stream()
            .filter(skill -> skill.getStatus() == SkillStatus.ACTIVE)
            .filter(skill -> "PLATFORM".equalsIgnoreCase(skill.getScopeType()))
            .map(skill -> {
                SkillVersionEntity version = skill.getLatestPublishedVersion();
                return version == null || version.getLifecycleStatus() != LifecycleStatus.PUBLISHED || !compatibleArtifacts.containsKey(version.getId())
                    ? null : new UpdateView(skill.getSkillKey(), skill.getDisplayName(), version.getId(), version.getVersion());
            }).filter(Objects::nonNull).toList();
        return new CheckView(platform, osType, result);
    }

    private String upper(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    public record CheckCommand(String platform, String osType, List<String> skillKeys) {}
    public record CheckView(String platform, String osType, List<UpdateView> items) {}
    public record UpdateView(String skillKey, String displayName, Long latestVersionId, String latestVersion) {}
}
