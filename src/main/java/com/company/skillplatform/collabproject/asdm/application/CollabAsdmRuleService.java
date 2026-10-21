package com.company.skillplatform.collabproject.asdm.application;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmGitlabClient;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmGitlabException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Versioned default, group delta, and requirement-pinned ASDM path rules. */
@Service
public class CollabAsdmRuleService {
    private static final List<String> STAGES = List.of("REQUIREMENT", "PRODUCT", "ARCHITECTURE_DESIGN", "UI_DESIGN", "FRONTEND_CODING", "BACKEND_CODING", "TESTING");
    private static final int MAX_PATTERNS_PER_FIELD = 100;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CollabAsdmConfigurationService configuration;
    private final CollabAsdmGitlabClient gitlab;

    public CollabAsdmRuleService(JdbcTemplate jdbc, ObjectMapper mapper, CollabAsdmConfigurationService configuration, CollabAsdmGitlabClient gitlab) { this.jdbc = jdbc; this.mapper = mapper; this.configuration=configuration;this.gitlab=gitlab; }

    @Transactional(readOnly = true)
    public GroupRulesView getGroupRules(String groupKey, long actorId) {
        GroupRow group = group(groupKey);
        requireGroupView(group, actorId);
        return groupView(group);
    }

    @Transactional
    public GroupRulesView updateGroupRules(String groupKey, long actorId, int expectedRevision, Map<String, StageOverride> stages) {
        GroupRow group = group(groupKey);
        requireOwner(group, actorId);
        if (stages == null || stages.isEmpty() || stages.size() > STAGES.size() || !STAGES.containsAll(stages.keySet())) throw bad("ASDM_RULE_STAGE_INVALID", "Rules may only target the seven ASDM stages");
        jdbc.queryForObject("select id from collab_group where id=? for update",Long.class,group.id());
        DefaultRules defaults = defaults();
        Map<String, StageOverride> safe = latestOverrides(group.id());
        stages.forEach((key, value) -> safe.put(key, validate(value, key, defaults.rules())));
        Integer current = jdbc.queryForObject("select coalesce(max(revision),0) from collab_asdm_group_rule_revision where group_id=?", Integer.class, group.id());
        int revision = current == null ? 0 : current;
        if (revision != expectedRevision) throw error("ASDM_RULE_REVISION_CONFLICT", "ASDM rules changed; reload and retry", HttpStatus.CONFLICT);
        String json = write(safe);
        String hash = sha256(write(merge(defaults.rules(), safe)));
        jdbc.update("insert into collab_asdm_group_rule_revision(group_id,revision,base_default_version,override_json,effective_hash,updated_by,time_created) values(?,?,?,?,?,?,now(3))",
                group.id(), revision + 1, defaults.version(), json, hash, actorId);
        return groupView(group);
    }

    @Transactional
    public GroupRulesView resetStage(String groupKey, String stageKey, long actorId, int expectedRevision) {
        if (!STAGES.contains(stageKey)) throw bad("ASDM_RULE_STAGE_INVALID", "Unknown ASDM stage");
        GroupRow group = group(groupKey);
        requireOwner(group, actorId);
        jdbc.queryForObject("select id from collab_group where id=? for update",Long.class,group.id());
        Integer current = jdbc.queryForObject("select coalesce(max(revision),0) from collab_asdm_group_rule_revision where group_id=?", Integer.class, group.id());
        int revision = current == null ? 0 : current;
        if (revision != expectedRevision) throw error("ASDM_RULE_REVISION_CONFLICT", "ASDM rules changed; reload and retry", HttpStatus.CONFLICT);
        Map<String, StageOverride> overrides = latestOverrides(group.id());
        overrides.remove(stageKey);
        DefaultRules defaults = defaults();
        jdbc.update("insert into collab_asdm_group_rule_revision(group_id,revision,base_default_version,override_json,effective_hash,updated_by,time_created) values(?,?,?,?,?,?,now(3))",
                group.id(), revision + 1, defaults.version(), write(overrides), sha256(write(merge(defaults.rules(), overrides))), actorId);
        return groupView(group);
    }

