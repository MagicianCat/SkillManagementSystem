package com.company.skillplatform.dependency.application;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.common.logging.LogContext;
import com.company.skillplatform.dependency.domain.DependencyType;
import com.company.skillplatform.dependency.infrastructure.entity.SkillVersionDependencyEntity;
import com.company.skillplatform.dependency.infrastructure.repository.SkillVersionDependencyRepository;
import com.company.skillplatform.skill.application.SkillService;
import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import com.company.skillplatform.skill.infrastructure.repository.SkillRepository;
import com.company.skillplatform.version.domain.LifecycleStatus;
import com.company.skillplatform.version.domain.SemanticVersionPolicy;
import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;
import com.company.skillplatform.version.infrastructure.repository.SkillVersionRepository;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DependencyService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DependencyService.class);
    private static final List<LifecycleStatus> RESOLVABLE = List.of(LifecycleStatus.PUBLISHED, LifecycleStatus.DEPRECATED);
    private final SkillVersionDependencyRepository dependencies;
    private final SkillVersionRepository versions;
    private final SkillRepository skills;
    private final SkillService skillService;
    private final SemanticVersionPolicy semver;

    public DependencyService(SkillVersionDependencyRepository dependencies, SkillVersionRepository versions, SkillRepository skills, SkillService skillService, SemanticVersionPolicy semver) {
        this.dependencies = dependencies; this.versions = versions; this.skills = skills; this.skillService = skillService; this.semver = semver;
    }
    @PreAuthorize("hasAuthority('skill:browse')") @Transactional(readOnly = true)
    public List<DependencyView> list(Long versionId) { skillService.version(versionId); return dependencies.findByVersionIdOrderBySortOrder(versionId).stream().map(this::view).toList(); }
    @PreAuthorize("hasAuthority('skill:edit')") @Transactional
    public List<DependencyView> replace(Long versionId, int versionNo, List<DependencyCommand> commands, Long actorId) {
        SkillVersionEntity version = skillService.version(versionId); skillService.assertOwner(version, actorId);
        if (version.getLifecycleStatus() != LifecycleStatus.DRAFT) throw conflict("VERSION_NOT_EDITABLE");
        List<SkillVersionDependencyEntity> next = new ArrayList<>(); int order = 0;
        for (DependencyCommand command : commands) {
            SkillEntity target = skills.findBySkillKey(command.skillKey()).orElseThrow(() -> notFound("DEPENDENCY_SKILL_NOT_FOUND"));
            if (target.getId().equals(version.getSkill().getId())) throw conflict("SELF_DEPENDENCY");
            validateConstraint(command.versionConstraint());
            if (reaches(target.getId(), version.getSkill().getId(), new HashSet<>())) {
                log.warn("event=dependency.cycle.detected requestId={} actorId={} versionId={} skillKey={} dependencySkillKey={} errorCode={}",
                        LogContext.requestId(), actorId, versionId, version.getSkill().getSkillKey(), command.skillKey(), "DEPENDENCY_CYCLE");
                throw conflict("DEPENDENCY_CYCLE");
            }
            next.add(new SkillVersionDependencyEntity(version, target, command.versionConstraint(), command.dependencyType() == null ? DependencyType.RUNTIME : command.dependencyType(), command.required(), order++));
        }
        if (versions.incrementVersion(versionId, versionNo) != 1) throw conflict("OPTIMISTIC_LOCK_CONFLICT");
        dependencies.deleteByVersionId(versionId); dependencies.saveAll(next);
        log.info("event=dependency.replaced requestId={} actorId={} versionId={} skillKey={} count={}",
                LogContext.requestId(), actorId, versionId, version.getSkill().getSkillKey(), next.size());
        return next.stream().map(this::view).toList();
    }
    @PreAuthorize("hasAuthority('skill:edit')") @Transactional(readOnly = true)
    public ResolveView resolve(Long versionId) {
        SkillVersionEntity root = skillService.version(versionId); LinkedHashMap<String, ResolvedItem> resolved = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> constraints = new LinkedHashMap<>(); LinkedHashSet<String> conflicts = new LinkedHashSet<>();
        try { resolve(root, 0, root.getSkill().getSkillKey(), resolved, constraints, conflicts, new LinkedHashSet<>()); }
        catch (DependencyCycleException cycle) {
            conflicts.add("DEPENDENCY_CYCLE: " + cycle.path);
            log.warn("event=dependency.resolve.cycle requestId={} versionId={} skillKey={} errorCode={}",
                    LogContext.requestId(), versionId, root.getSkill().getSkillKey(), "DEPENDENCY_CYCLE");
        }
        ResolveView result = new ResolveView(List.copyOf(resolved.values()), List.copyOf(conflicts));
        if (result.conflicts().isEmpty())
            log.info("event=dependency.resolve.succeeded requestId={} versionId={} skillKey={} resolvedCount={}",
                    LogContext.requestId(), versionId, root.getSkill().getSkillKey(), result.items().size());
        else
            log.warn("event=dependency.resolve.conflict requestId={} versionId={} skillKey={} resolvedCount={} conflictCount={}",
                    LogContext.requestId(), versionId, root.getSkill().getSkillKey(), result.items().size(), result.conflicts().size());
        return result;
    }
    private void resolve(SkillVersionEntity version, int depth, String path, Map<String, ResolvedItem> resolved, Map<String, List<String>> constraints, Set<String> conflicts, Set<Long> visiting) {
        if (!visiting.add(version.getSkill().getId())) throw new DependencyCycleException(path);
        for (SkillVersionDependencyEntity dependency : dependencies.findByVersionIdOrderBySortOrder(version.getId())) {
            String key = dependency.getDependencySkill().getSkillKey(); List<String> all = constraints.computeIfAbsent(key, ignored -> new ArrayList<>()); all.add(dependency.getVersionConstraint());
            Optional<SkillVersionEntity> selected = versions.findFirstBySkillIdAndLifecycleStatusInOrderByPublishedAtDesc(dependency.getDependencySkill().getId(), RESOLVABLE).filter(candidate -> all.stream().allMatch(c -> matches(candidate.getVersion(), c)));
            if (selected.isEmpty()) { conflicts.add("VERSION_CONFLICT: " + key + " constraints=" + all); continue; }
            resolved.putIfAbsent(key, new ResolvedItem(key, selected.get().getVersion(), depth + 1, path + " -> " + key));
            resolve(selected.get(), depth + 1, path + " -> " + key, resolved, constraints, conflicts, visiting);
        }
        visiting.remove(version.getSkill().getId());
    }
    @PreAuthorize("hasAuthority('skill:browse')") @Transactional(readOnly = true)
    public List<DependentView> dependents(Long versionId) { SkillVersionEntity version = skillService.version(versionId); return dependencies.findByDependencySkillId(version.getSkill().getId()).stream().map(d -> new DependentView(d.getVersion().getSkill().getSkillKey(), d.getVersion().getId(), d.getVersionConstraint())).toList(); }
    private boolean reaches(Long fromSkill, Long target, Set<Long> seen) { if (fromSkill.equals(target)) return true; if (!seen.add(fromSkill)) return false; Optional<SkillVersionEntity> published = versions.findFirstBySkillIdAndLifecycleStatusInOrderByPublishedAtDesc(fromSkill, RESOLVABLE); return published.isPresent() && dependencies.findByVersionIdOrderBySortOrder(published.get().getId()).stream().anyMatch(d -> reaches(d.getDependencySkill().getId(), target, seen)); }
    private boolean matches(String version, String constraint) {
        if (constraint == null || constraint.isBlank() || constraint.equals("*")) return true;
        for (String part : constraint.trim().split("\\s+")) {
            if (part.equals("*")) continue;
            if (part.startsWith(">=")) { if (semver.compare(version, part.substring(2)) < 0) return false; }
            else if (part.startsWith(">")) { if (semver.compare(version, part.substring(1)) <= 0) return false; }
            else if (part.startsWith("<=")) { if (semver.compare(version, part.substring(2)) > 0) return false; }
            else if (part.startsWith("<")) { if (semver.compare(version, part.substring(1)) >= 0) return false; }
            else if (part.matches("\\d+\\.\\d+\\.\\d+")) { if (semver.compare(version, part) != 0) return false; }
            else throw new IllegalArgumentException("Invalid SemVer constraint");
        } return true;
    }
    private void validateConstraint(String value) { try { matches("1.0.0", value); } catch (RuntimeException ex) { throw new BusinessException("INVALID_VERSION_CONSTRAINT", "Invalid SemVer constraint", HttpStatus.BAD_REQUEST); } }
    private DependencyView view(SkillVersionDependencyEntity d) { return new DependencyView(d.getDependencySkill().getSkillKey(), d.getVersionConstraint(), d.getDependencyType(), d.isRequired(), d.getSortOrder()); }
    private BusinessException conflict(String code) { return new BusinessException(code, code, HttpStatus.CONFLICT); }
    private BusinessException notFound(String code) { return new BusinessException(code, code, HttpStatus.NOT_FOUND); }
    private static final class DependencyCycleException extends RuntimeException { private final String path; private DependencyCycleException(String path) { this.path = path; } }
    public record DependencyCommand(String skillKey, String versionConstraint, DependencyType dependencyType, boolean required) {}
    public record DependencyView(String skillKey, String versionConstraint, DependencyType dependencyType, boolean required, int sortOrder) {}
    public record ResolvedItem(String skillKey, String version, int depth, String requiredByPath) {}
    public record ResolveView(List<ResolvedItem> items, List<String> conflicts) {}
    public record DependentView(String skillKey, Long versionId, String versionConstraint) {}
}
