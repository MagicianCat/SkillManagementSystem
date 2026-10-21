package com.company.skillplatform.collabproject.group.application;

import com.company.skillplatform.collabproject.application.CollabFeishuBindingService;
import com.company.skillplatform.collabproject.application.CollabFeishuOutbox;
import com.company.skillplatform.collabproject.application.GroupFeishuOutbox;
import com.company.skillplatform.collabproject.asdm.application.CollabAsdmConfigurationService;
import com.company.skillplatform.collabproject.asdm.application.CollabAsdmRuleService;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmGitlabClient;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmGitlabException;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Group templates and the one-time snapshot used to create each requirement. */
@Service
public class CollabGroupService {
    private static final List<String> STAGES = List.of("REQUIREMENT", "PRODUCT", "ARCHITECTURE_DESIGN", "UI_DESIGN", "FRONTEND_CODING", "BACKEND_CODING", "TESTING", "ACCEPTANCE");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CollabFeishuBindingService wiki;
    private final CollabFeishuOutbox projectOutbox;
    private final GroupFeishuOutbox outbox;
    private final CollabAsdmConfigurationService asdmConfiguration;
    private final CollabAsdmGitlabClient asdmGitlab;
    @Autowired(required = false)
    private CollabAsdmRuleService asdmRules;

    @Autowired
    public CollabGroupService(JdbcTemplate jdbc, ObjectMapper mapper, CollabFeishuBindingService wiki, CollabFeishuOutbox projectOutbox, GroupFeishuOutbox outbox,
                              CollabAsdmConfigurationService asdmConfiguration, CollabAsdmGitlabClient asdmGitlab) {
        this.jdbc = jdbc; this.mapper = mapper; this.wiki = wiki; this.projectOutbox = projectOutbox; this.outbox = outbox;
        this.asdmConfiguration = asdmConfiguration; this.asdmGitlab = asdmGitlab;
    }

    public CollabGroupService(JdbcTemplate jdbc, ObjectMapper mapper, CollabFeishuBindingService wiki, CollabFeishuOutbox projectOutbox, GroupFeishuOutbox outbox) {
        this(jdbc, mapper, wiki, projectOutbox, outbox, null, null);
    }

    @Transactional(readOnly = true)
    public List<TeamOption> creationOptions(long actorId) {
        String sql = "select distinct t.id,t.team_name from org_team t join org_team_member tm on tm.team_id=t.id and tm.user_id=? and tm.status='ACTIVE' join iam_scoped_role_assignment a on a.team_id=t.id and a.user_id=? and a.role_key='PROJECT_MANAGER' and a.scope_type='TEAM' where t.status='ACTIVE' order by t.team_name";
        if (isAdmin(actorId)) sql = "select t.id,t.team_name from org_team t where t.status='ACTIVE' order by t.team_name";
        return isAdmin(actorId)
                ? jdbc.query(sql, (rs,n) -> new TeamOption(rs.getLong(1),rs.getString(2)))
                : jdbc.query(sql, (rs,n) -> new TeamOption(rs.getLong(1),rs.getString(2)), actorId, actorId);
    }

    @Transactional
    public GroupView create(CreateGroup command, long actorId) {
        String name = required(command.name(), "COLLAB_GROUP_NAME_REQUIRED", "Project group name is required", 255);
        String gitlab = command.gitlabRootUrl()==null || command.gitlabRootUrl().isBlank() ? null : normalizeGitlabRoot(command.gitlabRootUrl());
        String asdmRepository = command.asdmRepositoryUrl()==null || command.asdmRepositoryUrl().isBlank() ? null : normalizeAsdmRepository(command.asdmRepositoryUrl());
        requireActive(actorId);
        if (!isAdmin(actorId)) {
            Integer allowed = jdbc.queryForObject("select count(*) from org_team_member tm join iam_scoped_role_assignment a on a.user_id=tm.user_id and a.team_id=tm.team_id where tm.team_id=? and tm.user_id=? and tm.status='ACTIVE' and a.scope_type='TEAM' and a.role_key='PROJECT_MANAGER'", Integer.class, command.homeTeamId(),actorId);
            if (allowed == null || allowed == 0) throw error("COLLAB_GROUP_CREATE_FORBIDDEN", "PROJECT_MANAGER permission for the selected team is required", HttpStatus.FORBIDDEN);
        }
        Integer team = jdbc.queryForObject("select count(*) from org_team where id=? and status='ACTIVE'", Integer.class, command.homeTeamId());
        if (team == null || team == 0) throw error("COLLAB_HOME_TEAM_INVALID", "An active home team is required", HttpStatus.UNPROCESSABLE_ENTITY);
        Instant now = Instant.now(); String key = UUID.randomUUID().toString();
        jdbc.update("insert into collab_group(group_key,home_team_id,name,description,owner_user_id,gitlab_root_url,gitlab_root_normalized,asdm_repository_url,status,template_version,version_no,legacy_import,created_by,time_created,time_updated) values(?,?,?,?,?,?,?,?, 'DRAFT',1,0,false,?,?,?)",key,command.homeTeamId(),name,nullableTrim(command.description()),actorId,gitlab,gitlab,asdmRepository,actorId,now,now);
        long groupId = jdbc.queryForObject("select id from collab_group where group_key=?", Long.class,key);
        jdbc.update("insert into collab_group_member(group_id,user_id,member_type,status,updated_by,time_created,time_updated) values(?,?,'OWNER','ACTIVE',?,?,?)",groupId,actorId,actorId,now,now);
        for (String stage : STAGES) jdbc.update("insert into collab_group_stage_template(group_id,stage_key,default_included,version_no) values(?,?,true,0)",groupId,stage);
        jdbc.update("insert into collab_group_feishu_binding(group_id,status,time_created,time_updated) values(?,'PENDING',?,?)",groupId,now,now);
        audit(groupId,1,actorId,Map.of("action","GROUP_CREATED","name",name,"homeTeamId",command.homeTeamId()));
        return get(key,actorId);
    }

    @Transactional(readOnly = true)
    public List<GroupView> list(long actorId) {
        boolean admin = isAdmin(actorId);
        String sql = "select g.group_key from collab_group g where " + (admin ? "1=1" : "(g.owner_user_id=? or (g.status='PUBLISHED' and exists(select 1 from collab_group_member m join iam_user u on u.id=m.user_id where m.group_id=g.id and m.user_id=? and m.status='ACTIVE' and u.status='ACTIVE'))) ") + " order by g.time_updated desc,g.id desc";
        Object[] args = admin ? new Object[]{} : new Object[]{actorId,actorId};
        return jdbc.query(sql,(rs,n)->get(rs.getString(1),actorId),args);
    }

