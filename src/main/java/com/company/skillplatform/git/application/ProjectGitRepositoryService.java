package com.company.skillplatform.git.application;

import com.company.skillplatform.codegraph.application.WorkflowCodeGraphAppendService;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.git.domain.GitRemotePort;
import com.company.skillplatform.git.infrastructure.GitProperties;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectGitRepositoryService {
    private static final java.util.Set<String> CODING_STAGES=java.util.Set.of("BACKEND_CODING","FRONTEND_CODING");
    private final JdbcTemplate jdbc; private final GitRemotePort remote; private final GitProperties properties; private final GitStageWatchService watches;
    private final WorkflowCodeGraphAppendService appendService;
    public ProjectGitRepositoryService(JdbcTemplate jdbc,GitRemotePort remote,GitProperties properties,GitStageWatchService watches,
                                       @Lazy WorkflowCodeGraphAppendService appendService){
        this.jdbc=jdbc;this.remote=remote;this.properties=properties;this.watches=watches;this.appendService=appendService;
    }
    @Transactional(readOnly=true) public GitRemotePort.RepositoryResolution resolve(String projectKey,Long actor,String url){Long project=project(projectKey);member(project,actor);return remote.resolve(url);}
    @Transactional(readOnly=true) public List<RepositoryView> list(String projectKey,Long actor){Long project=project(projectKey);member(project,actor);return listProject(project);}
    @Transactional public List<RepositoryView> replace(String projectKey,Long actor,List<RepositoryCommand> commands){Long project=project(projectKey);member(project,actor);if(currentRun(project)!=null)throw error("GIT_REPOSITORIES_LOCKED","Workflow has started; append a repository instead",HttpStatus.CONFLICT);jdbc.update("delete s from project_git_repository_stage s join project_git_repository r on r.id=s.repository_id where r.project_id=?",project);jdbc.update("delete from project_git_repository where project_id=?",project);for(RepositoryCommand command:safe(commands))save(project,actor,command);return listProject(project);}
    @Transactional public List<RepositoryView> append(String projectKey,Long actor,RepositoryCommand command){
        Long project=project(projectKey);member(project,actor);
        Long repository=save(project,actor,command);
        Long run=currentRun(project);
        if(run!=null){
            Long snapshot=snapshotOne(run,repository,actor);
            watches.attachSnapshotToActiveStages(run,snapshot);
            // M7: if the workflow is RUNNING with an ACTIVE binding, kick off a REPO_APPEND
            // generation in the same transaction so freeze + queue are atomic with the
            // repository snapshot. PREPARING workflows are excluded — they will pick the
            // repository up during their own initial build.
            if(isRunningWithActiveBinding(run)){
                appendService.onRepositoriesAppended(run,actor,List.of(snapshot));
            }
        }
        return listProject(project);
    }
    @Transactional public void snapshotForRun(Long runId,Long projectId,Long actor){for(Long repository:jdbc.query("select id from project_git_repository where project_id=? and status='ACTIVE' order by id",(rs,n)->rs.getLong(1),projectId))snapshotOne(runId,repository,actor);}
    private boolean isRunningWithActiveBinding(Long run){
        String status=jdbc.queryForObject("select status from workflow_run where id=?",String.class,run);
        if(!"RUNNING".equals(status))return false;
        Integer n=jdbc.queryForObject("select count(*) from workflow_run_code_graph_binding where workflow_run_id=? and status='ACTIVE'",Integer.class,run);
        return n!=null&&n>0;
    }
    private Long save(Long project,Long actor,RepositoryCommand command){if(command==null||command.url()==null||command.branch()==null||command.branch().isBlank())throw error("GIT_REPOSITORY_INVALID","Repository URL and branch are required",HttpStatus.BAD_REQUEST);List<String> stages=safe(command.stageKeys()).stream().map(x->x.toUpperCase(Locale.ROOT)).distinct().toList();if(stages.isEmpty()||stages.stream().anyMatch(x->!CODING_STAGES.contains(x)))throw error("GIT_STAGE_BINDING_INVALID","Select backend coding, frontend coding, or both",HttpStatus.BAD_REQUEST);Integer count=jdbc.queryForObject("select count(*) from project_git_repository where project_id=?",Integer.class,project);if(count!=null&&count>=properties.getMaxRepositoriesPerProject())throw error("GIT_REPOSITORY_LIMIT","Project repository limit reached",HttpStatus.CONFLICT);var resolution=remote.resolve(command.url());var branch=remote.head(resolution.normalizedUrl(),command.branch());String display=command.displayName()==null||command.displayName().isBlank()?nameOf(resolution.repositoryPath()):command.displayName().trim();String mode="BASE_RELATIVE".equalsIgnoreCase(properties.getUrlMode())?"BASE_RELATIVE":"ABSOLUTE";try{jdbc.update("insert into project_git_repository(time_created,time_updated,project_id,display_name,locator_mode,repository_path,remote_url,normalized_url,default_branch,tracked_branch,status,last_validated_at,added_by) values(now(3),now(3),?,?,?,?,?,?,?,?,'ACTIVE',now(3),?)",project,display,mode,resolution.repositoryPath(),command.url().trim(),resolution.normalizedUrl(),resolution.defaultBranch(),command.branch(),actor);}catch(org.springframework.dao.DuplicateKeyException duplicate){throw error("GIT_REPOSITORY_EXISTS","This repository branch is already configured",HttpStatus.CONFLICT);}Long id=jdbc.queryForObject("select id from project_git_repository where project_id=? and normalized_url=? and tracked_branch=?",Long.class,project,resolution.normalizedUrl(),command.branch());for(String stage:stages)jdbc.update("insert ignore into project_git_repository_stage(time_created,repository_id,stage_key) values(now(3),?,?)",id,stage);return id;}
    private Long snapshotOne(Long run,Long repository,Long actor){jdbc.update("insert ignore into workflow_run_git_repository(time_created,workflow_run_id,project_git_repository_id,display_name,normalized_url,repository_path,tracked_branch,added_by,status) select now(3),?,id,display_name,normalized_url,repository_path,tracked_branch,?,'ACTIVE' from project_git_repository where id=?",run,actor,repository);Long snapshot=jdbc.queryForObject("select id from workflow_run_git_repository where workflow_run_id=? and project_git_repository_id=?",Long.class,run,repository);jdbc.update("insert ignore into workflow_run_git_repository_stage(workflow_run_git_repository_id,stage_key) select ?,stage_key from project_git_repository_stage where repository_id=?",snapshot,repository);return snapshot;}
    private List<RepositoryView> listProject(Long project){return jdbc.query("select id,display_name,locator_mode,repository_path,remote_url,normalized_url,default_branch,tracked_branch,status,last_validated_at,last_validation_error from project_git_repository where project_id=? order by id",(rs,n)->new RepositoryView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getTimestamp(10)==null?null:rs.getTimestamp(10).toInstant(),rs.getString(11),jdbc.query("select stage_key from project_git_repository_stage where repository_id=? order by stage_key",(x,i)->x.getString(1),rs.getLong(1))),project);}
    private Long project(String key){List<Long> ids=jdbc.query("select id from virtual_project where project_key=?",(rs,n)->rs.getLong(1),key);if(ids.isEmpty())throw error("PROJECT_NOT_FOUND","Project not found",HttpStatus.NOT_FOUND);return ids.get(0);} private Long currentRun(Long project){List<Long> ids=jdbc.query("select id from workflow_run where project_id=? order by id desc limit 1",(rs,n)->rs.getLong(1),project);return ids.isEmpty()?null:ids.get(0);} private void member(Long project,Long actor){Integer n=jdbc.queryForObject("select count(*) from virtual_project_member where project_id=? and user_id=? and status='ACTIVE' and membership_type in ('OWNER','MEMBER')",Integer.class,project,actor);if(n==null||n==0)throw error("PROJECT_ACCESS_FORBIDDEN","Project membership required",HttpStatus.FORBIDDEN);} private String nameOf(String path){String value=path.replaceAll("\\.git$","");int slash=value.lastIndexOf('/');return slash<0?value:value.substring(slash+1);} private <T> List<T> safe(List<T> value){return value==null?List.of():value;} private BusinessException error(String c,String m,HttpStatus s){return new BusinessException(c,m,s);}
    public record RepositoryCommand(String url,String displayName,String branch,List<String> stageKeys){}
    public record RepositoryView(Long id,String displayName,String locatorMode,String repositoryPath,String remoteUrl,String normalizedUrl,String defaultBranch,String trackedBranch,String status,java.time.Instant lastValidatedAt,String lastValidationError,List<String> stageKeys){}
}