    @Transactional(readOnly = true)
    public RulePreview preview(String groupKey, long actorId, String stageKey, String requirementKey, StageOverride unsavedOverride) {
        if (!STAGES.contains(stageKey)) throw bad("ASDM_RULE_STAGE_INVALID", "Unknown ASDM stage");
        GroupRow group = group(groupKey);
        requireGroupView(group, actorId);
        PreviewRequirement requirement = jdbc.query("select id,group_id,asdm_branch,asdm_requirement_path from collab_project where project_key=?", rs -> rs.next() ? new PreviewRequirement(rs.getLong("id"),rs.getLong("group_id"),rs.getString("asdm_branch"),rs.getString("asdm_requirement_path")) : null, requirementKey);
        if(requirement==null)throw error("COLLAB_REQUIREMENT_NOT_FOUND","Requirement not found",HttpStatus.NOT_FOUND);
        if(requirement.groupId()!=group.id())throw error("COLLAB_REQUIREMENT_GROUP_MISMATCH","Requirement does not belong to this project group",HttpStatus.BAD_REQUEST);
        if(requirement.branch()==null||requirement.path()==null)throw error("ASDM_REQUIREMENT_NOT_BOUND","Bind an ASDM branch and requirement directory before preview",HttpStatus.CONFLICT);
        Map<String, StageOverride> overrides = latestOverrides(group.id());
        DefaultRules defaultRules = defaults();
        if (unsavedOverride != null) overrides.put(stageKey, validate(unsavedOverride, stageKey, defaultRules.rules()));
        CollabAsdmStageRules.StageRuleConfig config = merge(defaultRules.rules(), overrides).get(stageKey);
        CollabAsdmStageRules rules = new CollabAsdmStageRules();
        try {
            CollabAsdmConfigurationService.RepositoryAccess access=configuration.requireAccess(group.id());
            CollabAsdmGitlabClient.BranchInfo branch=gitlab.getBranch(access.projectId(),requirement.branch(),access.token(),access.repositoryBase());
            List<String> paths=gitlab.listTreeRecursive(access.projectId(),branch.headSha(),"workspace",access.token(),configuration.maxTreeEntries(),access.repositoryBase()).stream().filter(e->"blob".equals(e.type())).map(CollabAsdmGitlabClient.TreeEntry::path).toList();
            List<String> matched = paths.stream().filter(path -> rules.mayMatchStagePath(stageKey, requirement.path(), path, config)).toList();
            List<String> unmatched = paths.stream().filter(path -> !rules.mayMatchStagePath(stageKey, requirement.path(), path, config)).toList();
            return new RulePreview(stageKey, branch.headSha(), matched, unmatched);
        } catch(CollabAsdmGitlabException ex) { throw error(ex.errorCode(),"Unable to preview ASDM artifact paths from the bound branch",HttpStatus.BAD_GATEWAY); }
    }