    @Transactional(readOnly = true)
    public GroupView get(String groupKey,long actorId) {
        Map<String,Object> g = groupRow(groupKey);
        requireGroupView(g,actorId);
        long id = num(g,"id");
        List<MemberView> members = memberViews(id);
        List<RoleView> roles = roleViews(id);
        List<StageTemplateView> stages = templateViews(id);
        int requirementCount = jdbc.queryForObject("select count(*) from collab_project where group_id=?",Integer.class,id);
        Map<String,Object> b = binding(id);
        String wikiUrl = (String)b.get("wiki_root_url");
        return new GroupView(groupKey,num(g,"home_team_id"),(String)g.get("team_name"),(String)g.get("name"),(String)g.get("description"),num(g,"owner_user_id"),(String)g.get("owner_name"),(String)g.get("gitlab_root_url"),(String)g.get("asdm_repository_url"),(String)g.get("status"),((Number)g.get("template_version")).intValue(),((Number)g.get("version_no")).intValue(),Boolean.TRUE.equals(g.get("legacy_import")),instant(g.get("published_at")),members,roles,stages,requirementCount,wikiUrl,(String)b.get("status"));
    }

    @Transactional
    public GroupView update(String key,long actorId,UpdateGroup command) {
        Map<String,Object> g = groupRowForUpdate(key); requireOwner(g,actorId);
        String name = required(command.name(),"COLLAB_GROUP_NAME_REQUIRED","Project group name is required",255);
        String gitlab = command.gitlabRootUrl()==null ? (String)g.get("gitlab_root_url") : normalizeGitlabRoot(command.gitlabRootUrl());
        if ("PUBLISHED".equals(g.get("status")) && !Boolean.TRUE.equals(g.get("legacy_import")) && !Objects.equals(gitlab,g.get("gitlab_root_url"))) throw error("COLLAB_GROUP_GITLAB_ROOT_LOCKED","GitLab root is locked after publishing",HttpStatus.CONFLICT);
        int n=jdbc.update("update collab_group set name=?,description=?,gitlab_root_url=?,gitlab_root_normalized=?,version_no=version_no+1,time_updated=now(3) where id=? and version_no=?",name,nullableTrim(command.description()),gitlab,gitlab,num(g,"id"),command.expectedVersion());
        if(n!=1)throw error("COLLAB_GROUP_VERSION_CONFLICT","Project group changed; reload and retry",HttpStatus.CONFLICT);
        audit(num(g,"id"),((Number)g.get("template_version")).intValue(),actorId,Map.of("action","GROUP_UPDATED"));
        return get(key,actorId);
    }

    @Transactional
    public MemberView addMember(String key,long actorId,long userId) {
        Map<String,Object> g=groupRowForUpdate(key);requireOwner(g,actorId);requireActive(userId);long id=num(g,"id");Instant now=Instant.now();
        jdbc.update("insert into collab_group_member(group_id,user_id,member_type,status,updated_by,time_created,time_updated) values(?,?,'MEMBER','ACTIVE',?,?,?) on duplicate key update status='ACTIVE',updated_by=values(updated_by),time_updated=values(time_updated)",id,userId,actorId,now,now);
        templateChanged(g,actorId,Map.of("action","GROUP_MEMBER_ADDED","userId",userId));
        if("PUBLISHED".equals(g.get("status"))){outbox.enqueue(id,"ADD_MEMBER",userId,"group:"+id+":member:"+userId+":"+UUID.randomUUID(),Map.of("userId",userId));syncRequirementChatMembers(id,userId,"ADD_MEMBER");}
        return member(id,userId);
    }

    @Transactional
    public void removeMember(String key,long actorId,long userId) {
        Map<String,Object> g=groupRowForUpdate(key);requireOwner(g,actorId);long id=num(g,"id");
        if(Objects.equals(num(g,"owner_user_id"),userId))throw error("COLLAB_GROUP_OWNER_TRANSFER_REQUIRED","Transfer ownership before removing the group owner",HttpStatus.CONFLICT);
        Integer assigned=jdbc.queryForObject("select count(*) from collab_requirement_stage_actor a join collab_stage s on s.id=a.stage_id join collab_project p on p.id=s.project_id where p.group_id=? and a.user_id=? and s.actors_frozen_at is not null and s.status not in ('COMPLETED','SKIPPED')",Integer.class,id,userId);
        Integer reviewing=jdbc.queryForObject("select count(*) from collab_approval ap join collab_submission sub on sub.id=ap.submission_id join collab_stage s on s.id=sub.stage_id join collab_project p on p.id=s.project_id where p.group_id=? and ap.reviewer_user_id=? and ap.state='PENDING'",Integer.class,id,userId);
        if((assigned!=null&&assigned>0)||(reviewing!=null&&reviewing>0))throw error("COLLAB_GROUP_MEMBER_HAS_RESPONSIBILITIES","Transfer active stage or pending review responsibilities before removing this member",HttpStatus.CONFLICT);
        int changed=jdbc.update("update collab_group_member set status='REMOVED',updated_by=?,time_updated=now(3) where group_id=? and user_id=? and status='ACTIVE'",actorId,id,userId);
        if(changed>0){jdbc.update("delete rm from collab_group_role_member rm join collab_group_role r on r.id=rm.role_id where r.group_id=? and rm.user_id=?",id,userId);templateChanged(g,actorId,Map.of("action","GROUP_MEMBER_REMOVED","userId",userId));if("PUBLISHED".equals(g.get("status"))){outbox.enqueue(id,"REMOVE_MEMBER",userId,"group:"+id+":member:"+userId+":"+UUID.randomUUID(),Map.of("userId",userId));syncRequirementChatMembers(id,userId,"REMOVE_MEMBER");}}
    }

    private void syncRequirementChatMembers(long groupId,long userId,String action){
        List<Long> projects=jdbc.query("select id from collab_project where group_id=? and status<>'ARCHIVED'",(rs,n)->rs.getLong(1),groupId);
        for(Long projectId:projects)projectOutbox.enqueueMemberSync(projectId,userId,action);
    }

    @Transactional
    public RoleView createRole(String key,long actorId,String roleName) {
        Map<String,Object> g=groupRowForUpdate(key);requireOwner(g,actorId);String name=required(roleName,"COLLAB_GROUP_ROLE_NAME_REQUIRED","Role name is required",100);ensureUniqueRole(num(g,"id"),name,null);
        String roleKey=UUID.randomUUID().toString();Instant now=Instant.now();jdbc.update("insert into collab_group_role(group_id,role_key,role_name,status,version_no,time_created,time_updated) values(?,?,?,'ACTIVE',0,?,?)",num(g,"id"),roleKey,name,now,now);
        templateChanged(g,actorId,Map.of("action","GROUP_ROLE_CREATED","roleKey",roleKey,"roleName",name));return role(num(g,"id"),roleKey);
    }

    @Transactional
    public RoleView renameRole(String key,long actorId,String roleKey,String roleName) {
        Map<String,Object> g=groupRowForUpdate(key);requireOwner(g,actorId);roleRow(num(g,"id"),roleKey);
        throw error("COLLAB_GROUP_ROLE_NAME_IMMUTABLE","Role names cannot be changed after creation",HttpStatus.CONFLICT);
    }

    @Transactional
    public void deleteRole(String key,long actorId,String roleKey) {
        Map<String,Object> g=groupRowForUpdate(key);requireOwner(g,actorId);long groupId=num(g,"id");Map<String,Object> role=roleRow(groupId,roleKey);long roleId=((Number)role.get("id")).longValue();
        Integer used=jdbc.queryForObject("select (select count(*) from collab_group_stage_template where owner_role_id=?) + (select count(*) from collab_group_stage_reviewer_role where role_id=?)",Integer.class,roleId,roleId);
        if(used!=null&&used>0)throw error("COLLAB_GROUP_ROLE_IN_TEMPLATE","Remove this role from stage templates before deleting it",HttpStatus.CONFLICT);
        jdbc.update("delete from collab_group_role_member where role_id=?",roleId);jdbc.update("delete from collab_group_role where id=?",roleId);templateChanged(g,actorId,Map.of("action","GROUP_ROLE_DELETED","roleKey",roleKey));
    }

