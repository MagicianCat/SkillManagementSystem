package com.company.skillplatform.collabproject.application;

import com.company.skillplatform.common.application.BusinessException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CollabProjectAuthorizationService {
    private final JdbcTemplate jdbc;
    public CollabProjectAuthorizationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public ProjectRef requireMember(String projectKey, Long actorId) {
        return jdbc.query("select p.id,p.project_key,p.owner_user_id,p.approval_level,p.status,p.current_stage_order,p.version_no " +
                "from collab_project p join collab_project_member m on m.project_id=p.id " +
                "where p.project_key=? and m.user_id=? and m.status='ACTIVE' and p.status IN ('ACTIVE','COMPLETED') " +
                "and exists(select 1 from iam_user u where u.id=m.user_id and u.status='ACTIVE')",
                rs -> rs.next() ? new ProjectRef(rs.getLong("id"),rs.getString("project_key"),rs.getLong("owner_user_id"),rs.getString("approval_level"),rs.getString("status"),rs.getInt("current_stage_order"),rs.getInt("version_no")) : null,
                projectKey, actorId);
    }
    public ProjectRef requireMemberOrThrow(String projectKey, Long actorId) {
        ProjectRef ref = requireMember(projectKey, actorId);
        if (ref == null) throw error("COLLAB_PROJECT_NOT_FOUND", "Project not found", HttpStatus.NOT_FOUND);
        return ref;
    }
    public ProjectRef requireOwner(String projectKey, Long actorId) {
        ProjectRef ref = requireMemberOrThrow(projectKey, actorId);
        if (!ref.ownerUserId().equals(actorId)) throw error("COLLAB_PROJECT_OWNER_REQUIRED", "Project owner role is required", HttpStatus.FORBIDDEN);
        return ref;
    }
    public void requireActiveMember(Long projectId, Long userId) {
        Integer count = jdbc.queryForObject("select count(*) from collab_project_member m join iam_user u on u.id=m.user_id where m.project_id=? and m.user_id=? and m.status='ACTIVE' and u.status='ACTIVE'", Integer.class, projectId, userId);
        if (count == null || count == 0) throw error("COLLAB_PROJECT_MEMBER_REQUIRED", "An active project member is required", HttpStatus.UNPROCESSABLE_ENTITY);
    }
    public record ProjectRef(Long projectId, String projectKey, Long ownerUserId, String approvalLevel,
                             String status, int currentStageOrder, int versionNo) {}
    private BusinessException error(String code,String message,HttpStatus status){return new BusinessException(code,message,status);}
}
