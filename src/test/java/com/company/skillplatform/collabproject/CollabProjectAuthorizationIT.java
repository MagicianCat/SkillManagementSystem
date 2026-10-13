package com.company.skillplatform.collabproject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.skillplatform.collabproject.application.CollabFeishuOutbox;
import com.company.skillplatform.collabproject.application.CollabProjectAuthorizationService;
import com.company.skillplatform.collabproject.application.CollabProjectService;
import com.company.skillplatform.collabproject.flow.application.CollabProjectFlowService;
import com.company.skillplatform.collabproject.flow.interfaces.CollabProjectFlowController;
import com.company.skillplatform.collabproject.flow.interfaces.CollabProjectFlowController.ArtifactUpload;
import com.company.skillplatform.collabproject.flow.interfaces.CollabProjectFlowController.DecisionRequest;
import com.company.skillplatform.collabproject.flow.interfaces.CollabProjectFlowController.SubmissionRequest;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real MySQL integration checks for scoped project creation and live membership authorization.
 * Run with: mvn test -Dtest=CollabProjectAuthorizationIT -Dit.collab.mysql=true
 */
@EnabledIfSystemProperty(named = "it.collab.mysql", matches = "true")
class CollabProjectAuthorizationIT {
    private static final String SCHEMA = "collab_project_it";
    private static JdbcTemplate jdbc;
    private static CollabProjectService projects;
    private static CollabProjectAuthorizationService authorization;
    private static CollabProjectFlowService flow;
    private Long owner;
    private Long reviewer;
    private Long teamA;
    private Long teamB;

    @BeforeAll static void migrate() {
        String host=System.getProperty("it.collab.mysql.host","127.0.0.1");
        String port=System.getProperty("it.collab.mysql.port","3306");
        String user=System.getProperty("it.collab.mysql.user","sms_app");
        String password=System.getProperty("it.collab.mysql.password","SkillApp@2026");
        MysqlDataSource ds=new MysqlDataSource();
        ds.setUrl("jdbc:mysql://"+host+":"+port+"/"+SCHEMA+"?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false&createDatabaseIfNotExist=true");
        ds.setUser(user); ds.setPassword(password);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").baselineOnMigrate(true).load().migrate();
        jdbc=new JdbcTemplate(ds);
        ObjectMapper mapper=new ObjectMapper();
        authorization=new CollabProjectAuthorizationService(jdbc);
        CollabFeishuOutbox outbox=new CollabFeishuOutbox(jdbc,mapper);
        projects=new CollabProjectService(jdbc,mapper,authorization,outbox);
        flow=new CollabProjectFlowService(jdbc,mapper,authorization,outbox);
    }

    @BeforeEach void resetAndSeed() throws SQLException {
        clearFixture();
        owner=insertUser("owner"); reviewer=insertUser("reviewer");
        teamA=insertTeam("team-a"); teamB=insertTeam("team-b");
        addTeamMember(teamA,owner); addTeamMember(teamA,reviewer); addTeamMember(teamB,owner); addTeamMember(teamB,reviewer);
        grantProjectManager(teamA,owner);
    }

    @AfterAll static void cleanTestData() throws SQLException { clearFixture(); }