    /** Returns the pinned snapshot, or applies the group's current revision for an explicit sync. */
    @Transactional
    public EffectiveRules effectiveForRequirement(long requirementId, boolean applyLatest) {
        RequirementGroup requirement = jdbc.query("select p.group_id from collab_project p where p.id=?", rs -> rs.next() ? new RequirementGroup(rs.getLong(1)) : null, requirementId);
        if (requirement == null) throw error("COLLAB_REQUIREMENT_NOT_FOUND", "Requirement not found", HttpStatus.NOT_FOUND);
        if (!applyLatest) {
            List<EffectiveRules> existing = jdbc.query("select group_revision,default_version,effective_rules_json,effective_hash from collab_asdm_requirement_rule_binding where requirement_id=?", (rs, n) -> new EffectiveRules(rs.getInt(1), rs.getString(2), rs.getString(3), readRules(rs.getString(3)), rs.getString(4)), requirementId);
            if (!existing.isEmpty()) return existing.get(0);
        }
        DefaultRules defaults = defaults();
        Integer revisionValue = jdbc.queryForObject("select coalesce(max(revision),0) from collab_asdm_group_rule_revision where group_id=?", Integer.class, requirement.groupId());
        int revision = revisionValue == null ? 0 : revisionValue;
        Map<String, StageOverride> overrides = latestOverrides(requirement.groupId());
        Map<String, CollabAsdmStageRules.StageRuleConfig> effective = merge(defaults.rules(), overrides);
        String json = write(effective);
        String hash = sha256(json);
        jdbc.update("insert into collab_asdm_requirement_rule_binding(requirement_id,group_revision,default_version,effective_rules_json,effective_hash,synced_at) values(?,?,?,?,?,now(3)) on duplicate key update group_revision=values(group_revision),default_version=values(default_version),effective_rules_json=values(effective_rules_json),effective_hash=values(effective_hash),synced_at=values(synced_at)",
                requirementId, revision, defaults.version(), json, hash);
        return new EffectiveRules(revision, defaults.version(), json, effective, hash);
    }

    @Transactional
    public EffectiveRules bindLatestForRequirement(long requirementId, long actorId) {
        EffectiveRules rules = effectiveForRequirement(requirementId, true);
        jdbc.update("update collab_asdm_requirement_rule_binding set bound_by=? where requirement_id=?", actorId, requirementId);
        return rules;
    }

    @Transactional
    public EffectiveStageRules effectiveForRequirement(long requirementId, String stageKey) {
        if (!STAGES.contains(stageKey)) throw bad("ASDM_RULE_STAGE_INVALID", "Unknown ASDM stage");
        EffectiveRules rules = effectiveForRequirement(requirementId, false);
        return new EffectiveStageRules(rules.groupRevision(), rules.defaultVersion(), rules.effectiveHash(), rules.rules().get(stageKey));
    }

    @Transactional
    public RequirementRulesView getRequirementRules(String requirementKey, long actorId) {
        Long id = jdbc.query("select p.id,p.group_id,g.group_key,g.owner_user_id,g.status from collab_project p join collab_group g on g.id=p.group_id where p.project_key=?", rs -> rs.next() ? rs.getLong("id") : null, requirementKey);
        if (id == null) throw error("COLLAB_REQUIREMENT_NOT_FOUND", "Requirement not found", HttpStatus.NOT_FOUND);
        Long groupId = jdbc.queryForObject("select group_id from collab_project where id=?", Long.class, id);
        GroupRow group = jdbc.query("select g.id,g.group_key,g.owner_user_id,g.status from collab_group g where g.id=?", rs -> rs.next() ? row(rs) : null, groupId);
        requireGroupView(group, actorId);
        EffectiveRules pinned = effectiveForRequirement(id, false);
        return new RequirementRulesView(pinned, latestForGroup(groupId));
    }

    private GroupRulesView groupView(GroupRow group) {
        DefaultRules defaults = defaults();
        int revision = jdbc.queryForObject("select coalesce(max(revision),0) from collab_asdm_group_rule_revision where group_id=?", Integer.class, group.id());
        Map<String, StageOverride> overrides = latestOverrides(group.id());
        Map<String, CollabAsdmStageRules.StageRuleConfig> effective = merge(defaults.rules(), overrides);
        String updatedBy = jdbc.query("select u.display_name from collab_asdm_group_rule_revision r join iam_user u on u.id=r.updated_by where r.group_id=? order by r.revision desc limit 1", rs -> rs.next() ? rs.getString(1) : null, group.id());
        List<StageRulesView> stages = new ArrayList<>();
        for (String key : STAGES) {
            CollabAsdmStageRules.StageRuleConfig inherited = defaults.rules().get(key);
            if (inherited != null) stages.add(new StageRulesView(key, stageTitle(key), effective.get(key).enabled(), inherited,
                    overrides.getOrDefault(key, new StageOverride(null, List.of(), List.of(), List.of(), List.of(), List.of(), null))));
        }
        return new GroupRulesView(defaults.version(), revision, updatedBy, List.copyOf(stages));
    }

