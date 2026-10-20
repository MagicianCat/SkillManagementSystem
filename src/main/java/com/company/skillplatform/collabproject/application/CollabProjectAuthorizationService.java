package com.company.skillplatform.collabproject.application;

import com.company.skillplatform.common.application.BusinessException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Shared authorization for requirement routes, including legacy URL compatibility. */
@Service
public class CollabProjectAuthorizationService {
    private final JdbcTemplate jdbc;

    public CollabProjectAuthorizationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * A requirement is visible to active members of its published parent group. A requirement
     * without a group is treated as a pre-migration row and keeps its old member boundary.
     * IAM ADMIN is deliberately checked by role key, never by broad permissions such as
     * admin:identity.
     */
    public ProjectRef requireMember(String projectKey, Long actorId) {
        return jdbc.query("select p.id,p.project_key,p.owner_user_id,p.approval_level,p.status,p.current_stage_order,p.version_no, " +
                        "p.group_id,p.created_by,p.gitlab_root_snapshot,g.group_key,g.owner_user_id group_owner_user_id,g.status group_status,g.legacy_import, " +
                        "exists(select 1 from iam_user_role ur join iam_role r on r.id=ur.role_id and r.status='ACTIVE' where ur.user_id=? and r.role_key='ADMIN') is_admin, " +
                        "exists(select 1 from iam_user u where u.id=? and u.status='ACTIVE') actor_active, " +
                        "exists(select 1 from collab_group_member gm join iam_user u on u.id=gm.user_id and u.status='ACTIVE' where gm.group_id=p.group_id and gm.user_id=? and gm.status='ACTIVE') group_member, " +
                        "exists(select 1 from collab_project_member pm join iam_user u on u.id=pm.user_id and u.status='ACTIVE' where pm.project_id=p.id and pm.user_id=? and pm.status='ACTIVE') legacy_member " +
                        "from collab_project p left join collab_group g on g.id=p.group_id " +
                        "where p.project_key=? and p.status in ('ACTIVE','COMPLETED')",
                rs -> {
                    if (!rs.next() || !rs.getBoolean("actor_active")) return null;
                    Long groupId = (Long) rs.getObject("group_id");
                    boolean admin = rs.getBoolean("is_admin");
                    boolean groupOwner = groupId != null && actorId.equals((Long) rs.getObject("group_owner_user_id"));
                    String groupStatus = rs.getString("group_status");
                    boolean visible = admin || groupOwner || (groupId == null && rs.getBoolean("legacy_member")) ||
                            (groupId != null && ("PUBLISHED".equals(groupStatus) || "ARCHIVED".equals(groupStatus)) && rs.getBoolean("group_member"));
                    if (!visible) return null;
                    return new ProjectRef(rs.getLong("id"), rs.getString("project_key"), rs.getLong("owner_user_id"),
                            rs.getString("approval_level"), rs.getString("status"), rs.getInt("current_stage_order"),
                            rs.getInt("version_no"), groupId, rs.getString("group_key"),
                            (Long) rs.getObject("group_owner_user_id"), rs.getString("group_status"),
                            (Long) rs.getObject("created_by"), rs.getString("gitlab_root_snapshot"),
                            rs.getBoolean("legacy_import"), admin);
                }, actorId, actorId, actorId, actorId, projectKey);
    }

    public ProjectRef requireMemberOrThrow(String projectKey, Long actorId) {
        ProjectRef ref = requireMember(projectKey, actorId);
        if (ref == null) throw error("COLLAB_PROJECT_NOT_FOUND", "Requirement not found", HttpStatus.NOT_FOUND);
        return ref;
    }

    /** Legacy requirement-owner operation; new group operations use requireRequirementManager. */
    public ProjectRef requireOwner(String projectKey, Long actorId) {
        ProjectRef ref = requireMemberOrThrow(projectKey, actorId);
        if (!ref.ownerUserId().equals(actorId)) throw error("COLLAB_PROJECT_OWNER_REQUIRED", "Requirement owner role is required", HttpStatus.FORBIDDEN);
        return ref;
    }

    public ProjectRef requireRequirementManager(String projectKey, Long actorId) {
        ProjectRef ref = requireMemberOrThrow(projectKey, actorId);
        if (actorId.equals(ref.groupOwnerUserId()) || actorId.equals(ref.createdBy())) return ref;
        throw error("COLLAB_REQUIREMENT_MANAGER_REQUIRED", "Requirement creator or parent group owner is required", HttpStatus.FORBIDDEN);
    }

    public void requireWritable(ProjectRef ref) {
        if ("ARCHIVED".equals(ref.groupStatus())) throw error("COLLAB_GROUP_ARCHIVED", "Archived project group requirements are read-only", HttpStatus.CONFLICT);
    }

    public void requireActiveMember(Long projectId, Long userId) {
        Integer count = jdbc.queryForObject("select count(*) from collab_project p where p.id=? and exists(" +
                "select 1 from iam_user u where u.id=? and u.status='ACTIVE') and (" +
                "(p.group_id is not null and exists(select 1 from collab_group_member gm where gm.group_id=p.group_id and gm.user_id=? and gm.status='ACTIVE')) or " +
                "(p.group_id is null and exists(select 1 from collab_project_member pm where pm.project_id=p.id and pm.user_id=? and pm.status='ACTIVE'))) ",
                Integer.class, projectId, userId, userId, userId);
        if (count == null || count == 0) throw error("COLLAB_PROJECT_MEMBER_REQUIRED", "An active requirement member is required", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    public boolean isAdmin(Long actorId) {
        Integer count = jdbc.queryForObject("select count(*) from iam_user_role ur join iam_role r on r.id=ur.role_id " +
                "join iam_user u on u.id=ur.user_id where ur.user_id=? and u.status='ACTIVE' and r.role_key='ADMIN' and r.status='ACTIVE'", Integer.class, actorId);
        return count != null && count > 0;
    }

    public record ProjectRef(Long projectId, String projectKey, Long ownerUserId, String approvalLevel,
                             String status, int currentStageOrder, int versionNo, Long groupId,
                             String groupKey, Long groupOwnerUserId, String groupStatus, Long createdBy,
                             String gitlabRootSnapshot, boolean legacyImport, boolean admin) {}

    private BusinessException error(String code, String message, HttpStatus status) {
        return new BusinessException(code, message, status);
    }
}
