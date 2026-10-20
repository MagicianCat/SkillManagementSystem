package com.company.skillplatform.collabproject.application;

import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollabProjectService {
    private static final List<String> STAGES = List.of("REQUIREMENT","PRODUCT","ARCHITECTURE_DESIGN","UI_DESIGN","FRONTEND_CODING","BACKEND_CODING","TESTING","ACCEPTANCE");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CollabProjectAuthorizationService authorization;
    private final CollabFeishuOutbox feishuOutbox;

    public CollabProjectService(JdbcTemplate jdbc, ObjectMapper mapper, CollabProjectAuthorizationService authorization, CollabFeishuOutbox feishuOutbox) {
        this.jdbc=jdbc; this.mapper=mapper; this.authorization=authorization; this.feishuOutbox=feishuOutbox;
    }

    public boolean isAdmin(Long actorId) { return authorization.isAdmin(actorId); }

    @Transactional(readOnly=true)
    public List<TeamOption> creationOptions(Long actorId, boolean admin) {
        String sql = "select distinct t.id,t.team_name from org_team t ";
        if (!admin) sql += "join org_team_member tm on tm.team_id=t.id and tm.user_id=? and tm.status='ACTIVE' join iam_scoped_role_assignment a on a.team_id=t.id and a.user_id=? and a.role_key='PROJECT_MANAGER' and a.scope_type='TEAM' ";
        sql += "where t.status='ACTIVE' order by t.team_name";
        return (admin ? jdbc.query(sql,(rs,n)->new TeamOption(rs.getLong("id"),rs.getString("team_name"))) : jdbc.query(sql,(rs,n)->new TeamOption(rs.getLong("id"),rs.getString("team_name")),actorId,actorId));
    }
    @Transactional(readOnly=true)
    public List<MemberView> members(String projectKey,Long actorId){var p=authorization.requireMemberOrThrow(projectKey,actorId);return jdbc.query("select m.user_id,u.display_name,u.username,m.project_role from collab_project_member m join iam_user u on u.id=m.user_id where m.project_id=? and m.status='ACTIVE' order by m.project_role desc,u.display_name",(rs,n)->new MemberView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)),p.projectId());}

    @Transactional(readOnly=true)
    public List<UserOption> searchUsers(String query) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2) return List.of();
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.query("select distinct u.id,u.display_name,u.username from iam_user u where u.status='ACTIVE' and (u.display_name like ? or u.username like ? or u.email like ?) order by u.display_name limit 30",
                (rs,n)->new UserOption(rs.getLong("id"),rs.getString("display_name"),rs.getString("username")),like,like,like);
    }

    @Transactional
    public ProjectView create(CreateCommand command, Long actorId, boolean admin, CollabFeishuBindingService.WikiTarget wikiTarget) {
        admin = authorization.isAdmin(actorId);
        if (wikiTarget == null || wikiTarget.spaceId() == null || wikiTarget.spaceId().isBlank()
                || wikiTarget.nodeToken() == null || wikiTarget.nodeToken().isBlank())
            throw error("COLLAB_WIKI_ROOT_REQUIRED", "A valid Feishu Wiki root is required to create a project", HttpStatus.BAD_REQUEST);
        validateApproval(command.approvalLevel());
        if(command.includedStageKeys()!=null&&new HashSet<>(command.includedStageKeys()).size()!=command.includedStageKeys().size())
            throw error("COLLAB_STAGE_SELECTION_INVALID", "Stage selection cannot contain duplicates", HttpStatus.BAD_REQUEST);
        Set<String> includedStages = command.includedStageKeys() == null
                ? new LinkedHashSet<>(STAGES) : new LinkedHashSet<>(command.includedStageKeys());
        if (includedStages.isEmpty() || !STAGES.containsAll(includedStages))
            throw error("COLLAB_STAGE_SELECTION_INVALID", "At least one supported stage must be included", HttpStatus.BAD_REQUEST);
        if ("L1".equals(command.approvalLevel()) && includedStages.size() != STAGES.size())
            throw error("COLLAB_STAGE_SELECTION_REQUIRES_L2_OR_L3", "Only L2 or L3 projects may omit stages", HttpStatus.UNPROCESSABLE_ENTITY);
        List<String> orderedIncludedStages = STAGES.stream().filter(includedStages::contains).toList();
        Integer team = jdbc.query("select count(*) from org_team where id=? and status='ACTIVE'", rs->rs.next()?rs.getInt(1):0, command.homeTeamId());
        if (team == null || team == 0) throw error("COLLAB_HOME_TEAM_INVALID","An active home team is required",HttpStatus.UNPROCESSABLE_ENTITY);
        Integer actorActive = jdbc.query("select count(*) from iam_user where id=? and status='ACTIVE'",rs->rs.next()?rs.getInt(1):0,actorId);
        if (actorActive == null || actorActive==0) throw error("COLLAB_ACTOR_INACTIVE","Active account required",HttpStatus.FORBIDDEN);
        if (!admin) {
            Integer allowed = jdbc.query("select count(*) from org_team_member tm join iam_scoped_role_assignment a on a.user_id=tm.user_id and a.team_id=tm.team_id where tm.team_id=? and tm.user_id=? and tm.status='ACTIVE' and a.scope_type='TEAM' and a.role_key='PROJECT_MANAGER'",rs->rs.next()?rs.getInt(1):0,command.homeTeamId(),actorId);
            if (allowed==null||allowed==0) throw error("COLLAB_PROJECT_CREATE_FORBIDDEN","PROJECT_MANAGER permission for the selected team is required",HttpStatus.FORBIDDEN);
        }
        if (command.name()==null || command.name().isBlank()) throw error("COLLAB_PROJECT_NAME_REQUIRED","Project name is required",HttpStatus.BAD_REQUEST);
        Map<String,Assignment> assignments = new HashMap<>();
        if (command.stageAssignments()!=null) for (Assignment a:command.stageAssignments()) {
            if (a.stageKey()==null || !STAGES.contains(a.stageKey()) || assignments.putIfAbsent(a.stageKey(),a)!=null) throw error("COLLAB_STAGE_ASSIGNMENT_INVALID","Stage assignments must contain unique supported stage keys",HttpStatus.BAD_REQUEST);
        }
        if (assignments.size()!=orderedIncludedStages.size() || !assignments.keySet().containsAll(orderedIncludedStages)) throw error("COLLAB_STAGE_ASSIGNMENT_REQUIRED","Assignments are required for every included stage",HttpStatus.BAD_REQUEST);
        LinkedHashSet<Long> memberIds = new LinkedHashSet<>(); memberIds.add(actorId);
        if(command.memberUserIds()!=null) memberIds.addAll(command.memberUserIds());
        for (Long id:memberIds) if(id==null||!activeUser(id)) throw error("COLLAB_MEMBER_INVALID","Project members must be active IAM users",HttpStatus.UNPROCESSABLE_ENTITY);
        for(String stage:orderedIncludedStages){ Assignment a=assignments.get(stage); if(a.ownerUserId()==null||!memberIds.contains(a.ownerUserId())) throw error("COLLAB_STAGE_OWNER_NOT_MEMBER","Each included stage owner must be a project member",HttpStatus.UNPROCESSABLE_ENTITY);
            boolean manual=requiresReviewer(command.approvalLevel(),stage);
            if(manual&&(a.reviewerUserId()==null||!memberIds.contains(a.reviewerUserId()))) throw error("COLLAB_STAGE_REVIEWER_INVALID","Manual stages require an active project member as reviewer",HttpStatus.UNPROCESSABLE_ENTITY);
            if(!manual&&a.reviewerUserId()!=null&&!memberIds.contains(a.reviewerUserId())) throw error("COLLAB_STAGE_REVIEWER_NOT_MEMBER","Stage reviewer must be a project member",HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String key=UUID.randomUUID().toString(); Instant now=Instant.now();
        int firstOrder = jdbc.queryForObject("select stage_order from collab_stage_definition where stage_key=? and active=1", Integer.class, orderedIncludedStages.get(0));
        int firstGroupStart = stageGroupStart(firstOrder);
        List<String> initialStages = orderedIncludedStages.stream().filter(stage -> {
            Integer order = jdbc.queryForObject("select stage_order from collab_stage_definition where stage_key=? and active=1", Integer.class, stage);
            return order >= firstGroupStart && order <= stageGroupEnd(firstGroupStart);
        }).toList();
        int currentOrder = initialStages.stream().mapToInt(stage -> jdbc.queryForObject("select stage_order from collab_stage_definition where stage_key=? and active=1", Integer.class, stage)).min().orElse(firstOrder);
        jdbc.update("insert into collab_project(time_created,time_updated,project_key,home_team_id,name,description,owner_user_id,approval_level,status,current_stage_order,version_no,created_by) values(?,?,?,?,?,?,?,?,'ACTIVE',?,0,?)",now,now,key,command.homeTeamId(),command.name().trim(),command.description(),actorId,command.approvalLevel(),currentOrder,actorId);
        Long projectId=jdbc.queryForObject("select id from collab_project where project_key=?",Long.class,key);
        jdbc.update("insert into collab_feishu_binding(project_id,wiki_space_id,wiki_root_node_token,publish_actor_user_id,status,time_created,time_updated) values(?,?,?,?,'PENDING',?,?)",projectId,wikiTarget.spaceId(),wikiTarget.nodeToken(),actorId,now,now);
        for(Long id:memberIds) jdbc.update("insert into collab_project_member(time_created,time_updated,project_id,user_id,project_role,status,joined_at,updated_by) values(?,?,?,?,?,'ACTIVE',?,?)",now,now,projectId,id,id.equals(actorId)?"OWNER":"MEMBER",now,actorId);
        for(String stage:STAGES){ Assignment a=assignments.get(stage); StageDef def=jdbc.queryForObject("select stage_order,title,artifact_kind,git_required from collab_stage_definition where stage_key=? and active=1",(rs,n)->new StageDef(rs.getInt(1),rs.getString(2),rs.getString(3),rs.getBoolean(4)),stage);
            boolean included = includedStages.contains(stage);
            Long stageOwner = included ? a.ownerUserId() : actorId;
            Long stageReviewer = included ? a.reviewerUserId() : null;
            String stageStatus = included ? (initialStages.contains(stage) ? "IN_PROGRESS" : "WAITING") : "SKIPPED";
            jdbc.update("insert into collab_stage(time_created,time_updated,project_id,stage_key,stage_order,title,artifact_kind,git_required,definition_snapshot_json,owner_user_id,reviewer_user_id,status,attempt_no,version_no,started_at) values(?,?,?,?,?,?,?,?,?,?,?,?,1,0,?)",now,now,projectId,stage,def.order(),def.title(),def.artifactKind(),def.gitRequired(),json(Map.of("key",stage,"order",def.order(),"title",def.title(),"artifactKind",def.artifactKind(),"gitRequired",def.gitRequired())),stageOwner,stageReviewer,stageStatus,initialStages.contains(stage)?now:null);
        }
        feishuOutbox.enqueueCreateChat(projectId,key);
        feishuOutbox.enqueueWikiFolder(projectId);
        for(String stage:initialStages){
            Long firstStageId=jdbc.queryForObject("select id from collab_stage where project_id=? and stage_key=?",Long.class,projectId,stage);
            feishuOutbox.enqueueInitialStageHandoff(projectId,firstStageId,key);
        }
        return project(key,actorId);
    }

    @Transactional(readOnly=true)
    public List<ProjectView> list(Long actorId) {
        boolean admin = authorization.isAdmin(actorId);
        String sql = "select p.project_key from collab_project p left join collab_group g on g.id=p.group_id where p.status IN ('ACTIVE','COMPLETED') and (" +
                "(?=1) or (p.group_id is null and exists(select 1 from collab_project_member m join iam_user u on u.id=m.user_id where m.project_id=p.id and m.user_id=? and m.status='ACTIVE' and u.status='ACTIVE')) or " +
                "(p.group_id is not null and (g.owner_user_id=? or (g.status='PUBLISHED' and exists(select 1 from collab_group_member gm join iam_user u on u.id=gm.user_id where gm.group_id=p.group_id and gm.user_id=? and gm.status='ACTIVE' and u.status='ACTIVE'))))) order by p.time_updated desc";
        return jdbc.query(sql,(rs,n)->project(rs.getString(1),actorId),admin?1:0,actorId,actorId,actorId);
    }
    @Transactional(readOnly=true)
    public ProjectView project(String projectKey, Long actorId) {
        authorization.requireMemberOrThrow(projectKey,actorId);
        Map<String,Object> row=jdbc.queryForMap("select p.id,p.project_key,p.home_team_id,t.team_name,p.name,p.description,p.owner_user_id,owner.display_name owner_name,p.approval_level,p.status,p.current_stage_order,p.version_no,p.time_created,p.time_updated from collab_project p join org_team t on t.id=p.home_team_id join iam_user owner on owner.id=p.owner_user_id where p.project_key=?",projectKey);
        List<MemberView> members=jdbc.query("select m.user_id,u.display_name,u.username,m.project_role from collab_project_member m join iam_user u on u.id=m.user_id where m.project_id=? and m.status='ACTIVE' order by m.project_role desc,u.display_name",(rs,n)->new MemberView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)),((Number)row.get("id")).longValue());
        List<StageView> stages=stageViews(((Number)row.get("id")).longValue());
        return new ProjectView((String)row.get("project_key"),((Number)row.get("home_team_id")).longValue(),(String)row.get("team_name"),(String)row.get("name"),(String)row.get("description"),((Number)row.get("owner_user_id")).longValue(),(String)row.get("owner_name"),(String)row.get("approval_level"),(String)row.get("status"),((Number)row.get("current_stage_order")).intValue(),((Number)row.get("version_no")).intValue(),members,stages);
    }
    @Transactional(readOnly=true) public List<StageView> stages(String projectKey,Long actorId){var p=authorization.requireMemberOrThrow(projectKey,actorId);return stageViews(p.projectId());}
    @Transactional @SuppressWarnings("unchecked")
    public ProjectView update(String key,Long actor,String name,String description,String requestedLevel,int expectedVersion){var p=authorization.requireOwner(key,actor);authorization.requireWritable(p);if(p.groupId()!=null)throw error("REQUIREMENT_ROLE_IMMUTABLE","Use project group and requirement configuration APIs",HttpStatus.CONFLICT);String level=requestedLevel==null?p.approvalLevel():requestedLevel;validateApproval(level);if(!level.equals(p.approvalLevel())){Integer started=jdbc.queryForObject("select count(*) from collab_stage where project_id=? and status not in ('WAITING','READY','NOT_STARTED','SKIPPED')",Integer.class,p.projectId());if(started!=null&&started>0)throw error("COLLAB_APPROVAL_LEVEL_LOCKED","Approval strategy can only be changed before any stage starts",HttpStatus.CONFLICT);Integer pending=jdbc.queryForObject("select count(*) from collab_submission sub join collab_stage s on s.id=sub.stage_id where s.project_id=? and sub.status='PENDING_APPROVAL'",Integer.class,p.projectId());if(pending!=null&&pending>0)throw error("COLLAB_PENDING_APPROVAL_LEVEL_LOCKED","Cannot change approval level during pending approval",HttpStatus.CONFLICT);for(String stage:STAGES)if(requiresReviewer(level,stage)){Integer valid=jdbc.queryForObject("select count(*) from collab_stage s join collab_project_member m on m.project_id=s.project_id and m.user_id=s.reviewer_user_id and m.status='ACTIVE' where s.project_id=? and s.stage_key=? and s.status<>'SKIPPED' and s.reviewer_user_id is not null",Integer.class,p.projectId(),stage);if(valid==null||valid==0)throw error("COLLAB_STAGE_REVIEWER_REQUIRED","Assign an active reviewer to every included manual stage before changing approval level",HttpStatus.CONFLICT);}}int n=jdbc.update("update collab_project set name=?,description=?,approval_level=?,version_no=version_no+1,time_updated=now(3) where id=? and version_no=?",name.trim(),description,level,p.projectId(),expectedVersion);if(n!=1)throw error("COLLAB_PROJECT_VERSION_CONFLICT","Project changed; reload and retry",HttpStatus.CONFLICT);return project(key,actor);}
    @Transactional
    public MemberView addMember(String key,Long actor,Long userId){var p=authorization.requireOwner(key,actor);authorization.requireWritable(p);if(p.groupId()!=null)throw error("REQUIREMENT_ROLE_IMMUTABLE","Manage people through the parent project group",HttpStatus.CONFLICT);if(!activeUser(userId))throw error("COLLAB_MEMBER_INVALID","Project members must be active IAM users",HttpStatus.UNPROCESSABLE_ENTITY);jdbc.update("insert into collab_project_member(time_created,time_updated,project_id,user_id,project_role,status,joined_at,updated_by) values(now(3),now(3),?,?,'MEMBER','ACTIVE',now(3),?) on duplicate key update status='ACTIVE',updated_by=values(updated_by),time_updated=now(3)",p.projectId(),userId,actor);feishuOutbox.enqueueMemberSync(p.projectId(),userId,"ADD_MEMBER");return jdbc.queryForObject("select m.user_id,u.display_name,u.username,m.project_role from collab_project_member m join iam_user u on u.id=m.user_id where m.project_id=? and m.user_id=?",(rs,i)->new MemberView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)),p.projectId(),userId);}
    @Transactional
    public void removeMember(String key,Long actor,Long userId){var p=authorization.requireOwner(key,actor);authorization.requireWritable(p);if(p.groupId()!=null)throw error("REQUIREMENT_ROLE_IMMUTABLE","Manage people through the parent project group",HttpStatus.CONFLICT);if(actor.equals(userId))throw error("COLLAB_OWNER_TRANSFER_REQUIRED","Transfer ownership before leaving the project",HttpStatus.CONFLICT);Integer assigned=jdbc.queryForObject("select count(*) from collab_stage where project_id=? and owner_user_id=? and status not in ('COMPLETED','SKIPPED')",Integer.class,p.projectId(),userId);Integer reviewing=jdbc.queryForObject("select count(*) from collab_stage s join collab_submission sub on sub.stage_id=s.id join collab_approval a on a.submission_id=sub.id where s.project_id=? and a.reviewer_user_id=? and a.state='PENDING'",Integer.class,p.projectId(),userId);Integer reviewerAssignments=jdbc.queryForObject("select (select count(*) from collab_stage where project_id=? and reviewer_user_id=? and status not in ('COMPLETED','SKIPPED'))+(select count(*) from collab_stage_additional_reviewer x join collab_stage s on s.id=x.stage_id where s.project_id=? and x.reviewer_user_id=? and s.status not in ('COMPLETED','SKIPPED'))",Integer.class,p.projectId(),userId,p.projectId(),userId);if(assigned!=null&&assigned>0||reviewing!=null&&reviewing>0||reviewerAssignments!=null&&reviewerAssignments>0)throw error("COLLAB_MEMBER_HAS_RESPONSIBILITIES","Reassign active stages and pending reviews before removing this member",HttpStatus.CONFLICT);int changed=jdbc.update("update collab_project_member set status='REMOVED',updated_by=?,time_updated=now(3) where project_id=? and user_id=? and project_role<>'OWNER' and status='ACTIVE'",actor,p.projectId(),userId);if(changed>0)feishuOutbox.enqueueMemberSync(p.projectId(),userId,"REMOVE_MEMBER");}
    @Transactional
    public ProjectView transferOwnership(String key,Long actor,Long newOwner){var p=authorization.requireOwner(key,actor);authorization.requireWritable(p);if(p.groupId()!=null)throw error("REQUIREMENT_ROLE_IMMUTABLE","Requirement ownership follows the parent project group",HttpStatus.CONFLICT);authorization.requireActiveMember(p.projectId(),newOwner);jdbc.update("update collab_project set owner_user_id=?,version_no=version_no+1,time_updated=now(3) where id=?",newOwner,p.projectId());jdbc.update("update collab_project_member set project_role='MEMBER',updated_by=?,time_updated=now(3) where project_id=? and project_role='OWNER'",actor,p.projectId());jdbc.update("update collab_project_member set project_role='OWNER',updated_by=?,time_updated=now(3) where project_id=? and user_id=? and status='ACTIVE'",actor,p.projectId(),newOwner);return project(key,actor);}
    @Transactional
    public StageView assign(String key,Long actor,String stageKey,Long owner,Long reviewer,List<Long> additionalReviewerIds){
        var p=authorization.requireOwner(key,actor);
        if(p.groupId()!=null)throw error("REQUIREMENT_ROLE_IMMUTABLE","Requirement stage roles are fixed; edit the assigned people through the requirement actors API",HttpStatus.CONFLICT);
        if(!STAGES.contains(stageKey))throw error("COLLAB_STAGE_NOT_FOUND","Stage not found",HttpStatus.NOT_FOUND);
        authorization.requireActiveMember(p.projectId(),owner);
        StageView current=jdbc.query("select id,stage_key,stage_order,title,artifact_kind,git_required,owner_user_id,reviewer_user_id,status,attempt_no,version_no from collab_stage where project_id=? and stage_key=? for update",rs->rs.next()?stageView(rs):null,p.projectId(),stageKey);
        if(current==null)throw error("COLLAB_STAGE_NOT_FOUND","Stage not found",HttpStatus.NOT_FOUND);
        if(!List.of("WAITING","READY","NOT_STARTED","SKIPPED").contains(current.status()))throw error("COLLAB_STAGE_ASSIGNMENT_LOCKED","Assignments can only be changed before the stage starts",HttpStatus.CONFLICT);
        LinkedHashSet<Long> reviewers=new LinkedHashSet<>();
        if(reviewer!=null)reviewers.add(reviewer);
        if(additionalReviewerIds!=null)for(Long id:additionalReviewerIds){if(id==null)throw error("COLLAB_STAGE_REVIEWER_INVALID","Reviewers must be active project members",HttpStatus.UNPROCESSABLE_ENTITY);reviewers.add(id);}
        for(Long id:reviewers)authorization.requireActiveMember(p.projectId(),id);
        if(!"SKIPPED".equals(current.status())&&requiresReviewer(p.approvalLevel(),stageKey)&&reviewers.isEmpty())throw error("COLLAB_STAGE_REVIEWER_REQUIRED","Manual stages require at least one active reviewer",HttpStatus.UNPROCESSABLE_ENTITY);
        Long primary=reviewer!=null?reviewer:reviewers.stream().findFirst().orElse(null);
        if(primary!=null)reviewers.remove(primary);
        jdbc.update("update collab_stage set owner_user_id=?,reviewer_user_id=?,version_no=version_no+1,time_updated=now(3) where id=?",owner,primary,current.stageId());
        jdbc.update("delete from collab_stage_additional_reviewer where stage_id=?",current.stageId());
        for(Long id:reviewers)jdbc.update("insert into collab_stage_additional_reviewer(time_created,time_updated,stage_id,reviewer_user_id) values(now(3),now(3),?,?)",current.stageId(),id);
        return jdbc.queryForObject("select id,stage_key,stage_order,title,artifact_kind,git_required,owner_user_id,reviewer_user_id,status,attempt_no,version_no from collab_stage where project_id=? and stage_key=?",(rs,n)->stageView(rs),p.projectId(),stageKey);
    }

    @Transactional
    public StageView setStageIncluded(String key,Long actor,String stageKey,boolean included){
        var p=authorization.requireOwner(key,actor);
        authorization.requireWritable(p);
        if(!STAGES.contains(stageKey))throw error("COLLAB_STAGE_NOT_FOUND","Stage not found",HttpStatus.NOT_FOUND);
        if(!List.of("L2","L3").contains(p.approvalLevel()))throw error("COLLAB_STAGE_SELECTION_REQUIRES_L2_OR_L3","Only L2 or L3 projects may change their stage selection",HttpStatus.UNPROCESSABLE_ENTITY);
        jdbc.queryForObject("select id from collab_project where id=? for update",Long.class,p.projectId());
        Map<String,Object> row=jdbc.queryForMap("select id,stage_order,status,owner_user_id,reviewer_user_id from collab_stage where project_id=? and stage_key=? for update",p.projectId(),stageKey);
        long stageId=((Number)row.get("id")).longValue();
        int order=((Number)row.get("stage_order")).intValue();
        String status=String.valueOf(row.get("status"));
        Instant now=Instant.now();
        if(included){
            if(!"SKIPPED".equals(status))return stagesView(p.projectId(),stageKey);
            Map<String,Object> current=jdbc.queryForMap("select current_stage_order,status from collab_project where id=?",p.projectId());
            if("COMPLETED".equals(current.get("status")))throw error("COLLAB_PROJECT_COMPLETED","Completed projects cannot add stages",HttpStatus.CONFLICT);
            if(order<((Number)current.get("current_stage_order")).intValue())throw error("COLLAB_STAGE_BEHIND_CURRENT","A skipped stage before the current workflow position cannot be added",HttpStatus.CONFLICT);
            if(p.groupId()!=null){
                Integer owners=jdbc.queryForObject("select count(*) from collab_requirement_stage_actor a join iam_user u on u.id=a.user_id and u.status='ACTIVE' join collab_group_member gm on gm.group_id=? and gm.user_id=a.user_id and gm.status='ACTIVE' where a.stage_id=? and a.duty='OWNER'",Integer.class,p.groupId(),stageId);
                if(owners==null||owners==0)throw error("REQUIREMENT_STAGE_OWNER_REQUIRED","Configure at least one active owner before adding this stage",HttpStatus.UNPROCESSABLE_ENTITY);
            }else authorization.requireActiveMember(p.projectId(),((Number)row.get("owner_user_id")).longValue());
            if(p.groupId()==null&&requiresReviewer(p.approvalLevel(),stageKey)){
                if(p.groupId()!=null){Integer reviewers=jdbc.queryForObject("select count(*) from collab_requirement_stage_actor a join iam_user u on u.id=a.user_id and u.status='ACTIVE' join collab_group_member gm on gm.group_id=? and gm.user_id=a.user_id and gm.status='ACTIVE' where a.stage_id=? and a.duty='REVIEWER'",Integer.class,p.groupId(),stageId);if(reviewers==null||reviewers==0)throw error("REQUIREMENT_STAGE_REVIEWER_REQUIRED","Configure at least one active reviewer before adding this stage",HttpStatus.UNPROCESSABLE_ENTITY);}
                else {
                Object reviewer=row.get("reviewer_user_id");
                if(reviewer==null)throw error("COLLAB_STAGE_REVIEWER_REQUIRED","Assign a reviewer before adding this manual approval stage",HttpStatus.UNPROCESSABLE_ENTITY);
                authorization.requireActiveMember(p.projectId(),((Number)reviewer).longValue());
                }
            }
            int currentOrder=((Number)current.get("current_stage_order")).intValue();
            boolean activate=stageGroupStart(order)==stageGroupStart(currentOrder);
            jdbc.update("update collab_stage set status=?,started_at=?,actors_frozen_at=?,version_no=version_no+1,time_updated=? where id=? and status='SKIPPED'",
                    activate?"IN_PROGRESS":"WAITING",activate?now:null,activate?now:null,now,stageId);
            if(activate&&p.groupId()!=null)jdbc.update("update collab_requirement_stage_actor set frozen_at=coalesce(frozen_at,?) where stage_id=?",now,stageId);
            if(activate)feishuOutbox.enqueueStageActivation(p.projectId(),stageId,jdbc.queryForObject("select version_no from collab_stage where id=?",Integer.class,stageId));
        }else{
            if("SKIPPED".equals(status))return stagesView(p.projectId(),stageKey);
            if(!List.of("WAITING","READY","NOT_STARTED").contains(status))throw error("COLLAB_STAGE_SELECTION_LOCKED","Completed or active stages cannot be removed",HttpStatus.CONFLICT);
            jdbc.update("update collab_stage set status='SKIPPED',version_no=version_no+1,time_updated=? where id=?",now,stageId);
        }
        jdbc.update("update collab_project set version_no=version_no+1,time_updated=? where id=?",now,p.projectId());
        return stagesView(p.projectId(),stageKey);
    }

    private StageView stagesView(Long projectId,String stageKey){return jdbc.queryForObject("select id,stage_key,stage_order,title,artifact_kind,git_required,owner_user_id,reviewer_user_id,status,attempt_no,version_no from collab_stage where project_id=? and stage_key=?",(rs,n)->stageView(rs),projectId,stageKey);}

    private List<Long> stageAdditionalReviewerIds(Long stageId){return jdbc.query("select reviewer_user_id from collab_stage_additional_reviewer where stage_id=? order by id",(rs,n)->rs.getLong(1),stageId);}
    private List<StageView> stageViews(Long id){return jdbc.query("select id,stage_key,stage_order,title,artifact_kind,git_required,owner_user_id,reviewer_user_id,status,attempt_no,version_no from collab_stage where project_id=? order by stage_order",(rs,n)->stageView(rs),id);}
    private StageView stageView(ResultSet rs)throws SQLException{return new StageView(rs.getLong("id"),rs.getString("stage_key"),rs.getInt("stage_order"),rs.getString("title"),rs.getString("artifact_kind"),rs.getBoolean("git_required"),rs.getLong("owner_user_id"),(Long)rs.getObject("reviewer_user_id"),rs.getString("status"),rs.getInt("attempt_no"),rs.getInt("version_no"));}
    private boolean activeUser(Long id){if(id==null)return false;Integer n=jdbc.queryForObject("select count(*) from iam_user where id=? and status='ACTIVE'",Integer.class,id);return n!=null&&n>0;}
    private int stageGroupStart(int order){return order==4?3:order==6?5:order;}
    private int stageGroupEnd(int start){return start==3||start==5?start+1:start;}
    private boolean requiresReviewer(String level,String stage){return "L1".equals(level)||"L2".equals(level)&&List.of("PRODUCT","FRONTEND_CODING","BACKEND_CODING").contains(stage);}
    private void validateApproval(String level){if(!List.of("L1","L2","L3").contains(level))throw error("COLLAB_APPROVAL_LEVEL_INVALID","Approval level must be L1, L2 or L3",HttpStatus.BAD_REQUEST);}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private BusinessException error(String c,String m,HttpStatus s){return new BusinessException(c,m,s);}

    public record CreateCommand(Long homeTeamId,String name,String description,String approvalLevel,List<Assignment> stageAssignments,List<Long> memberUserIds,List<String> includedStageKeys){
        public CreateCommand(Long homeTeamId,String name,String description,String approvalLevel,List<Assignment> stageAssignments,List<Long> memberUserIds){this(homeTeamId,name,description,approvalLevel,stageAssignments,memberUserIds,null);}
    }
    public record Assignment(String stageKey,Long ownerUserId,Long reviewerUserId){}
    public record TeamOption(Long id,String name){}
    public record CreationOptions(List<TeamOption> teams){}
    public record UserOption(Long id,String displayName,String username){}
    public record MemberView(Long userId,String displayName,String username,String role){}
    public record StageView(Long stageId,String stageKey,int order,String title,String artifactKind,boolean gitRequired,Long ownerUserId,Long reviewerUserId,String status,int attemptNo,int versionNo){}
    public record ProjectView(String projectKey,Long homeTeamId,String homeTeamName,String name,String description,Long ownerUserId,String ownerName,String approvalLevel,String status,int currentStageOrder,int versionNo,List<MemberView> members,List<StageView> stages){}
    private record StageDef(int order,String title,String artifactKind,boolean gitRequired){}
}