    private EffectiveRules latestForGroup(long groupId) {
        DefaultRules defaults = defaults();
        Integer revisionValue = jdbc.queryForObject("select coalesce(max(revision),0) from collab_asdm_group_rule_revision where group_id=?", Integer.class, groupId);
        int revision = revisionValue == null ? 0 : revisionValue;
        Map<String, CollabAsdmStageRules.StageRuleConfig> rules = merge(defaults.rules(), latestOverrides(groupId));
        String json = write(rules);
        return new EffectiveRules(revision, defaults.version(), json, rules, sha256(json));
    }

    private Map<String, CollabAsdmStageRules.StageRuleConfig> merge(Map<String, CollabAsdmStageRules.StageRuleConfig> base, Map<String, StageOverride> overrides) {
        Map<String, CollabAsdmStageRules.StageRuleConfig> result = new LinkedHashMap<>();
        for (String key : STAGES) {
            CollabAsdmStageRules.StageRuleConfig inherited = base.get(key);
            if (inherited == null) continue;
            StageOverride override = overrides.get(key);
            List<String> disabled = override == null ? List.of() : override.disabledIncludeGlobs();
            List<String> includes = new ArrayList<>(inherited.includeGlobs().stream().filter(p -> !disabled.contains(p)).toList());
            List<String> excludes = new ArrayList<>(inherited.excludeGlobs());
            List<String> markers = new ArrayList<>(inherited.markerGlobs());
            List<List<String>> markerGroups = override != null && !override.markerGroups().isEmpty()
                    ? new ArrayList<>(override.markerGroups()) : new ArrayList<>(inherited.markerGroups());
            if (override != null) {
                includes.addAll(override.addIncludeGlobs());
                excludes.addAll(override.excludeGlobs());
                markers.addAll(override.addMarkerGlobs());
                if (!override.addMarkerGlobs().isEmpty()) {
                    if (markerGroups.isEmpty()) markerGroups.add(new ArrayList<>());
                    List<String> alternatives = new ArrayList<>(markerGroups.get(0));
                    alternatives.addAll(override.addMarkerGlobs());
                    markerGroups.set(0, List.copyOf(alternatives));
                }
            }
            result.put(key, new CollabAsdmStageRules.StageRuleConfig(key, override == null || override.enabled() == null ? inherited.enabled() : override.enabled(), includes, excludes, markers, markerGroups, override == null ? null : override.relativeBase()));
        }
        return result;
    }

    private Map<String, StageOverride> latestOverrides(long groupId) {
        List<String> values = jdbc.query("select override_json from collab_asdm_group_rule_revision where group_id=? order by revision desc limit 1", (rs,n)->rs.getString(1), groupId);
        if (values.isEmpty()) return new LinkedHashMap<>();
        try { return mapper.readValue(values.get(0), new TypeReference<>() {}); }
        catch (Exception ex) { throw new IllegalStateException("Stored ASDM rule override is invalid", ex); }
    }

    private DefaultRules defaults() {
        return jdbc.query("select version,rules_json from collab_asdm_default_rule_version where status='ACTIVE' order by id desc limit 1", rs -> {
            if (!rs.next()) throw new IllegalStateException("No active ASDM default rule version");
            try {
                Map<String, Map<String,Object>> json = mapper.readValue(rs.getString("rules_json"), new TypeReference<>() {});
                Map<String, CollabAsdmStageRules.StageRuleConfig> rules = new LinkedHashMap<>();
                json.forEach((key, value) -> rules.put(key, new CollabAsdmStageRules.StageRuleConfig(key, Boolean.TRUE.equals(value.get("enabled")), strings(value.get("includeGlobs")), strings(value.get("excludeGlobs")), strings(value.get("markerGlobs")), nestedStrings(value.get("markerGroups")), (String)value.get("relativeBase"))));
                return new DefaultRules(rs.getString("version"), rules);
            } catch (Exception ex) { throw new IllegalStateException("Stored ASDM default rules are invalid", ex); }
        });
    }