    private static void clearFixture() throws SQLException {
        try(Connection c=jdbc.getDataSource().getConnection();var s=c.createStatement()){
            s.execute("SET FOREIGN_KEY_CHECKS=0");
            for(String table:List.of("collab_outbox","collab_feishu_publication","collab_feishu_binding","collab_stage_event","collab_approval","collab_submission","collab_artifact_version","collab_artifact","collab_stage","collab_project_member","collab_project"))s.execute("TRUNCATE TABLE "+table);
            s.execute("DELETE FROM iam_scoped_role_assignment WHERE role_key='PROJECT_MANAGER' AND scope_type='TEAM'");
            s.execute("DELETE FROM org_team_member WHERE user_id IN (SELECT id FROM iam_user WHERE external_user_id LIKE 'collab-it-%')");
            s.execute("DELETE FROM org_team WHERE external_department_id LIKE 'collab-it-%'");
            s.execute("DELETE FROM iam_user WHERE external_user_id LIKE 'collab-it-%'");
            s.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

    @Test void creationRequiresExactHomeTeamScopeAndOptionsAreScoped() {
        assertThat(projects.creationOptions(owner,false)).extracting(CollabProjectService.TeamOption::id).containsExactly(teamA);
        assertThatThrownBy(()->projects.create(command(teamB,"L1",List.of(owner,reviewer)),owner,false))
                .isInstanceOf(BusinessException.class).hasMessageContaining("selected team");
        var created=projects.create(command(teamA,"L1",List.of(owner,reviewer)),owner,false);
        assertThat(created.homeTeamId()).isEqualTo(teamA);
        assertThat(created.stages()).hasSize(8);
    }

    @Test void ownerCanReviewOwnStagesInASingleMemberProject() {
        var source=command(teamA,"L1",List.of(owner));
        var selfReviewed=source.stageAssignments().stream()
                .map(a->new CollabProjectService.Assignment(a.stageKey(),owner,owner)).toList();
        var project=projects.create(new CollabProjectService.CreateCommand(teamA,"Solo project","","L1",selfReviewed,List.of(owner)),owner,false);
        var stage=projects.stages(project.projectKey(),owner).get(0);
        String content="## 需求范围\n单人项目\n## 验收标准\nOwner 可以审批自己的阶段";
        var artifact=flow.upload(project.projectKey(),"REQUIREMENT",
                new ArtifactUpload(stage.stageId(),1,"REQUIREMENT_DOCUMENT","需求分析",sha256(content),content,null),"solo-upload",owner);
        var submission=flow.submit(project.projectKey(),"REQUIREMENT",
                new SubmissionRequest(stage.stageId(),1,artifact.artifactVersionId(),(long)stage.versionNo()),"solo-submit",owner);
        assertThat(submission.submissionStatus()).isEqualTo("PENDING_APPROVAL");
        var decision=flow.decide(project.projectKey(),submission.submissionId(),new DecisionRequest("APPROVE","self review",0L),owner);
        assertThat(decision.submissionStatus()).isEqualTo("APPROVED");
    }

    @Test void projectAccessIsIsolatedAndRemovedMemberLosesAccessImmediately() {
        var first=projects.create(command(teamA,"L3",List.of(owner,reviewer)),owner,false);
        var second=projects.create(command(teamA,"L3",List.of(owner)),owner,false);
        assertThat(authorization.requireMemberOrThrow(first.projectKey(),reviewer).projectId()).isNotNull();
        assertThatThrownBy(()->authorization.requireMemberOrThrow(second.projectKey(),reviewer))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Project not found");
        projects.removeMember(first.projectKey(),owner,reviewer);
        assertThatThrownBy(()->authorization.requireMemberOrThrow(first.projectKey(),reviewer))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Project not found");
    }

    @Test void approvalLevelsSelectManualStagesAndUploadRetryIsIdempotent() {
        for(String level:List.of("L1","L2","L3")){
            var project=projects.create(command(teamA,level,List.of(owner,reviewer)),owner,false);
            var stage=projects.stages(project.projectKey(),owner).get(0);
            String content="## 需求范围\nMVP 范围\n## 验收标准\n通过验收";
            String hash=sha256(content);
            ArtifactUpload upload=new ArtifactUpload(stage.stageId(),1,"REQUIREMENT_DOCUMENT","需求分析",hash,content,null);
            var first=flow.upload(project.projectKey(),"REQUIREMENT",upload,"upload-"+level,owner);
            var retry=flow.upload(project.projectKey(),"REQUIREMENT",upload,"upload-"+level,owner);
            assertThat(retry.artifactVersionId()).isEqualTo(first.artifactVersionId());
            assertThatThrownBy(()->flow.upload(project.projectKey(),"REQUIREMENT",new ArtifactUpload(stage.stageId(),1,"REQUIREMENT_DOCUMENT","需求分析",sha256(content+"\nchanged"),content+"\nchanged",null),"upload-"+level,owner))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("different content");
            var alternate=flow.upload(project.projectKey(),"REQUIREMENT",new ArtifactUpload(stage.stageId(),1,"REQUIREMENT_DOCUMENT","需求分析 v2",sha256(content+"\nsecond"),content+"\nsecond",null),"upload-alt-"+level,owner);
            var submitted=flow.submit(project.projectKey(),"REQUIREMENT",new SubmissionRequest(stage.stageId(),1,first.artifactVersionId(),(long)stage.versionNo()),"submit-"+level,owner);
            var submitRetry=flow.submit(project.projectKey(),"REQUIREMENT",new SubmissionRequest(stage.stageId(),1,first.artifactVersionId(),(long)stage.versionNo()),"submit-"+level,owner);
            assertThat(submitRetry.submissionId()).isEqualTo(submitted.submissionId());
            assertThatThrownBy(()->flow.submit(project.projectKey(),"REQUIREMENT",new SubmissionRequest(stage.stageId(),1,alternate.artifactVersionId(),(long)stage.versionNo()),"submit-"+level,owner))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("different submission");
            boolean manual=level.equals("L1");
            assertThat(submitted.submissionStatus()).isEqualTo(manual?"PENDING_APPROVAL":"APPROVED");
            if(manual) flow.decide(project.projectKey(),submitted.submissionId(),new DecisionRequest("APPROVE","ok",0L),reviewer);
            assertThat(flow.upload(project.projectKey(),"REQUIREMENT",upload,"upload-"+level,owner).artifactVersionId()).isEqualTo(first.artifactVersionId());
            assertThat(projects.project(project.projectKey(),owner).currentStageOrder()).isEqualTo(2);

            if(level.equals("L2")){
                var product=projects.stages(project.projectKey(),owner).get(1);
                String prd="## 功能清单\n功能\n## 边界\n边界";
                var pArtifact=flow.upload(project.projectKey(),"PRODUCT",new ArtifactUpload(product.stageId(),1,"PRODUCT_REQUIREMENTS","PRD",sha256(prd),prd,null),"upload-product",owner);
                var pSubmission=flow.submit(project.projectKey(),"PRODUCT",new SubmissionRequest(product.stageId(),1,pArtifact.artifactVersionId(),(long)product.versionNo()),"submit-product",owner);
                assertThat(pSubmission.submissionStatus()).isEqualTo("PENDING_APPROVAL");
            }
        }
    }

    @Test void completedL3ProjectRemainsReadableAfterAcceptance() {
        var project=projects.create(command(teamA,"L3",List.of(owner)),owner,false);
        List<String> required=List.of(
                "需求范围\n验收标准",
                "功能清单\n边界",
                "接口\n组件影响",
                "页面\n交互",
                "实现\n变更",
                "实现\n变更",
                "测试范围\n缺陷结果",
                "验收结论\n遗留问题");
        List<String> stageKeys=List.of("REQUIREMENT","PRODUCT","ARCHITECTURE_DESIGN","UI_DESIGN","FRONTEND_CODING","BACKEND_CODING","TESTING","ACCEPTANCE");
        for(int i=0;i<stageKeys.size();i++){
            String stageKey=stageKeys.get(i);
            var stage=projects.stages(project.projectKey(),owner).get(i);
            String content="## 交付内容\n"+required.get(i);
            CollabProjectFlowController.GitEvidence git=stage.gitRequired()
                    ?new CollabProjectFlowController.GitEvidence("https://github.com/example/repo","https://github.com/example/repo/commit/0123456789abcdef")
                    :null;
            var artifact=flow.upload(project.projectKey(),stageKey,
                    new ArtifactUpload(stage.stageId(),stage.attemptNo(),stage.artifactKind(),stage.title(),sha256(content),content,git),
                    "l3-final-upload-"+stageKey,owner);
            var submission=flow.submit(project.projectKey(),stageKey,
                    new SubmissionRequest(stage.stageId(),stage.attemptNo(),artifact.artifactVersionId(),(long)stage.versionNo()),
                    "l3-final-submit-"+stageKey,owner);
            assertThat(submission.submissionStatus()).as(stageKey+" auto-completes").isEqualTo("APPROVED");
        }

        assertThat(projects.project(project.projectKey(),owner).status()).isEqualTo("COMPLETED");
        assertThat(projects.project(project.projectKey(),owner).currentStageOrder()).isEqualTo(8);
        assertThat(projects.list(owner)).extracting(CollabProjectService.ProjectView::projectKey).contains(project.projectKey());
        assertThat(flow.artifacts(project.projectKey(),owner)).hasSize(8);
        assertThat(flow.events(project.projectKey(),owner)).isNotEmpty();
        assertThat(flow.context(project.projectKey(),"ACCEPTANCE",false,owner).approvedArtifacts()).hasSize(7);
    }

    private CollabProjectService.CreateCommand command(Long team,String level,List<Long> members){
        List<CollabProjectService.Assignment> assignments=new ArrayList<>();
        for(String key:List.of("REQUIREMENT","PRODUCT","ARCHITECTURE_DESIGN","UI_DESIGN","FRONTEND_CODING","BACKEND_CODING","TESTING","ACCEPTANCE")){
            boolean manual="L1".equals(level)||"L2".equals(level)&&List.of("PRODUCT","FRONTEND_CODING","BACKEND_CODING").contains(key);
            assignments.add(new CollabProjectService.Assignment(key,owner,manual?reviewer:null));
        }
        return new CollabProjectService.CreateCommand(team,"Integration "+level,"",level,assignments,members);
    }
    private Long insertUser(String key){jdbc.update("insert into iam_user(time_created,time_updated,identity_provider,external_user_id,username,display_name,status,version_no) values(now(3),now(3),'FEISHU',?,?,?,'ACTIVE',0)","collab-it-"+key,"collab-it-"+key,key);return jdbc.queryForObject("select id from iam_user where external_user_id=?",Long.class,"collab-it-"+key);}
    private Long insertTeam(String key){jdbc.update("insert into org_team(time_created,time_updated,provider,external_department_id,parent_id,team_name,status,version_no) values(now(3),now(3),'FEISHU',?,null,?,'ACTIVE',0)","collab-it-"+key,key);return jdbc.queryForObject("select id from org_team where external_department_id=?",Long.class,"collab-it-"+key);}
    private void addTeamMember(Long team,Long user){jdbc.update("insert into org_team_member(time_created,time_updated,team_id,user_id,membership_type,status) values(now(3),now(3),?,?,'MEMBER','ACTIVE')",team,user);}
    private void grantProjectManager(Long team,Long user){jdbc.update("insert into iam_scoped_role_assignment(time_created,time_updated,user_id,role_key,scope_type,team_id,granted_by,version_no) values(now(3),now(3),?,'PROJECT_MANAGER','TEAM',?,?,0)",user,team,user);}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
}