    @Transactional
    public RoleView replaceRoleMembers(String key,long actorId,String roleKey,List<Long> userIds) {
        Map<String,Object> g=groupRowForUpdate(key);requireOwner(g,actorId);long groupId=num(g,"id");Map<String,Object> role=roleRow(groupId,roleKey);long roleId=((Number)role.get("id")).longValue();LinkedHashSet<Long> ids=new LinkedHashSet<>(userIds==null?List.of():userIds);
        if(ids.contains(null))throw error("COLLAB_GROUP_MEMBER_INVALID","Role members must be active group members",HttpStatus.UNPROCESSABLE_ENTITY);
        for(long uid:ids){Integer active=jdbc.queryForObject("select count(*) from collab_group_member m join iam_user u on u.id=m.user_id where m.group_id=? and m.user_id=? and m.status='ACTIVE' and u.status='ACTIVE'",Integer.class,groupId,uid);if(active==null||active==0)throw error("COLLAB_GROUP_ROLE_MEMBER_INVALID","Role members must be active group members",HttpStatus.UNPROCESSABLE_ENTITY);}
        jdbc.update("delete from collab_group_role_member where role_id=?",roleId);for(long uid:ids)jdbc.update("insert into collab_group_role_member(role_id,user_id) values(?,?)",roleId,uid);
        templateChanged(g,actorId,Map.of("action","GROUP_ROLE_MEMBERS_REPLACED","roleKey",roleKey,"userIds",ids));return role(groupId,roleKey);
    }

    @Transactional
    public StageTemplateView setStageTemplate(String key,long actorId,String stageKey,StageTemplateCommand command) {
        Map<String,Object> g=groupRowForUpdate(key);requireOwner(g,actorId);validStage(stageKey);long id=num(g,"id");Long ownerRole=roleId(id,command.ownerRoleKey());List<String> reviewerKeys=canonicalRoleKeys(command.reviewerRoleKeys(),command.reviewerRoleKey());List<Long> reviewerRoles=reviewerKeys.stream().map(k->roleId(id,k)).toList();Long legacyReviewer=reviewerRoles.isEmpty()?null:reviewerRoles.get(0);Instant now=Instant.now();
        if(((Number)g.get("template_version")).intValue()!=command.expectedVersion())throw error("COLLAB_GROUP_TEMPLATE_VERSION_CONFLICT","Project group template changed; reload and retry",HttpStatus.CONFLICT);
        jdbc.update("insert into collab_group_stage_template(group_id,stage_key,owner_role_id,reviewer_role_id,default_included,version_no) values(?,?,?,?,?,0) on duplicate key update owner_role_id=values(owner_role_id),reviewer_role_id=values(reviewer_role_id),default_included=values(default_included),version_no=version_no+1",id,stageKey,ownerRole,legacyReviewer,command.included());
        jdbc.update("delete from collab_group_stage_reviewer_role where group_id=? and stage_key=?",id,stageKey);for(Long role:reviewerRoles)jdbc.update("insert into collab_group_stage_reviewer_role(group_id,stage_key,role_id) values(?,?,?)",id,stageKey,role);
        templateChanged(g,actorId,Map.of("action","STAGE_TEMPLATE_CHANGED","stageKey",stageKey,"ownerRoleKey",Objects.toString(command.ownerRoleKey(),""),"reviewerRoleKeys",reviewerKeys,"included",command.included()));
        return template(id,stageKey);
    }

    @Transactional(readOnly=true)
    public TemplatePreview preview(String key,long actorId) {
        Map<String,Object> g=groupRow(key);requireGroupView(g,actorId);long id=num(g,"id");List<Issue> issues=new ArrayList<>();
        List<Issue> warnings=new ArrayList<>();
        if(g.get("asdm_repository_url")!=null){
            if(asdmConfiguration==null || !asdmConfigurationModuleEnabled()) issues.add(new Issue(null,"ASDM_MODULE_DISABLED","ASDM GitLab integration is disabled"));
            Integer valid=jdbc.queryForObject("select count(*) from collab_asdm_credential where group_id=? and status='VALID'",Integer.class,id);
            if(valid==null||valid==0)issues.add(new Issue(null,"ASDM_CREDENTIAL_REQUIRED","Configure and validate an ASDM Project Access Token before publishing"));
        }
        Map<String,Object> b=binding(id);if(b.get("wiki_space_id")==null||b.get("wiki_root_node_token")==null)issues.add(new Issue(null,"WIKI_ROOT_REQUIRED","Configure a writable Feishu Wiki root before publishing"));
        for(StageTemplateView stage:templateViews(id))if(stage.included()){
            checkRoleUsers(id,stage,"OWNER",stage.ownerRoleKey(),stage.ownerRoleName(),issues);
            for(int i=0;i<stage.reviewerRoleKeys().size();i++)if(roleUsers(id,stage.reviewerRoleKeys().get(i)).isEmpty())
                warnings.add(new Issue(stage.stageKey(),"STAGE_REVIEWER_ROLE_EMPTY","Reviewer role "+stage.reviewerRoleNames().get(i)+" has no active group members; requirements needing approval at this stage cannot be created until it is staffed"));
        }
        return new TemplatePreview(issues.isEmpty(),issues,warnings);
    }

    @Transactional
    public GroupView publish(String key,long actorId,int expectedVersion) {
        Map<String,Object> g=groupRowForUpdate(key);requireOwner(g,actorId);long id=num(g,"id");
        if("PUBLISHED".equals(g.get("status")))return get(key,actorId);
        if(((Number)g.get("version_no")).intValue()!=expectedVersion)throw error("COLLAB_GROUP_VERSION_CONFLICT","Project group changed; reload and retry",HttpStatus.CONFLICT);
        TemplatePreview preview=preview(key,actorId);if(!preview.valid())throw new BusinessException("COLLAB_GROUP_PUBLISH_INVALID", "Project group is not ready to publish: "+preview.issues().stream().map(Issue::code).distinct().toList(),HttpStatus.UNPROCESSABLE_ENTITY);
        jdbc.update("update collab_group set status='PUBLISHED',published_at=now(3),version_no=version_no+1,time_updated=now(3) where id=?",id);
        jdbc.update("update collab_group_feishu_binding set publish_actor_user_id=?,time_updated=now(3) where group_id=?",actorId,id);
        outbox.enqueue(id,"CREATE_CHAT",id,"group:"+id+":create-chat",Map.of());outbox.enqueue(id,"CREATE_WIKI_FOLDER",id,"group:"+id+":create-wiki-folder",Map.of());
        audit(id,((Number)g.get("template_version")).intValue(),actorId,Map.of("action","GROUP_PUBLISHED"));return get(key,actorId);
    }