    private StageOverride validate(StageOverride value, String stageKey, Map<String, CollabAsdmStageRules.StageRuleConfig> inheritedRules) {
        if (value == null) throw bad("ASDM_RULE_INVALID", "Each stage override must be provided");
        boolean hasBase = value.relativeBase() != null && !value.relativeBase().isBlank();
        List<String> includes = validPatterns(value.addIncludeGlobs(),hasBase);
        List<String> excludes = validPatterns(value.excludeGlobs(),hasBase);
        List<String> disabled = validPatterns(value.disabledIncludeGlobs());
        Set<String> inherited = Set.copyOf(inheritedRules.get(stageKey).includeGlobs());
        if (!inherited.containsAll(disabled)) throw bad("ASDM_RULE_DEFAULT_PATTERN_UNKNOWN", "Only inherited patterns can be disabled");
        List<String> markers = validPatterns(value.addMarkerGlobs(),hasBase);
        List<List<String>> markerGroups = value.markerGroups() == null ? List.of() : value.markerGroups().stream().map(group->validPatterns(group,hasBase)).toList();
        if (markerGroups.size() > 20) throw bad("ASDM_RULE_LIMIT_EXCEEDED", "Too many marker groups");
        if (markerGroups.stream().anyMatch(List::isEmpty)) throw bad("ASDM_RULE_INVALID", "Marker groups must contain at least one path pattern");
        if (value.relativeBase() != null && !validPattern(value.relativeBase())) throw bad("ASDM_RULE_PATH_INVALID", "relativeBase must be a safe repository-relative path");
        return new StageOverride(value.enabled(), includes, excludes, disabled, markers, markerGroups, value.relativeBase());
    }
    private List<String> validPatterns(List<String> values) { return validPatterns(values,false); }
    private List<String> validPatterns(List<String> values, boolean allowRelative) {
        if (values == null) return List.of();
        if (values.size() > MAX_PATTERNS_PER_FIELD) throw bad("ASDM_RULE_LIMIT_EXCEEDED", "Too many patterns in one rule field");
        return values.stream().map(v -> { if (!validPattern(v,allowRelative)) throw bad("ASDM_RULE_PATH_INVALID", "Patterns must be safe repository-relative globs"); return v.trim(); }).distinct().toList();
    }
    private boolean validPattern(String value) { return validPattern(value,false); }
    private boolean validPattern(String value, boolean allowRelative) {
        if (value == null || value.isBlank() || value.length() > 300 || value.contains("..") || value.contains("\\") || value.startsWith("/") || value.contains("://") || value.chars().anyMatch(Character::isISOControl)) return false;
        if (Set.of("workspace/**", "workspace/**/*", "workspace/*").contains(value)) return false;
        return allowRelative || value.startsWith("workspace/") || value.startsWith("${REQ_PATH}/") || value.startsWith("${REQ_DIR}/") || value.startsWith("${REQ_ID}/") || value.startsWith("${REQ_NAME}/");
    }

