package com.company.skillplatform.collabproject.application;

import com.company.skillplatform.common.application.BusinessException;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Requirement-scoped role snapshot and pre-start actor management APIs. */
@Service
public class CollabRequirementService {
    private static final Set<String> DUTIES = Set.of("OWNER", "REVIEWER");
    private static final Set<String> NOT_STARTED = Set.of("WAITING", "READY", "NOT_STARTED", "READY_BLOCKED");
    private final JdbcTemplate jdbc;
    private final CollabProjectAuthorizationService authorization;
    private final CollabFeishuOutbox feishuOutbox;

    public CollabRequirementService(JdbcTemplate jdbc, CollabProjectAuthorizationService authorization, CollabFeishuOutbox feishuOutbox) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.feishuOutbox = feishuOutbox;
    }

    @Transactional(readOnly = true)
    public RequirementView detail(String requirementKey, Long actorId) {
        var req = authorization.requireMemberOrThrow(requirementKey, actorId);
        return jdbc.query("select p.project_key,p.name,p.description,p.approval_level,p.status,p.current_stage_order,p.owner_user_id, " +
                        "p.inherited_template_version,p.gitlab_root_snapshot,g.group_key,g.name group_name " +
                        "from collab_project p left join collab_group g on g.id=p.group_id where p.id=?",
                rs -> rs.next() ? new RequirementView(rs.getString("project_key"), rs.getString("group_key"),
                        rs.getString("group_name"), rs.getString("name"), rs.getString("description"),
                        rs.getString("approval_level"), rs.getString("status"), rs.getInt("current_stage_order"),
                        (Integer) rs.getObject("inherited_template_version"), rs.getString("gitlab_root_snapshot"),
                        rs.getLong("owner_user_id")) : null, req.projectId());
    }

    @Transactional(readOnly = true)
    public RolesView roles(String requirementKey, Long actorId) {
        var req = authorization.requireMemberOrThrow(requirementKey, actorId);
        List<RoleView> roles = jdbc.query("select source_group_role_key,role_name_snapshot from collab_requirement_role where requirement_id=? order by role_name_snapshot",
                (rs, n) -> new RoleView(rs.getString(1), rs.getString(2)), req.projectId());
        List<StageActorsView> stages = jdbc.query("select s.id,s.stage_key,s.status,s.version_no,s.actors_frozen_at, " +
                        "(select rr.source_group_role_key from collab_requirement_stage_role sr join collab_requirement_role rr on rr.id=sr.requirement_role_id where sr.stage_id=s.id and sr.duty='OWNER') owner_role_key, " +
                        "(select rr.role_name_snapshot from collab_requirement_stage_role sr join collab_requirement_role rr on rr.id=sr.requirement_role_id where sr.stage_id=s.id and sr.duty='OWNER') owner_role_name " +
                        "from collab_stage s where s.project_id=? order by s.stage_order",
                (rs, n) -> stageActorsFromRow(rs, req.groupId() != null), req.projectId());
        return new RolesView(requirementKey, roles, stages);
    }

    @Transactional
    public StageActorsView replaceActors(String requirementKey, String stageKey, String dutyValue, String roleKey,
            List<Long> requestedUserIds, Integer expectedStageVersion, Long actorId) {
        var req = authorization.requireRequirementManager(requirementKey, actorId);
        authorization.requireWritable(req);
        if (req.groupId() == null) throw error("REQUIREMENT_ROLE_IMMUTABLE", "Legacy requirement roles are not editable", HttpStatus.CONFLICT);
        String duty = normalizeDuty(dutyValue);
        jdbc.queryForObject("select id from collab_project where id=? for update", Long.class, req.projectId());
        Map<String, Object> stage = lockStage(req.projectId(), stageKey);
        ensureEditable(stage, expectedStageVersion);
        validateStageRole(((Number) stage.get("id")).longValue(), duty, roleKey);
        List<Long> userIds = canonical(requestedUserIds);
        validateMembers(req.groupId(), userIds, duty, req.approvalLevel(), stageKey);
        if ("REVIEWER".equals(duty)) replaceReviewerRoleActors(((Number) stage.get("id")).longValue(), roleKey, userIds, "MANUAL", stage);
        else replaceStageDuty(((Number) stage.get("id")).longValue(), duty, userIds, "MANUAL", stage);
        maybeResumeBlockedStage(req, ((Number) stage.get("id")).longValue(), actorId);
        return stageActors(req.projectId(), ((Number) stage.get("id")).longValue(), stageKey);
    }

    @Transactional(readOnly = true)
    public SyncPreview syncPreview(String requirementKey, String stageKey, Long actorId) {
        var req = authorization.requireRequirementManager(requirementKey, actorId);
        authorization.requireWritable(req);
        if (req.groupId() == null) throw error("REQUIREMENT_ROLE_IMMUTABLE", "Legacy requirement roles cannot sync", HttpStatus.CONFLICT);
        Map<String, Object> stage = stage(req.projectId(), stageKey);
        List<DutyDiff> diffs = buildDiffs(req.groupId(), ((Number) stage.get("id")).longValue(), stageKey);
        return new SyncPreview(requirementKey, stageKey, ((Number) stage.get("version_no")).intValue(),
                jdbc.queryForObject("select template_version from collab_group where id=?", Integer.class, req.groupId()),
                String.valueOf(stage.get("status")), stage.get("actors_frozen_at") != null, diffs);
    }

    @Transactional
    public SyncResult syncMembers(String requirementKey, String stageKey, SyncRequest request, Long actorId) {
        var req = authorization.requireRequirementManager(requirementKey, actorId);
        authorization.requireWritable(req);
        if (req.groupId() == null) throw error("REQUIREMENT_ROLE_IMMUTABLE", "Legacy requirement roles cannot sync", HttpStatus.CONFLICT);
        if (request == null || !request.confirm() || !"REPLACE".equalsIgnoreCase(request.mode()))
            throw error("COLLAB_SYNC_CONFIRMATION_REQUIRED", "Explicit REPLACE confirmation is required", HttpStatus.BAD_REQUEST);
        jdbc.queryForObject("select id from collab_project where id=? for update", Long.class, req.projectId());
        Map<String, Object> stage = lockStage(req.projectId(), stageKey);
        ensureEditable(stage, request.expectedStageVersion());
        Integer templateVersion = jdbc.queryForObject("select template_version from collab_group where id=?", Integer.class, req.groupId());
        if (!Objects.equals(templateVersion, request.expectedGroupTemplateVersion()))
            throw error("GROUP_TEMPLATE_STALE", "Project group template changed; reload before syncing", HttpStatus.CONFLICT);
        List<DutyDiff> diffs = buildDiffs(req.groupId(), ((Number) stage.get("id")).longValue(), stageKey);
        for (DutyDiff diff : diffs) {
            if (diff.roleKey() == null) continue; // A removed group role keeps the requirement snapshot and actors.
            if (diff.removedRoleFromGroup()) continue;
            if ("REVIEWER".equals(diff.duty())) {
                for (String roleKey : diff.roleKeys()) {
                    List<Long> desired = desiredRoleMembers(req.groupId(), roleKey);
                    validateMembers(req.groupId(), desired, "REVIEWER", req.approvalLevel(), stageKey);
                    replaceReviewerRoleActors(((Number) stage.get("id")).longValue(), roleKey, desired, "SYNC", stage);
                }
            } else {
                validateMembers(req.groupId(), diff.desiredUserIds(), diff.duty(), req.approvalLevel(), stageKey);
                replaceStageDuty(((Number) stage.get("id")).longValue(), diff.duty(), diff.desiredUserIds(), "SYNC", stage);
            }
        }
        maybeResumeBlockedStage(req, ((Number) stage.get("id")).longValue(), actorId);
        return new SyncResult(requirementKey, stageKey, stageKeyVersion(((Number) stage.get("id")).longValue()), diffs);
    }

    private List<DutyDiff> buildDiffs(Long groupId, Long stageId, String stageKey) {
        Map<String, Object> stage = stageById(stageId);
        if (!NOT_STARTED.contains(String.valueOf(stage.get("status"))) || stage.get("actors_frozen_at") != null)
            throw error("REQUIREMENT_STAGE_ACTORS_LOCKED", "Only stages that have not started can sync actors", HttpStatus.CONFLICT);
        List<DutyDiff> result = new ArrayList<>();
        for (String duty : List.of("OWNER", "REVIEWER")) {
            List<RoleBinding> bindings = "REVIEWER".equals(duty) ? reviewerRoleBindings(stageId)
                    : Optional.ofNullable(roleBinding(stageId, duty)).map(List::of).orElseGet(List::of);
            if (bindings.isEmpty()) continue;
            List<Long> current = actorIds(stageId, duty, true);
            LinkedHashSet<Long> desiredSet = new LinkedHashSet<>();
            boolean anyRoleActive = false;
            boolean anyRoleRemoved = false;
            for (RoleBinding binding : bindings) {
                Integer activeRole = jdbc.queryForObject("select count(*) from collab_group_role where group_id=? and role_key=? and status='ACTIVE'", Integer.class, groupId, binding.roleKey());
                if (activeRole == null || activeRole == 0) {
                    anyRoleRemoved = true;
                    continue;
                }
                anyRoleActive = true;
                desiredSet.addAll(desiredRoleMembers(groupId, binding.roleKey()));
            }
            // A deleted source role has no current membership to sync. Preserve existing actors from it.
            if (anyRoleRemoved) desiredSet.addAll(current);
            List<Long> desired = desiredSet.stream().sorted().toList();
            boolean removed = !anyRoleActive;
            if ("REVIEWER".equals(duty)) {
                for (RoleBinding binding : bindings) {
                    List<Long> roleCurrent = reviewerRoleActorIds(stageId, binding.roleKey());
                    List<Long> roleDesired = activeRole(groupId, binding.roleKey()) ? desiredRoleMembers(groupId, binding.roleKey()) : roleCurrent;
                    result.add(new DutyDiff(duty, binding.roleKey(), binding.roleName(), List.of(binding.roleKey()), List.of(binding.roleName()),
                            roleCurrent, roleDesired, difference(roleDesired, roleCurrent), difference(roleCurrent, roleDesired), !activeRole(groupId, binding.roleKey())));
                }
            } else result.add(new DutyDiff(duty, firstRoleKey(bindings), firstRoleName(bindings), reviewerRoleKeys(bindings),
                    reviewerRoleNames(bindings), current, desired, difference(desired, current), difference(current, desired), removed));
        }
        return result;
    }

    private StageActorsView stageActors(Long projectId, Long stageId, String stageKey) {
        Map<String, Object> stage = stageById(stageId);
        RoleBinding owner = roleBinding(stageId, "OWNER");
        List<RoleBinding> reviewers = reviewerRoleBindings(stageId);
        return new StageActorsView(stageId, stageKey, String.valueOf(stage.get("status")), ((Number) stage.get("version_no")).intValue(),
                stage.get("actors_frozen_at") != null, owner == null ? null : owner.roleKey(), owner == null ? null : owner.roleName(),
                actorIds(stageId, "OWNER", true), firstRoleKey(reviewers), firstRoleName(reviewers), reviewerRoleKeys(reviewers), reviewerRoleNames(reviewers),
                actorIds(stageId, "REVIEWER", true), reviewerRoleActors(stageId, reviewers));
    }

    private StageActorsView stageActorsFromRow(java.sql.ResultSet rs, boolean actorsTable) throws java.sql.SQLException {
        Long stageId = rs.getLong("id");
        List<RoleBinding> reviewers = reviewerRoleBindings(stageId);
        return new StageActorsView(stageId, rs.getString("stage_key"), rs.getString("status"), rs.getInt("version_no"),
                rs.getTimestamp("actors_frozen_at") != null, rs.getString("owner_role_key"), rs.getString("owner_role_name"),
                actorIds(stageId, "OWNER", actorsTable), firstRoleKey(reviewers), firstRoleName(reviewers), reviewerRoleKeys(reviewers),
                reviewerRoleNames(reviewers), actorIds(stageId, "REVIEWER", actorsTable), reviewerRoleActors(stageId, reviewers));
    }

    private List<RoleActorView> reviewerRoleActors(Long stageId, List<RoleBinding> bindings) {
        return bindings.stream().map(binding -> new RoleActorView(binding.roleKey(), binding.roleName(), reviewerRoleActorIds(stageId, binding.roleKey()))).toList();
    }

    private List<Long> reviewerRoleActorIds(Long stageId, String roleKey) {
        return jdbc.query("select a.user_id from collab_requirement_stage_reviewer_role_actor a join collab_requirement_role rr on rr.id=a.requirement_role_id join iam_user u on u.id=a.user_id and u.status='ACTIVE' where a.stage_id=? and rr.source_group_role_key=? order by a.user_id",
                (rs,n) -> rs.getLong(1), stageId, roleKey);
    }

    private void replaceReviewerRoleActors(Long stageId, String roleKey, List<Long> userIds, String source, Map<String,Object> stage) {
        if (userIds.isEmpty() && Boolean.TRUE.equals(requiresReviewerForStage(stage))) throw error("REQUIREMENT_STAGE_REVIEWER_REQUIRED", "At least one active reviewer is required for this approval stage", HttpStatus.UNPROCESSABLE_ENTITY);
        Long roleId = jdbc.queryForObject("select rr.id from collab_requirement_stage_reviewer_role sr join collab_requirement_role rr on rr.id=sr.requirement_role_id where sr.stage_id=? and rr.source_group_role_key=?", Long.class, stageId, roleKey);
        jdbc.update("delete from collab_requirement_stage_reviewer_role_actor where stage_id=? and requirement_role_id=?", stageId, roleId);
        for (Long userId : userIds) jdbc.update("insert into collab_requirement_stage_reviewer_role_actor(stage_id,requirement_role_id,user_id,source) values(?,?,?,?)", stageId, roleId, userId, source);
        rebuildReviewerUnion(stageId, stage);
    }

    private boolean requiresReviewerForStage(Map<String,Object> stage) {
        String stageKey = String.valueOf(stage.get("stage_key")); Long projectId = ((Number)stage.get("project_id")).longValue();
        String level = jdbc.queryForObject("select approval_level from collab_project where id=?", String.class, projectId);
        return requiresReviewer(level, stageKey);
    }

    private void rebuildReviewerUnion(Long stageId, Map<String,Object> stage) {
        List<Long> union = jdbc.query("select distinct a.user_id from collab_requirement_stage_reviewer_role_actor a join iam_user u on u.id=a.user_id and u.status='ACTIVE' where a.stage_id=? order by a.user_id", (rs,n)->rs.getLong(1), stageId);
        jdbc.update("delete from collab_requirement_stage_actor where stage_id=? and duty='REVIEWER'", stageId);
        for (Long userId : union) jdbc.update("insert into collab_requirement_stage_actor(stage_id,duty,user_id,source,frozen_at) values(?,'REVIEWER',?,'ROLE',null)", stageId, userId);
        Long representative = union.isEmpty() ? null : union.get(0);
        jdbc.update("update collab_stage set reviewer_user_id=?,version_no=version_no+1,time_updated=now(3) where id=?", representative, stageId);
        jdbc.update("delete from collab_stage_additional_reviewer where stage_id=?", stageId);
        for (int i=1;i<union.size();i++) jdbc.update("insert into collab_stage_additional_reviewer(time_created,time_updated,stage_id,reviewer_user_id) values(now(3),now(3),?,?)", stageId, union.get(i));
    }

    private List<Long> desiredRoleMembers(Long groupId, String roleKey) {
        return jdbc.query("select rm.user_id from collab_group_role r join collab_group_role_member rm on rm.role_id=r.id join collab_group_member gm on gm.group_id=r.group_id and gm.user_id=rm.user_id and gm.status='ACTIVE' join iam_user u on u.id=rm.user_id and u.status='ACTIVE' where r.group_id=? and r.role_key=? and r.status='ACTIVE' order by rm.user_id", (rs,n)->rs.getLong(1), groupId, roleKey);
    }
    private boolean activeRole(Long groupId, String roleKey) { Integer count=jdbc.queryForObject("select count(*) from collab_group_role where group_id=? and role_key=? and status='ACTIVE'",Integer.class,groupId,roleKey); return count!=null&&count>0; }

    private void replaceStageDuty(Long stageId, String duty, List<Long> userIds, String source, Map<String, Object> stage) {
        String stageKey = String.valueOf(stage.get("stage_key"));
        Long projectId = ((Number) stage.get("project_id")).longValue();
        if (userIds.isEmpty() && "OWNER".equals(duty)) throw error("REQUIREMENT_STAGE_OWNER_REQUIRED", "At least one active owner is required", HttpStatus.UNPROCESSABLE_ENTITY);
        jdbc.update("delete from collab_requirement_stage_actor where stage_id=? and duty=?", stageId, duty);
        for (Long userId : userIds) jdbc.update("insert into collab_requirement_stage_actor(stage_id,duty,user_id,source,frozen_at) values(?,?,?,?,null)", stageId, duty, userId, source);
        Long representative = userIds.isEmpty() ? null : userIds.get(0);
        if ("OWNER".equals(duty)) jdbc.update("update collab_stage set owner_user_id=?,version_no=version_no+1,time_updated=now(3) where id=?", representative, stageId);
        else {
            jdbc.update("update collab_stage set reviewer_user_id=?,version_no=version_no+1,time_updated=now(3) where id=?", representative, stageId);
            jdbc.update("delete from collab_stage_additional_reviewer where stage_id=?", stageId);
            for (int i = 1; i < userIds.size(); i++) jdbc.update("insert into collab_stage_additional_reviewer(time_created,time_updated,stage_id,reviewer_user_id) values(now(3),now(3),?,?)", stageId, userIds.get(i));
        }
    }

    private void maybeResumeBlockedStage(CollabProjectAuthorizationService.ProjectRef req, Long stageId, Long actorId) {
        Map<String, Object> stage = stageById(stageId);
        if (!"READY_BLOCKED".equals(stage.get("status")) || stage.get("actors_frozen_at") != null) return;
        String stageKey = String.valueOf(stage.get("stage_key"));
        Integer owners = jdbc.queryForObject("select count(*) from collab_requirement_stage_actor a join collab_group_member gm on gm.group_id=? and gm.user_id=a.user_id and gm.status='ACTIVE' join iam_user u on u.id=a.user_id and u.status='ACTIVE' where a.stage_id=? and a.duty='OWNER'", Integer.class, req.groupId(), stageId);
        if (owners == null || owners == 0) return;
        if (requiresReviewer(req.approvalLevel(), stageKey)) {
            Integer missingRole = jdbc.queryForObject("select count(*) from collab_requirement_stage_reviewer_role rr where rr.stage_id=? and not exists (select 1 from collab_requirement_stage_reviewer_role_actor a join collab_group_member gm on gm.group_id=? and gm.user_id=a.user_id and gm.status='ACTIVE' join iam_user u on u.id=a.user_id and u.status='ACTIVE' where a.stage_id=rr.stage_id and a.requirement_role_id=rr.requirement_role_id)", Integer.class, stageId, req.groupId());
            Integer roleCount = jdbc.queryForObject("select count(*) from collab_requirement_stage_reviewer_role where stage_id=?", Integer.class, stageId);
            if (roleCount != null && roleCount > 0) { if (missingRole != null && missingRole > 0) return; }
            else {
                Integer reviewers = jdbc.queryForObject("select count(*) from collab_requirement_stage_actor a join collab_group_member gm on gm.group_id=? and gm.user_id=a.user_id and gm.status='ACTIVE' join iam_user u on u.id=a.user_id and u.status='ACTIVE' where a.stage_id=? and a.duty='REVIEWER'", Integer.class, req.groupId(), stageId);
                if (reviewers == null || reviewers == 0) return;
            }
        } else if ("L2".equals(req.approvalLevel())) {
            Integer assigned = jdbc.queryForObject("select count(*) from collab_requirement_stage_reviewer_role_actor a join collab_group_member gm on gm.group_id=? and gm.user_id=a.user_id and gm.status='ACTIVE' join iam_user u on u.id=a.user_id and u.status='ACTIVE' where a.stage_id=?", Integer.class, req.groupId(), stageId);
            if (assigned != null && assigned > 0) {
                Integer missingRole = jdbc.queryForObject("select count(*) from collab_requirement_stage_reviewer_role rr where rr.stage_id=? and not exists (select 1 from collab_requirement_stage_reviewer_role_actor a join collab_group_member gm on gm.group_id=? and gm.user_id=a.user_id and gm.status='ACTIVE' join iam_user u on u.id=a.user_id and u.status='ACTIVE' where a.stage_id=rr.stage_id and a.requirement_role_id=rr.requirement_role_id)", Integer.class, stageId, req.groupId());
                if (missingRole != null && missingRole > 0) return;
            }
        }
        Instant now = Instant.now();
        int changed = jdbc.update("update collab_stage set status='IN_PROGRESS',started_at=coalesce(started_at,?),actors_frozen_at=?,version_no=version_no+1,time_updated=? where id=? and status='READY_BLOCKED' and actors_frozen_at is null", now, now, now, stageId);
        if (changed != 1) return;
        jdbc.update("update collab_requirement_stage_actor set frozen_at=coalesce(frozen_at,?) where stage_id=?", now, stageId);
        int version = stageKeyVersion(stageId);
        feishuOutbox.enqueueStageActivation(req.projectId(), stageId, version);
        jdbc.update("insert into collab_stage_event(time_created,time_updated,project_id,stage_id,attempt_no,event_type,actor_user_id,from_status,to_status,reference_id,payload_json,created_at) values(?,?,?,?,?,'ASSIGNMENT_RESTORED',?,'READY_BLOCKED','IN_PROGRESS',null,?,?)",
                now, now, req.projectId(), stageId, jdbc.queryForObject("select attempt_no from collab_stage where id=?", Integer.class, stageId), actorId,
                "{\"reason\":\"REQUIRED_ACTORS_CONFIGURED\"}", now);
    }

    private void validateMembers(Long groupId, List<Long> userIds, String duty, String level, String stageKey) {
        for (Long id : userIds) {
            Integer active = jdbc.queryForObject("select count(*) from collab_group_member gm join iam_user u on u.id=gm.user_id where gm.group_id=? and gm.user_id=? and gm.status='ACTIVE' and u.status='ACTIVE'", Integer.class, groupId, id);
            if (active == null || active == 0) throw error("COLLAB_MEMBER_INVALID", "Actors must be active IAM members of this project group", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if ("OWNER".equals(duty) && userIds.isEmpty()) throw error("REQUIREMENT_STAGE_OWNER_REQUIRED", "At least one active owner is required", HttpStatus.UNPROCESSABLE_ENTITY);
        if ("REVIEWER".equals(duty) && "L1".equals(level) && userIds.isEmpty()) throw error("REQUIREMENT_STAGE_REVIEWER_REQUIRED", "At least one reviewer is required for this approval stage", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private void validateStageRole(Long stageId, String duty, String roleKey) {
        boolean bound = "REVIEWER".equals(duty)
                ? reviewerRoleBindings(stageId).stream().anyMatch(binding -> Objects.equals(binding.roleKey(), roleKey))
                : Optional.ofNullable(roleBinding(stageId, duty)).map(binding -> Objects.equals(binding.roleKey(), roleKey)).orElse(false);
        if (!bound) throw error("REQUIREMENT_ROLE_IMMUTABLE", "The requirement stage role cannot be changed", HttpStatus.CONFLICT);
    }
    private RoleBinding roleBinding(Long stageId, String duty) {
        return jdbc.query("select rr.source_group_role_key,rr.role_name_snapshot from collab_requirement_stage_role sr join collab_requirement_role rr on rr.id=sr.requirement_role_id where sr.stage_id=? and sr.duty=?", rs -> rs.next() ? new RoleBinding(rs.getString(1), rs.getString(2)) : null, stageId, duty);
    }
    private List<RoleBinding> reviewerRoleBindings(Long stageId) {
        List<RoleBinding> bindings = jdbc.query("select rr.source_group_role_key,rr.role_name_snapshot from collab_requirement_stage_reviewer_role sr join collab_requirement_role rr on rr.id=sr.requirement_role_id where sr.stage_id=? order by rr.role_name_snapshot,rr.source_group_role_key",
                (rs, n) -> new RoleBinding(rs.getString(1), rs.getString(2)), stageId);
        RoleBinding legacy = roleBinding(stageId, "REVIEWER");
        if (bindings.isEmpty()) return legacy == null ? List.of() : List.of(legacy);
        if (legacy == null) return bindings;
        List<RoleBinding> ordered = new ArrayList<>();
        ordered.add(legacy);
        bindings.stream().filter(binding -> !Objects.equals(binding.roleKey(), legacy.roleKey())).forEach(ordered::add);
        return ordered;
    }
    private String firstRoleKey(List<RoleBinding> bindings) { return bindings.isEmpty() ? null : bindings.get(0).roleKey(); }
    private String firstRoleName(List<RoleBinding> bindings) { return bindings.isEmpty() ? null : bindings.get(0).roleName(); }
    private List<String> reviewerRoleKeys(List<RoleBinding> bindings) { return bindings.stream().map(RoleBinding::roleKey).toList(); }
    private List<String> reviewerRoleNames(List<RoleBinding> bindings) { return bindings.stream().map(RoleBinding::roleName).toList(); }
    private Map<String, Object> lockStage(Long projectId, String stageKey) {
        Map<String, Object> result = jdbc.query("select id,project_id,stage_key,status,version_no,actors_frozen_at from collab_stage where project_id=? and stage_key=? for update", rs -> rs.next() ? row(rs) : null, projectId, stageKey);
        if (result == null) throw error("COLLAB_STAGE_NOT_FOUND", "Requirement stage not found", HttpStatus.NOT_FOUND);
        return result;
    }
    private Map<String, Object> stage(Long projectId, String stageKey) {
        Map<String, Object> result = jdbc.query("select id,project_id,stage_key,status,version_no,actors_frozen_at from collab_stage where project_id=? and stage_key=?", rs -> rs.next() ? row(rs) : null, projectId, stageKey);
        if (result == null) throw error("COLLAB_STAGE_NOT_FOUND", "Requirement stage not found", HttpStatus.NOT_FOUND);
        return result;
    }
    private Map<String, Object> stageById(Long stageId) {
        Map<String, Object> result = jdbc.query("select id,project_id,stage_key,status,version_no,actors_frozen_at from collab_stage where id=?", rs -> rs.next() ? row(rs) : null, stageId);
        if (result == null) throw error("COLLAB_STAGE_NOT_FOUND", "Requirement stage not found", HttpStatus.NOT_FOUND);
        return result;
    }
    private Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new HashMap<>();
        row.put("id", rs.getLong("id")); row.put("project_id", rs.getLong("project_id"));
        row.put("stage_key", rs.getString("stage_key")); row.put("status", rs.getString("status"));
        row.put("version_no", rs.getInt("version_no")); row.put("actors_frozen_at", rs.getTimestamp("actors_frozen_at"));
        return row;
    }
    private List<Long> actorIds(Long stageId, String duty, boolean actorsTable) {
        if (actorsTable) return jdbc.query("select a.user_id from collab_requirement_stage_actor a join iam_user u on u.id=a.user_id and u.status='ACTIVE' where a.stage_id=? and a.duty=? order by a.user_id", (rs, n) -> rs.getLong(1), stageId, duty);
        if ("OWNER".equals(duty)) return jdbc.query("select owner_user_id from collab_stage where id=?", (rs, n) -> rs.getLong(1), stageId);
        List<Long> ids = new ArrayList<>(jdbc.query("select reviewer_user_id from collab_stage where id=? and reviewer_user_id is not null", (rs, n) -> rs.getLong(1), stageId));
        ids.addAll(jdbc.query("select reviewer_user_id from collab_stage_additional_reviewer where stage_id=?", (rs, n) -> rs.getLong(1), stageId));
        return ids.stream().distinct().toList();
    }
    private void ensureEditable(Map<String, Object> stage, Integer expectedVersion) {
        if (!NOT_STARTED.contains(String.valueOf(stage.get("status"))) || stage.get("actors_frozen_at") != null)
            throw error("REQUIREMENT_STAGE_ACTORS_LOCKED", "Actors of a started stage are frozen", HttpStatus.CONFLICT);
        if (expectedVersion == null || ((Number) stage.get("version_no")).intValue() != expectedVersion)
            throw error("COLLAB_STAGE_VERSION_STALE", "Stage changed; reload and retry", HttpStatus.CONFLICT);
    }
    private int stageKeyVersion(Long stageId) { return jdbc.queryForObject("select version_no from collab_stage where id=?", Integer.class, stageId); }
    private List<Long> canonical(List<Long> ids) { return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().sorted().toList(); }
    private List<Long> difference(List<Long> left, List<Long> right) { Set<Long> r = new HashSet<>(right); return left.stream().filter(id -> !r.contains(id)).toList(); }
    private String normalizeDuty(String value) { String duty = value == null ? "" : value.trim().toUpperCase(Locale.ROOT); if (!DUTIES.contains(duty)) throw error("REQUIREMENT_DUTY_INVALID", "Duty must be OWNER or REVIEWER", HttpStatus.BAD_REQUEST); return duty; }
    private boolean requiresReviewer(String level, String key) { return "L1".equals(level); }
    private BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }

    public record RequirementView(String requirementKey, String groupKey, String groupName, String name, String description,
            String approvalLevel, String status, int currentStageOrder, Integer inheritedTemplateVersion,
            String gitlabRootSnapshot, Long ownerUserId) {}
    public record RoleView(String roleKey, String name) {}
    public record StageActorsView(Long stageId, String stageKey, String status, int versionNo, boolean actorsFrozen,
            String ownerRoleKey, String ownerRoleName, List<Long> ownerUserIds,
            String reviewerRoleKey, String reviewerRoleName, List<String> reviewerRoleKeys, List<String> reviewerRoleNames,
            List<Long> reviewerUserIds, List<RoleActorView> reviewerRoleActors) {}
    public record RoleActorView(String roleKey, String roleName, List<Long> userIds) {}
    public record RolesView(String requirementKey, List<RoleView> roles, List<StageActorsView> stages) {}
    public record SyncRequest(Integer expectedStageVersion, Integer expectedGroupTemplateVersion, String mode, boolean confirm) {}
    public record DutyDiff(String duty, String roleKey, String roleName, List<String> roleKeys, List<String> roleNames,
            List<Long> currentUserIds, List<Long> desiredUserIds,
            List<Long> addedUserIds, List<Long> removedUserIds, boolean removedRoleFromGroup) {}
    public record SyncPreview(String requirementKey, String stageKey, int expectedStageVersion, int groupTemplateVersion,
            String status, boolean actorsFrozen, List<DutyDiff> duties) {}
    public record SyncResult(String requirementKey, String stageKey, int versionNo, List<DutyDiff> appliedDuties) {}
    private record RoleBinding(String roleKey, String roleName) {}
}