    @Transactional
    public BindingView configureWikiRoot(String key,long actorId,String url) {
        Map<String,Object> g=groupRowForUpdate(key);requireOwner(g,actorId);long id=num(g,"id");
        if ("PUBLISHED".equals(g.get("status")) && !Boolean.TRUE.equals(g.get("legacy_import"))) throw error("COLLAB_GROUP_WIKI_ROOT_LOCKED","Configure the Wiki root before publishing the project group",HttpStatus.CONFLICT);
        CollabFeishuBindingService.WikiTarget target=wiki.validateWikiRoot(actorId,url);Instant now=Instant.now();
        Map<String,Object> previous=binding(id);
        if(Boolean.TRUE.equals(g.get("legacy_import"))&&previous.get("wiki_group_node_token")!=null&&!Objects.equals(previous.get("wiki_root_node_token"),target.nodeToken()))throw error("COLLAB_GROUP_WIKI_ROOT_LOCKED","The legacy group Wiki directory already exists",HttpStatus.CONFLICT);
        jdbc.update("insert into collab_group_feishu_binding(group_id,wiki_space_id,wiki_root_node_token,wiki_root_url,publish_actor_user_id,status,time_created,time_updated) values(?,?,?,?,?,'READY',?,?) on duplicate key update wiki_space_id=values(wiki_space_id),wiki_root_node_token=values(wiki_root_node_token),wiki_root_url=values(wiki_root_url),publish_actor_user_id=values(publish_actor_user_id),status='READY',last_error=null,time_updated=values(time_updated)",id,target.spaceId(),target.nodeToken(),url,actorId,now,now);
        if(Boolean.TRUE.equals(g.get("legacy_import"))){if(previous.get("chat_id")==null)outbox.enqueue(id,"CREATE_CHAT",id,"group:"+id+":create-chat",Map.of());if(previous.get("wiki_group_node_token")==null)outbox.enqueue(id,"CREATE_WIKI_FOLDER",id,"group:"+id+":create-wiki-folder",Map.of());}
        jdbc.update("update collab_group set version_no=version_no+1,time_updated=now(3) where id=?",id);
        return bindingView(id);
    }

    @Transactional(readOnly=true)
    public BindingView binding(String key,long actorId){Map<String,Object> g=groupRow(key);requireGroupView(g,actorId);return bindingView(num(g,"id"));}

    @Transactional(readOnly=true)
    public List<RequirementView> requirements(String key,long actorId){Map<String,Object> g=groupRow(key);requireGroupMembers(g,actorId);return jdbc.query("select project_key,name,description,approval_level,inherited_template_version,gitlab_root_snapshot,asdm_branch,asdm_binding_status,current_stage_order,status from collab_project where group_id=? order by time_created desc,id desc",(rs,n)->new RequirementView(rs.getString("project_key"),key,rs.getString("name"),rs.getString("description"),rs.getString("approval_level"),(Integer)rs.getObject("inherited_template_version"),rs.getString("gitlab_root_snapshot"),rs.getString("asdm_branch"),rs.getString("asdm_binding_status"),rs.getInt("current_stage_order"),rs.getString("status")),num(g,"id"));}