    private GroupRow group(String key) { return jdbc.query("select id,group_key,owner_user_id,status from collab_group where group_key=?", rs -> rs.next() ? row(rs) : null, key); }
    private GroupRow row(ResultSet rs) throws SQLException { return new GroupRow(rs.getLong("id"), rs.getString("group_key"), rs.getLong("owner_user_id"), rs.getString("status")); }
    private void requireOwner(GroupRow group, long actorId) { if (group == null) throw error("COLLAB_GROUP_NOT_FOUND", "Project group not found", HttpStatus.NOT_FOUND); if (group.ownerId()!=actorId) throw error("COLLAB_GROUP_OWNER_REQUIRED", "Project group owner is required", HttpStatus.FORBIDDEN); }
    private void requireGroupView(GroupRow group, long actorId) { if (group == null) throw error("COLLAB_GROUP_NOT_FOUND", "Project group not found", HttpStatus.NOT_FOUND); if (group.ownerId()==actorId) return; Integer n=jdbc.queryForObject("select count(*) from collab_group_member where group_id=? and user_id=? and status='ACTIVE'", Integer.class,group.id(),actorId); if(n==null||n==0||!"PUBLISHED".equals(group.status())) throw error("COLLAB_GROUP_NOT_FOUND", "Project group not found", HttpStatus.NOT_FOUND); }
    private List<String> strings(Object value) { return value instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of(); }
    private String stageTitle(String key) { return switch (key) { case "REQUIREMENT" -> "需求分析"; case "PRODUCT" -> "产品设计"; case "ARCHITECTURE_DESIGN" -> "架构设计"; case "UI_DESIGN" -> "UI 设计"; case "FRONTEND_CODING" -> "前端开发"; case "BACKEND_CODING" -> "后端开发"; case "TESTING" -> "测试"; default -> key; }; }
    private String write(Object value) { try { return mapper.writeValueAsString(value); } catch (JsonProcessingException ex) { throw new IllegalStateException(ex); } }
    private Map<String, CollabAsdmStageRules.StageRuleConfig> readRules(String value) {
        try {
            Map<String, CollabAsdmStageRules.StageRuleConfig> stored = mapper.readValue(value, new TypeReference<>() {});
            Map<String, CollabAsdmStageRules.StageRuleConfig> normalized = new LinkedHashMap<>();
            stored.forEach((key, rule) -> normalized.put(key, new CollabAsdmStageRules.StageRuleConfig(key, rule.enabled(), rule.includeGlobs(), rule.excludeGlobs(), rule.markerGlobs(), rule.markerGroups(), rule.relativeBase())));
            return normalized;
        } catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception ex) { throw new IllegalStateException(ex); } }
    private BusinessException bad(String code,String message) { return error(code,message,HttpStatus.BAD_REQUEST); }
    private BusinessException error(String code,String message,HttpStatus status) { return new BusinessException(code,message,status); }

    public record StageOverride(Boolean enabled, List<String> addIncludeGlobs, List<String> excludeGlobs, List<String> disabledIncludeGlobs, List<String> addMarkerGlobs, List<List<String>> markerGroups, String relativeBase) {
        public StageOverride { addIncludeGlobs=immutable(addIncludeGlobs);excludeGlobs=immutable(excludeGlobs);disabledIncludeGlobs=immutable(disabledIncludeGlobs);addMarkerGlobs=immutable(addMarkerGlobs);markerGroups=markerGroups==null?List.of():markerGroups.stream().map(List::copyOf).toList(); }
    }
    public record StageRulesView(String stageKey, String title, boolean enabled, CollabAsdmStageRules.StageRuleConfig defaultRules, StageOverride overrides) {}
    public record GroupRulesView(String defaultVersion, int revision, String updatedBy, List<StageRulesView> stages) {}
    public record EffectiveRules(int groupRevision, String defaultVersion, String effectiveRulesJson, Map<String, CollabAsdmStageRules.StageRuleConfig> rules, String effectiveHash) {}
    public record EffectiveStageRules(int groupRevision, String defaultVersion, String effectiveHash, CollabAsdmStageRules.StageRuleConfig rules) {}
    public record RequirementRulesView(EffectiveRules inUse, EffectiveRules latestGroupRules) {}
    public record RulePreview(String stageKey, String headSha, List<String> matchedPaths, List<String> unmatchedPaths) {}
    private record DefaultRules(String version, Map<String, CollabAsdmStageRules.StageRuleConfig> rules) {}
    private record GroupRow(long id, String key, long ownerId, String status) {}
    private record RequirementGroup(long groupId) {}
    private record PreviewRequirement(long id,long groupId,String branch,String path) {}
    private static List<String> immutable(List<String> values) { return values == null ? List.of() : List.copyOf(values); }
    private List<List<String>> nestedStrings(Object value) { return value instanceof List<?> groups ? groups.stream().map(this::strings).toList() : List.of(); }
}
