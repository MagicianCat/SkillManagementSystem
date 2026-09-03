package com.company.skillplatform.workflow.infrastructure;

import com.company.skillplatform.adapter.application.AdapterRegistry;
import com.company.skillplatform.adapter.domain.SkillAdapter;
import com.company.skillplatform.artifact.infrastructure.entity.SkillArtifactEntity;
import com.company.skillplatform.artifact.infrastructure.repository.SkillArtifactRepository;
import com.company.skillplatform.compatibility.domain.CompatibilityStatus;
import com.company.skillplatform.compatibility.domain.OsType;
import com.company.skillplatform.compatibility.infrastructure.repository.PlatformRepository;
import com.company.skillplatform.compatibility.infrastructure.repository.SkillVersionCompatibilityRepository;
import com.company.skillplatform.storage.domain.ObjectStoragePort;
import com.company.skillplatform.version.application.SkillFileService;
import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;
import com.company.skillplatform.workflow.domain.PublicationPort;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class LifecycleOnlyPublicationAdapter implements PublicationPort {
    private final AdapterRegistry registry;
    private final SkillFileService files;
    private final ObjectStoragePort storage;
    private final PlatformRepository platforms;
    private final SkillArtifactRepository artifacts;
    private final SkillVersionCompatibilityRepository compatibilities;

    public LifecycleOnlyPublicationAdapter(AdapterRegistry registry, SkillFileService files,
            ObjectStoragePort storage, PlatformRepository platforms, SkillArtifactRepository artifacts,
            SkillVersionCompatibilityRepository compatibilities) {
        this.registry = registry; this.files = files; this.storage = storage; this.platforms = platforms;
        this.artifacts = artifacts; this.compatibilities = compatibilities;
    }

    @Override public void build(Long versionId) {
        if (registry.descriptors().isEmpty()) throw new IllegalStateException("No skill adapters registered");
        Map<String, byte[]> source = files.snapshot(versionId);
        SkillVersionEntity version = files.version(versionId);
        for (SkillAdapter adapter : registryAdapters()) {
            var platform = platforms.findByPlatformKey(adapter.platformKey().toUpperCase()).orElseThrow();
            Set<OsType> targets = new LinkedHashSet<>();
            compatibilities.findByVersionId(versionId).stream()
                    .filter(item -> item.getPlatform().getPlatformKey().equalsIgnoreCase(adapter.platformKey()))
                    .filter(item -> item.getDeclaredStatus() != CompatibilityStatus.UNSUPPORTED)
                    .map(item -> item.getOsType()).forEach(targets::add);
            if (targets.isEmpty()) targets.add(OsType.ANY);
            for (OsType os : targets) {
                if (artifacts.findFirstByVersionIdAndPlatformPlatformKeyAndOsTypeAndStatus(
                        versionId, adapter.platformKey().toUpperCase(), os.name(), "AVAILABLE").isPresent()) continue;
                var result = adapter.build(new SkillAdapter.AdapterRequest(version.getSkill().getSkillKey(),
                        version.getCandidateVersion(), source,
                        "overlays/" + adapter.platformKey().toLowerCase() + ".yaml"));
                String base = version.getCandidateVersion() + "/" + adapter.platformKey().toLowerCase()
                        + "/" + os.name().toLowerCase();
                String objectKey = "skills/" + version.getSkill().getSkillKey() + "/artifacts/" + base + ".zip";
                String fileName = "skill-" + version.getCandidateVersion() + "-"
                        + adapter.platformKey().toLowerCase() + "-" + os.name().toLowerCase() + ".zip";
                storage.put(objectKey, new ByteArrayInputStream(result.artifact()), result.artifact().length,
                        result.contentType());
                artifacts.save(new SkillArtifactEntity(version, platform, os.name(), "PLATFORM_SKILL", objectKey,
                        fileName, result.artifact().length, result.sha256(), adapter.adapterVersion(), null));
            }
        }
    }

    private List<SkillAdapter> registryAdapters() {
        return registry.descriptors().stream().map(item -> registry.forPlatform(item.platformKey())).toList();
    }
}