    @Transactional
    public RequirementView createRequirement(String groupKey,long actorId,CreateRequirement command){
        Map<String,Object> g=groupRow(groupKey);requireGroupMembers(g,actorId);if(!"PUBLISHED".equals(g.get("status")))throw error("COLLAB_GROUP_NOT_PUBLISHED","Requirements can only be created in a published group",HttpStatus.CONFLICT);
        int observedGroupVersion=((Number)g.get("version_no")).intValue();
        if(!Objects.equals(num(g,"owner_user_id"),actorId)&&!isProjectManagerRoleMember(num(g,"id"),actorId))throw error("COLLAB_REQUIREMENT_CREATE_FORBIDDEN","Only the group owner or an authorized project manager may create requirements",HttpStatus.FORBIDDEN);
        String name=required(command.name(),"COLLAB_REQUIREMENT_NAME_REQUIRED","Requirement name is required",255);String level=command.approvalLevel();if(!List.of("L1","L2","L3").contains(level))throw error("COLLAB_APPROVAL_LEVEL_INVALID","Approval level must be L1, L2 or L3",HttpStatus.BAD_REQUEST);
        Integer version=((Number)g.get("template_version")).intValue();if(command.expectedTemplateVersion()!=version)throw error("GROUP_TEMPLATE_STALE","Project group template changed; reload and retry",HttpStatus.CONFLICT);
        String gitlab=(String)g.get("gitlab_root_url");
        String asdmRepository=(String)g.get("asdm_repository_url");
        String asdmBranch=null;String asdmBaseline=null;String asdmBindingStatus="DISABLED";
        if(asdmRepository!=null){
            asdmBranch=required(command.asdmBranch(),"ASDM_BRANCH_REQUIRED","Select a GitLab branch for this requirement",255);
            if(asdmBranch.chars().anyMatch(Character::isISOControl)||asdmBranch.startsWith("/")||asdmBranch.endsWith("/"))throw error("ASDM_BRANCH_INVALID","The ASDM branch name is invalid",HttpStatus.BAD_REQUEST);
            Integer duplicate=jdbc.queryForObject("select count(*) from collab_project where group_id=? and asdm_branch=?",Integer.class,num(g,"id"),asdmBranch);
            if(duplicate!=null&&duplicate>0)throw error("ASDM_BRANCH_ALREADY_USED","This branch is already bound to another requirement in the project group",HttpStatus.CONFLICT);
            if(asdmConfiguration==null||asdmGitlab==null)throw error("ASDM_MODULE_DISABLED","ASDM GitLab integration is unavailable",HttpStatus.SERVICE_UNAVAILABLE);
            CollabAsdmConfigurationService.RepositoryAccess access=asdmConfiguration.requireAccess(num(g,"id"));
            try {
                CollabAsdmGitlabClient.BranchInfo branch=asdmGitlab.getBranch(access.projectId(),asdmBranch,access.token(),access.repositoryBase());
                List<CollabAsdmGitlabClient.TreeEntry> requirementRoot=asdmGitlab.listTree(access.projectId(),branch.headSha(),"workspace/requirements",access.token(),asdmConfiguration.maxTreeEntries(),access.repositoryBase());
                if(requirementRoot.isEmpty())throw error("ASDM_REQUIREMENTS_DIRECTORY_NOT_FOUND","The branch does not contain workspace/requirements",HttpStatus.UNPROCESSABLE_ENTITY);
                asdmBaseline=branch.headSha();asdmBindingStatus="WAITING_DIRECTORY";
            } catch(CollabAsdmGitlabException ex) {
                String code=ex.errorCode().equals("GITLAB_RESOURCE_NOT_FOUND")?"ASDM_BRANCH_OR_REQUIREMENTS_DIRECTORY_NOT_FOUND":ex.errorCode();
                throw error(code,"Unable to validate the ASDM branch or workspace/requirements directory",ex.httpStatus()==401||ex.httpStatus()==403?HttpStatus.UNPROCESSABLE_ENTITY:HttpStatus.UNPROCESSABLE_ENTITY);
            }
        } else if(command.asdmBranch()!=null&&!command.asdmBranch().isBlank()) {
            throw error("ASDM_BRANCH_NOT_ALLOWED","A branch can only be selected when the project group has an ASDM repository",HttpStatus.BAD_REQUEST);
        }
        g=groupRowForUpdate(groupKey);
        Integer lockedVersion=jdbc.queryForObject("select version_no from collab_group where id=? for update",Integer.class,num(g,"id"));
        if(lockedVersion==null||lockedVersion!=observedGroupVersion)throw error("COLLAB_GROUP_VERSION_CONFLICT","Project group changed while validating the ASDM branch; retry requirement creation",HttpStatus.CONFLICT);
        requireGroupMembers(g,actorId);
        if(!"PUBLISHED".equals(g.get("status")))throw error("COLLAB_GROUP_NOT_PUBLISHED","Requirements can only be created in a published group",HttpStatus.CONFLICT);
        if(asdmBranch!=null){Integer duplicateAfterValidation=jdbc.queryForObject("select count(*) from collab_project where group_id=? and asdm_branch=?",Integer.class,num(g,"id"),asdmBranch);if(duplicateAfterValidation!=null&&duplicateAfterValidation>0)throw error("ASDM_BRANCH_ALREADY_USED","This branch is already bound to another requirement in the project group",HttpStatus.CONFLICT);}
        Map<String,Object> wikiBinding=binding(num(g,"id"));if(wikiBinding.get("wiki_space_id")==null||wikiBinding.get("wiki_root_node_token")==null)throw error("COLLAB_GROUP_WIKI_ROOT_REQUIRED","Configure a Feishu Wiki root before creating requirements",HttpStatus.CONFLICT);
        LinkedHashSet<String> included=new LinkedHashSet<>(command.includedStageKeys()==null?List.of():command.includedStageKeys());if(included.isEmpty()||!STAGES.containsAll(included)||included.size()!=(command.includedStageKeys()==null?0:command.includedStageKeys().size()))throw error("COLLAB_STAGE_SELECTION_INVALID","Select unique supported workflow stages",HttpStatus.BAD_REQUEST);
        if("L1".equals(level)&&included.size()!=STAGES.size())throw error("COLLAB_STAGE_SELECTION_REQUIRES_L2_OR_L3","Only L2 or L3 requirements may omit stages",HttpStatus.UNPROCESSABLE_ENTITY);
        LinkedHashSet<String> noApproval=new LinkedHashSet<>(command.stagesWithoutApproval()==null?List.of():command.stagesWithoutApproval());if(!Set.of("L2").contains(level)&&!noApproval.isEmpty()||!included.containsAll(noApproval)||noApproval.size()!=(command.stagesWithoutApproval()==null?0:command.stagesWithoutApproval().size()))throw error("COLLAB_STAGE_APPROVAL_SELECTION_INVALID","Only unique included L2 stages may opt out of approval",HttpStatus.UNPROCESSABLE_ENTITY);
        for(String stage:included){StageTemplateView t=template(num(g,"id"),stage);if(t.ownerRoleKey()==null||t.ownerUserIds().isEmpty())throw error("COLLAB_STAGE_OWNER_ROLE_REQUIRED","Configure a staffed owner role for stage "+stage,HttpStatus.UNPROCESSABLE_ENTITY);if("L1".equals(level)){if(t.reviewerRoleKeys().isEmpty())throw error("COLLAB_STAGE_REVIEWER_ROLE_REQUIRED","Configure at least one staffed reviewer role for stage "+stage,HttpStatus.UNPROCESSABLE_ENTITY);for(int i=0;i<t.reviewerRoleKeys().size();i++)if(roleUsers(num(g,"id"),t.reviewerRoleKeys().get(i)).isEmpty())throw error("COLLAB_STAGE_REVIEWER_ROLE_REQUIRED","Configure an active member for reviewer role "+t.reviewerRoleNames().get(i)+" at stage "+stage,HttpStatus.UNPROCESSABLE_ENTITY);}}

        Instant now=Instant.now();String requirementKey=UUID.randomUUID().toString();List<String> ordered=STAGES.stream().filter(included::contains).toList();int firstOrder=stageOrder(ordered.get(0));int firstGroupStart=stageGroupStart(firstOrder);List<String> initial=ordered.stream().filter(s->{int o=stageOrder(s);return o>=firstGroupStart&&o<=stageGroupEnd(firstGroupStart);}).toList();int currentOrder=initial.stream().mapToInt(this::stageOrder).min().orElse(firstOrder);
        long groupId=num(g,"id");jdbc.update("insert into collab_project(time_created,time_updated,project_key,home_team_id,name,description,owner_user_id,approval_level,status,current_stage_order,version_no,created_by,group_id,inherited_template_version,gitlab_root_snapshot,asdm_branch,asdm_binding_status,asdm_baseline_head_sha,asdm_last_scanned_head_sha) values(?,?,?,?,?,?,?,?,'ACTIVE',?,0,?,?,?,?,?,?,?,?)",now,now,requirementKey,g.get("home_team_id"),name,nullableTrim(command.description()),actorId,level,currentOrder,actorId,groupId,version,gitlab,asdmBranch,asdmBindingStatus,asdmBaseline,asdmBaseline);
        long projectId=jdbc.queryForObject("select id from collab_project where project_key=?",Long.class,requirementKey);
        if(asdmRules!=null) asdmRules.effectiveForRequirement(projectId,true);
        jdbc.update("insert into collab_feishu_binding(project_id,wiki_space_id,wiki_root_node_token,wiki_parent_node_token,publish_actor_user_id,status,time_created,time_updated) values(?,?,?,NULL,?,'PENDING',?,?)",projectId,wikiBinding.get("wiki_space_id"),wikiBinding.get("wiki_group_node_token"),actorId,now,now);
        projectOutbox.enqueueCreateChat(projectId,requirementKey);
        projectOutbox.enqueueWikiFolder(projectId);
        List<Map<String,Object>> groupRoles=jdbc.queryForList("select r.id,r.role_key,r.role_name from collab_group_role r where r.group_id=? and r.status='ACTIVE' order by r.id",groupId);
        Map<String,Long> requirementRoles=new HashMap<>();
        for(Map<String,Object> role:groupRoles){String roleKey=(String)role.get("role_key");String roleName=(String)role.get("role_name");jdbc.update("insert into collab_requirement_role(requirement_id,source_group_role_key,role_name_snapshot) values(?,?,?)",projectId,roleKey,roleName);long rid=jdbc.queryForObject("select id from collab_requirement_role where requirement_id=? and source_group_role_key=?",Long.class,projectId,roleKey);requirementRoles.put(roleKey,rid);}
        List<Long> groupMembers=jdbc.query("select user_id from collab_group_member where group_id=? and status='ACTIVE'",(rs,n)->rs.getLong(1),groupId);for(Long uid:groupMembers)jdbc.update("insert into collab_project_member(time_created,time_updated,project_id,user_id,project_role,status,joined_at,updated_by) values(?,?,?,?,?,'ACTIVE',?,?)",now,now,projectId,uid,uid.equals(actorId)?"OWNER":"MEMBER",now,actorId);
        for(String stage:STAGES){StageTemplateView t=template(groupId,stage);int order=stageOrder(stage);boolean isIncluded=included.contains(stage);Long representativeOwner=firstOrNull(t.ownerUserIds()); if(representativeOwner==null) representativeOwner=actorId;Long representativeReviewer=noApproval.contains(stage)?null:firstOrNull(t.reviewerUserIds());
            StageDef def=stageDef(stage);String status=isIncluded?(initial.contains(stage)?"IN_PROGRESS":"WAITING"):"SKIPPED";
            jdbc.update("insert into collab_stage(time_created,time_updated,project_id,stage_key,stage_order,title,artifact_kind,git_required,definition_snapshot_json,owner_user_id,reviewer_user_id,status,attempt_no,version_no,started_at) values(?,?,?,?,?,?,?,?,?,?,?,?,1,0,?)",now,now,projectId,stage,order,def.title(),def.artifactKind(),def.gitRequired(),json(Map.of("key",stage,"order",order,"title",def.title(),"artifactKind",def.artifactKind(),"gitRequired",def.gitRequired())),representativeOwner,representativeReviewer,status,initial.contains(stage)?now:null);
            Long stageId=jdbc.queryForObject("select id from collab_stage where project_id=? and stage_key=?",Long.class,projectId,stage);
            String ownerKey=t.ownerRoleKey();if(ownerKey!=null&&requirementRoles.containsKey(ownerKey))jdbc.update("insert into collab_requirement_stage_role(stage_id,duty,requirement_role_id) values(?,'OWNER',?)",stageId,requirementRoles.get(ownerKey));
            for(String reviewerKey:t.reviewerRoleKeys())if(requirementRoles.containsKey(reviewerKey)){
                long requirementRoleId=requirementRoles.get(reviewerKey);
                jdbc.update("insert into collab_requirement_stage_reviewer_role(stage_id,requirement_role_id) values(?,?)",stageId,requirementRoleId);
                if(!noApproval.contains(stage))for(Long uid:roleUsers(groupId,reviewerKey))jdbc.update("insert into collab_requirement_stage_reviewer_role_actor(stage_id,requirement_role_id,user_id,source) values(?,?,?,'INHERITED')",stageId,requirementRoleId,uid);
            }
            // Keep the legacy single-role row as a representative for older consumers.
            if(!t.reviewerRoleKeys().isEmpty()&&requirementRoles.containsKey(t.reviewerRoleKeys().get(0)))jdbc.update("insert into collab_requirement_stage_role(stage_id,duty,requirement_role_id) values(?,'REVIEWER',?)",stageId,requirementRoles.get(t.reviewerRoleKeys().get(0)));
            if(ownerKey!=null)for(Long uid:t.ownerUserIds())insertStageActor(stageId,"OWNER",uid,initial.contains(stage)?now:null);
            if(!noApproval.contains(stage))for(Long uid:t.reviewerUserIds())insertStageActor(stageId,"REVIEWER",uid,initial.contains(stage)?now:null);
            if(initial.contains(stage))jdbc.update("update collab_stage set actors_frozen_at=? where id=?",now,stageId);
        }
        for(String stage:initial){Long stageId=jdbc.queryForObject("select id from collab_stage where project_id=? and stage_key=?",Long.class,projectId,stage);projectOutbox.enqueueInitialStageHandoff(projectId,stageId,requirementKey);}
        return new RequirementView(requirementKey,groupKey,name,nullableTrim(command.description()),level,version,gitlab,asdmBranch,asdmBindingStatus,currentOrder,"ACTIVE");
    }

    private void insertStageActor(long stage,String duty,long user,Instant frozenAt){jdbc.update("insert into collab_requirement_stage_actor(stage_id,duty,user_id,source,frozen_at) values(?,?,?,'INHERITED',?)",stage,duty,user,frozenAt);}
    private void checkRoleUsers(long groupId,StageTemplateView s,String duty,String roleKey,String roleName,List<Issue> issues){if(roleKey==null){issues.add(new Issue(s.stageKey(),"OWNER".equals(duty)?"STAGE_OWNER_ROLE_REQUIRED":"STAGE_REVIEWER_ROLE_REQUIRED","Assign a "+duty.toLowerCase(Locale.ROOT)+" role for "+s.stageKey()));return;}List<Long> users="OWNER".equals(duty)?s.ownerUserIds():s.reviewerUserIds();if(users.isEmpty())issues.add(new Issue(s.stageKey(),"OWNER".equals(duty)?"STAGE_OWNER_ROLE_EMPTY":"STAGE_REVIEWER_ROLE_EMPTY","Role "+roleName+" has no active group members"));}
    private List<MemberView> memberViews(long groupId){return jdbc.query("select m.user_id,u.display_name,u.username,m.member_type from collab_group_member m join iam_user u on u.id=m.user_id where m.group_id=? and m.status='ACTIVE' order by m.member_type desc,u.display_name",(rs,n)->new MemberView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)),groupId);}
    private MemberView member(long groupId,long userId){return jdbc.queryForObject("select m.user_id,u.display_name,u.username,m.member_type from collab_group_member m join iam_user u on u.id=m.user_id where m.group_id=? and m.user_id=?",(rs,n)->new MemberView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)),groupId,userId);}
    private List<RoleView> roleViews(long groupId){return jdbc.query("select role_key from collab_group_role where group_id=? and status='ACTIVE' order by role_name",(rs,n)->role(groupId,rs.getString(1)),groupId);}
    private RoleView role(long groupId,String roleKey){Map<String,Object> r=roleRow(groupId,roleKey);List<MemberView> members=jdbc.query("select u.id,u.display_name,u.username from collab_group_role_member rm join iam_user u on u.id=rm.user_id join collab_group_member gm on gm.group_id=? and gm.user_id=u.id and gm.status='ACTIVE' where rm.role_id=? and u.status='ACTIVE' order by u.display_name",(rs,n)->new MemberView(rs.getLong(1),rs.getString(2),rs.getString(3),"MEMBER"),groupId,r.get("id"));return new RoleView((String)r.get("role_key"),(String)r.get("role_name"),members,members.stream().map(MemberView::userId).toList());}
    private Map<String,Object> roleRow(long groupId,String roleKey){if(roleKey==null)return Map.of();return jdbc.query("select id,role_key,role_name from collab_group_role where group_id=? and role_key=? and status='ACTIVE'",rs->rs.next()?row(rs):null,groupId,roleKey)==null?notFound("COLLAB_GROUP_ROLE_NOT_FOUND","Role not found"):jdbc.queryForMap("select id,role_key,role_name from collab_group_role where group_id=? and role_key=? and status='ACTIVE'",groupId,roleKey);}
    private Map<String,Object> row(ResultSet rs)throws SQLException{return Map.of("id",rs.getLong("id"),"role_key",rs.getString("role_key"),"role_name",rs.getString("role_name"));}
    private List<StageTemplateView> templateViews(long groupId){return STAGES.stream().map(stage->template(groupId,stage)).toList();}
    private StageTemplateView template(long groupId,String stage){
        Map<String,Object> row=jdbc.query("select t.default_included,t.version_no,ro.role_key owner_role_key,ro.role_name owner_role_name from collab_group_stage_template t left join collab_group_role ro on ro.id=t.owner_role_id where t.group_id=? and t.stage_key=?",rs->rs.next()?Map.of("included",rs.getBoolean("default_included"),"version",rs.getInt("version_no"),"ownerKey",Objects.toString(rs.getObject("owner_role_key"),""),"ownerName",Objects.toString(rs.getObject("owner_role_name"),"")):null,groupId,stage);
        if(row==null)return new StageTemplateView(stage,stageOrder(stage),stageDef(stage).title(),true,null,null,null,null,List.of(),List.of(),0,List.of(),List.of());
        String ownerKey=((String)row.get("ownerKey")).isBlank()?null:(String)row.get("ownerKey"),ownerName=((String)row.get("ownerName")).isBlank()?null:(String)row.get("ownerName");
        List<Map<String,Object>> reviewerRoles=jdbc.query("select r.role_key,r.role_name from collab_group_stage_reviewer_role sr join collab_group_role r on r.id=sr.role_id and r.status='ACTIVE' where sr.group_id=? and sr.stage_key=? order by r.role_name,r.role_key",(rs,n)->Map.of("key",rs.getString(1),"name",rs.getString(2)),groupId,stage);
        // Compatibility fallback for rows written before the multi-role table was populated.
        if(reviewerRoles.isEmpty()) reviewerRoles=jdbc.query("select r.role_key,r.role_name from collab_group_stage_template t join collab_group_role r on r.id=t.reviewer_role_id and r.status='ACTIVE' where t.group_id=? and t.stage_key=?",(rs,n)->Map.of("key",rs.getString(1),"name",rs.getString(2)),groupId,stage);
        List<String> reviewerKeys=reviewerRoles.stream().map(r->(String)r.get("key")).toList(),reviewerNames=reviewerRoles.stream().map(r->(String)r.get("name")).toList();
        List<Long> reviewers=reviewerKeys.stream().flatMap(k->roleUsers(groupId,k).stream()).distinct().sorted().toList();
        return new StageTemplateView(stage,stageOrder(stage),stageDef(stage).title(),(Boolean)row.get("included"),ownerKey,ownerName,reviewerKeys.isEmpty()?null:reviewerKeys.get(0),reviewerNames.isEmpty()?null:reviewerNames.get(0),reviewerKeys,reviewerNames,((Number)row.get("version")).intValue(),ownerKey==null?List.of():roleUsers(groupId,ownerKey),reviewers);
    }
    private List<Long> roleUsers(long groupId,String roleKey){if(roleKey==null)return List.of();return jdbc.query("select rm.user_id from collab_group_role_member rm join collab_group_role r on r.id=rm.role_id join collab_group_member gm on gm.group_id=r.group_id and gm.user_id=rm.user_id and gm.status='ACTIVE' join iam_user u on u.id=rm.user_id and u.status='ACTIVE' where r.group_id=? and r.role_key=? and r.status='ACTIVE' order by rm.user_id",(rs,n)->rs.getLong(1),groupId,roleKey);}
    private Long roleId(long groupId,String key){if(key==null||key.isBlank())return null;Map<String,Object> r=roleRow(groupId,key);return ((Number)r.get("id")).longValue();}
    private Map<String,Object> binding(long id){return jdbc.queryForMap("select wiki_space_id,wiki_root_node_token,wiki_root_url,wiki_group_node_token,chat_id,status,last_error from collab_group_feishu_binding where group_id=?",id);}
    private BindingView bindingView(long id){Map<String,Object>b=binding(id);return new BindingView((String)b.get("status"),(String)b.get("wiki_space_id"),(String)b.get("wiki_root_node_token"),(String)b.get("wiki_group_node_token"),(String)b.get("chat_id"),(String)b.get("wiki_root_url"),(String)b.get("last_error"));}
    private Map<String,Object> groupRow(String key){return jdbc.query("select g.*,t.team_name,owner.display_name owner_name from collab_group g join org_team t on t.id=g.home_team_id join iam_user owner on owner.id=g.owner_user_id where g.group_key=?",rs->rs.next()?groupMap(rs):null,key)==null?notFound("COLLAB_GROUP_NOT_FOUND","Project group not found"):jdbc.queryForMap("select g.*,t.team_name,owner.display_name owner_name from collab_group g join org_team t on t.id=g.home_team_id join iam_user owner on owner.id=g.owner_user_id where g.group_key=?",key);}
    private Map<String,Object> groupRowForUpdate(String key){Map<String,Object> row=groupRow(key);jdbc.queryForObject("select id from collab_group where id=? for update",Long.class,num(row,"id"));return groupRow(key);}
    private Map<String,Object> groupMap(ResultSet rs)throws SQLException{Map<String,Object> m=new HashMap<>();for(String k:List.of("id","home_team_id","owner_user_id","template_version","version_no"))m.put(k,rs.getLong(k));for(String k:List.of("group_key","name","description","team_name","owner_name","gitlab_root_url","gitlab_root_normalized","asdm_repository_url","status"))m.put(k,rs.getString(k));m.put("legacy_import",rs.getBoolean("legacy_import"));m.put("published_at",rs.getTimestamp("published_at"));return m;}
    private void requireGroupView(Map<String,Object> g,long actor){if(isAdmin(actor)||Objects.equals(num(g,"owner_user_id"),actor))return;Integer n=jdbc.queryForObject("select count(*) from collab_group_member m join iam_user u on u.id=m.user_id where m.group_id=? and m.user_id=? and m.status='ACTIVE' and u.status='ACTIVE' and ?='PUBLISHED'",Integer.class,num(g,"id"),actor,g.get("status"));if(n==null||n==0)throw error("COLLAB_GROUP_NOT_FOUND","Project group not found",HttpStatus.NOT_FOUND);}
    private void requireGroupMembers(Map<String,Object> g,long actor){requireGroupView(g,actor);if(!"PUBLISHED".equals(g.get("status"))&&!isAdmin(actor)&&!Objects.equals(num(g,"owner_user_id"),actor))throw error("COLLAB_GROUP_NOT_PUBLISHED","Project group is not published",HttpStatus.CONFLICT);}
    private void requireOwner(Map<String,Object> g,long actor){if(!Objects.equals(num(g,"owner_user_id"),actor))throw error("COLLAB_GROUP_OWNER_REQUIRED","Project group owner is required",HttpStatus.FORBIDDEN);}
    private boolean isAdmin(long actor){Integer n=jdbc.queryForObject("select count(*) from iam_user_role ur join iam_role r on r.id=ur.role_id where ur.user_id=? and r.role_key='ADMIN' and r.status='ACTIVE'",Integer.class,actor);return n!=null&&n>0;}
    private boolean isProjectManagerRoleMember(long group,long actor){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from collab_group_role_member rm join collab_group_role r on r.id=rm.role_id where r.group_id=? and r.status='ACTIVE' and lower(r.role_name)='项目经理' and rm.user_id=?)",Boolean.class,group,actor));}
    private void ensureUniqueRole(long group,String name,Long except){Integer n=jdbc.queryForObject("select count(*) from collab_group_role where group_id=? and lower(role_name)=lower(?) and (? is null or id<>?)",Integer.class,group,name,except,except);if(n!=null&&n>0)throw error("COLLAB_GROUP_ROLE_NAME_EXISTS","Role name must be unique within the project group",HttpStatus.CONFLICT);}
    private void templateChanged(Map<String,Object> g,long actor,Map<String,?> change){long id=num(g,"id");jdbc.update("update collab_group set template_version=template_version+1,version_no=version_no+1,time_updated=now(3) where id=?",id);int v=jdbc.queryForObject("select template_version from collab_group where id=?",Integer.class,id);audit(id,v,actor,change);}
    private void audit(long group,int version,long actor,Object change){jdbc.update("insert into collab_group_template_audit(group_id,version_no,actor_id,changed_json,time_created) values(?,?,?,?,now(3))",group,version,actor,json(change));}
    private void validStage(String key){if(!STAGES.contains(key))throw error("COLLAB_STAGE_NOT_FOUND","Stage not found",HttpStatus.NOT_FOUND);}
    private List<String> canonicalRoleKeys(List<String> keys,String legacyKey){LinkedHashSet<String> result=new LinkedHashSet<>();if(keys!=null)for(String key:keys)if(key!=null&&!key.isBlank())result.add(key.trim());if(result.isEmpty()&&legacyKey!=null&&!legacyKey.isBlank())result.add(legacyKey.trim());return List.copyOf(result);}
    private int stageOrder(String stage){return jdbc.queryForObject("select stage_order from collab_stage_definition where stage_key=? and active=1",Integer.class,stage);}
    private StageDef stageDef(String stage){return jdbc.queryForObject("select stage_order,title,artifact_kind,git_required from collab_stage_definition where stage_key=? and active=1",(rs,n)->new StageDef(rs.getInt(1),rs.getString(2),rs.getString(3),rs.getBoolean(4)),stage);}
    private int stageGroupStart(int order){return order==4?3:order==6?5:order;}
    private int stageGroupEnd(int start){return start==3||start==5?start+1:start;}
    private boolean requiresReviewer(String level,String stage){return "L1".equals(level);}
    private Long firstOrNull(List<Long> xs){return xs.isEmpty()?null:xs.get(0);}
    private String normalizeGitlabRoot(String input){String raw=required(input,"COLLAB_GROUP_GITLAB_ROOT_REQUIRED","GitLab root URL is required",2048);try{URI uri=URI.create(raw);String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);String path=uri.getPath()==null?"":uri.getPath().replaceAll("/+$","");if(!"https".equalsIgnoreCase(uri.getScheme())||!host.equals("gitlab.it.taikang.com")||path.isBlank()||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException();return "https://"+host+path;}catch(RuntimeException e){throw error("COLLAB_GROUP_GITLAB_ROOT_INVALID","GitLab root must be an HTTPS group/subgroup URL on gitlab.it.taikang.com without credentials or query parameters",HttpStatus.BAD_REQUEST);}}
    private String normalizeAsdmRepository(String input){if(asdmConfiguration==null)throw error("ASDM_MODULE_DISABLED","ASDM GitLab integration is unavailable",HttpStatus.SERVICE_UNAVAILABLE);return asdmConfiguration.normalizeRepositoryUrl(input);}
    private boolean asdmConfigurationModuleEnabled(){return asdmConfiguration!=null&&asdmConfiguration.isEnabled();}
    private boolean validStoredRoot(String s){if(s==null)return false;try{return normalizeGitlabRoot(s).equals(s);}catch(RuntimeException e){return false;}}
    private void requireActive(long user){Integer n=jdbc.queryForObject("select count(*) from iam_user where id=? and status='ACTIVE'",Integer.class,user);if(n==null||n==0)throw error("COLLAB_USER_INACTIVE","An active user is required",HttpStatus.UNPROCESSABLE_ENTITY);}
    private String required(String s,String code,String message,int max){if(s==null||s.isBlank())throw error(code,message,HttpStatus.BAD_REQUEST);String v=s.trim();if(v.length()>max)throw error(code,message,HttpStatus.BAD_REQUEST);return v;}
    private String nullableTrim(String s){return s==null||s.isBlank()?null:s.trim();}
    private Long num(Map<String,Object> m,String k){Object o=m.get(k);return o==null?null:((Number)o).longValue();}
    private Instant instant(Object o){return o instanceof java.sql.Timestamp t?t.toInstant():null;}
    private String json(Object o){try{return mapper.writeValueAsString(o);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private BusinessException error(String code,String message,HttpStatus status){return new BusinessException(code,message,status);}
    private <T>T notFound(String code,String message){throw error(code,message,HttpStatus.NOT_FOUND);}

    public record CreateGroup(Long homeTeamId,String name,String description,String gitlabRootUrl,String asdmRepositoryUrl){public CreateGroup(Long homeTeamId,String name,String description,String gitlabRootUrl){this(homeTeamId,name,description,gitlabRootUrl,null);}}
    public record UpdateGroup(String name,String description,String gitlabRootUrl,int expectedVersion){}
    public record CreateRequirement(String name,String description,String approvalLevel,List<String> includedStageKeys,List<String> stagesWithoutApproval,int expectedTemplateVersion,String asdmBranch){public CreateRequirement(String name,String description,String approvalLevel,List<String> includedStageKeys,List<String> stagesWithoutApproval,int expectedTemplateVersion){this(name,description,approvalLevel,includedStageKeys,stagesWithoutApproval,expectedTemplateVersion,null);}public CreateRequirement(String name,String description,String approvalLevel,List<String> includedStageKeys,int expectedTemplateVersion){this(name,description,approvalLevel,includedStageKeys,List.of(),expectedTemplateVersion,null);}}
    public record StageTemplateCommand(String ownerRoleKey,String reviewerRoleKey,List<String> reviewerRoleKeys,boolean included,int expectedVersion){public StageTemplateCommand(String ownerRoleKey,String reviewerRoleKey,boolean included,int expectedVersion){this(ownerRoleKey,reviewerRoleKey,null,included,expectedVersion);}}
    public record TeamOption(long id,String name){}
    public record MemberView(long userId,String displayName,String username,String memberType){}
    public record RoleView(String roleKey,String roleName,List<MemberView> members,List<Long> userIds){}
    public record StageTemplateView(String stageKey,int order,String title,boolean included,String ownerRoleKey,String ownerRoleName,String reviewerRoleKey,String reviewerRoleName,List<String> reviewerRoleKeys,List<String> reviewerRoleNames,int versionNo,List<Long> ownerUserIds,List<Long> reviewerUserIds){}
    public record GroupView(String groupKey,long homeTeamId,String homeTeamName,String name,String description,long ownerUserId,String ownerName,String gitlabRootUrl,String asdmRepositoryUrl,String status,int templateVersion,int versionNo,boolean legacyImport,Instant publishedAt,List<MemberView> members,List<RoleView> roles,List<StageTemplateView> stages,int requirementCount,String wikiRootUrl,String feishuStatus){}
    public record Issue(String stageKey,String code,String message){}
    public record TemplatePreview(boolean valid,List<Issue> issues,List<Issue> warnings){}
    public record BindingView(String status,String wikiSpaceId,String wikiRootNodeToken,String wikiGroupNodeToken,String chatId,String url,String lastError){}
    public record RequirementView(String requirementKey,String groupKey,String name,String description,String approvalLevel,Integer inheritedTemplateVersion,String gitlabRootSnapshot,String asdmBranch,String asdmBindingStatus,int currentStageOrder,String status){}
    private record StageDef(int order,String title,String artifactKind,boolean gitRequired){}
}
