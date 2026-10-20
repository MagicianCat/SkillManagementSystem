package com.company.skillplatform.collabproject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.company.skillplatform.collabproject.application.CollabFeishuBindingService;
import com.company.skillplatform.collabproject.application.CollabFeishuOutbox;
import com.company.skillplatform.collabproject.application.CollabGitlabRootValidator;
import com.company.skillplatform.collabproject.application.CollabProjectAuthorizationService;
import com.company.skillplatform.collabproject.application.GroupFeishuOutbox;
import com.company.skillplatform.collabproject.flow.application.CollabProjectFlowService;
import com.company.skillplatform.collabproject.flow.interfaces.CollabProjectFlowController.ArtifactUpload;
import com.company.skillplatform.collabproject.flow.interfaces.CollabProjectFlowController.SubmissionRequest;
import com.company.skillplatform.collabproject.group.application.CollabGroupService;
import com.company.skillplatform.collabproject.application.CollabRequirementService;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL coverage for Group visibility, snapshots, actor freezing and GitLab validation.
 * Run with: mvn test -Dtest=CollabGroupLifecycleIT -Dit.collab.mysql=true
 */
@EnabledIfSystemProperty(named = "it.collab.mysql", matches = "true")
class CollabGroupLifecycleIT {
    private static final String SCHEMA = "collab_group_it";
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static CollabGroupService groups;
    private static CollabProjectAuthorizationService authorization;
    private static CollabRequirementService requirements;
    private static CollabProjectFlowService flow;
    private Long owner;
    private Long memberA;
    private Long memberB;
    private Long outsider;
    private Long team;

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
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        ObjectMapper mapper=new ObjectMapper();
        authorization=new CollabProjectAuthorizationService(jdbc);
        CollabFeishuOutbox projectOutbox=new CollabFeishuOutbox(jdbc,mapper);
        groups=new CollabGroupService(jdbc,mapper,mock(CollabFeishuBindingService.class),projectOutbox,new GroupFeishuOutbox(jdbc,mapper));
        requirements=new CollabRequirementService(jdbc,authorization,projectOutbox);
        flow=new CollabProjectFlowService(jdbc,mapper,authorization,projectOutbox,new CollabGitlabRootValidator());
    }

    @BeforeEach void resetAndSeed() throws SQLException {
        clearFixture();
        owner=insertUser("owner"); memberA=insertUser("member-a"); memberB=insertUser("member-b"); outsider=insertUser("outsider");
        team=insertTeam("team");
        for(Long user:List.of(owner,memberA,memberB)) addTeamMember(team,user);
        grantProjectManager(team,owner);
    }

    @AfterAll static void cleanTestData() throws SQLException { if(jdbc!=null) clearFixture(); }

    @Test void draftIsVisibleOnlyToOwnerAndInvalidGitLabRootsAreRejected() {
        assertThatThrownBy(()->inTx(()->groups.create(new CollabGroupService.CreateGroup(team,"Bad","", "https://gitlab.it.taikang.com.evil/team"),owner)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("GitLab root");
        assertThatThrownBy(()->inTx(()->groups.create(new CollabGroupService.CreateGroup(team,"Bad","", "http://gitlab.it.taikang.com/team"),owner)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("GitLab root");

        var group=createGroup("Draft group");
        assertThat(inTx(()->groups.list(owner))).extracting(CollabGroupService.GroupView::groupKey).contains(group.groupKey());
        assertThat(inTx(()->groups.list(outsider))).extracting(CollabGroupService.GroupView::groupKey).doesNotContain(group.groupKey());
        assertThatThrownBy(()->inTx(()->groups.get(group.groupKey(),outsider)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("not found");
    }

    @Test void templateRoleMembersPublishPreviewAndRequirementSnapshotsAreIsolated() {
        var group=createGroup("Lifecycle group");
        inTx(()->groups.addMember(group.groupKey(),owner,memberA));
        inTx(()->groups.addMember(group.groupKey(),owner,memberB));
        var ownerRole=inTx(()->groups.createRole(group.groupKey(),owner,"开发负责人"));
        inTx(()->groups.replaceRoleMembers(group.groupKey(),owner,ownerRole.roleKey(),List.of(owner,memberA)));
        var reviewerRole=inTx(()->groups.createRole(group.groupKey(),owner,"需求审批人")); // Deliberately unstaffed: warning only.
        var reviewerRoleA=inTx(()->groups.createRole(group.groupKey(),owner,"产品审批人 A"));
        var reviewerRoleB=inTx(()->groups.createRole(group.groupKey(),owner,"产品审批人 B"));
        inTx(()->groups.replaceRoleMembers(group.groupKey(),owner,reviewerRoleA.roleKey(),List.of(memberA,memberB)));
        inTx(()->groups.replaceRoleMembers(group.groupKey(),owner,reviewerRoleB.roleKey(),List.of(owner,memberB)));
        for(String stage:List.of("REQUIREMENT","PRODUCT","ARCHITECTURE_DESIGN","UI_DESIGN","FRONTEND_CODING","BACKEND_CODING","TESTING","ACCEPTANCE")){
            int expected=latestVersion(group.groupKey());
            boolean included="REQUIREMENT".equals(stage);
            boolean hasOwner="REQUIREMENT".equals(stage)||"PRODUCT".equals(stage);
            inTx(()->groups.setStageTemplate(group.groupKey(),owner,stage,new CollabGroupService.StageTemplateCommand(
                    hasOwner?ownerRole.roleKey():null,included?reviewerRole.roleKey():null,included,expected)));
        }
        int productVersion=latestVersion(group.groupKey());
        var productTemplate=inTx(()->groups.setStageTemplate(group.groupKey(),owner,"PRODUCT",new CollabGroupService.StageTemplateCommand(
                ownerRole.roleKey(),reviewerRoleA.roleKey(),List.of(reviewerRoleA.roleKey(),reviewerRoleB.roleKey()),false,productVersion)));
        assertThat(productTemplate.reviewerRoleKeys()).containsExactlyInAnyOrder(reviewerRoleA.roleKey(),reviewerRoleB.roleKey());
        assertThat(productTemplate.reviewerUserIds()).containsExactlyInAnyOrder(owner,memberA,memberB);
        setWikiReady(group.groupKey(),owner);
        var preview=inTx(()->groups.preview(group.groupKey(),owner));
        assertThat(preview.valid()).isTrue();
        assertThat(preview.warnings()).extracting(CollabGroupService.Issue::code).contains("STAGE_REVIEWER_ROLE_EMPTY");
        var published=inTx(()->groups.publish(group.groupKey(),owner,currentGroup(group.groupKey()).versionNo()));
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("select count(*) from collab_group_outbox where group_id=?",Integer.class,groupId(group.groupKey()))).isEqualTo(2);

        var l2Requirement=createRequirement(group.groupKey(),"L2 multiple reviewers","L2",List.of("PRODUCT"));
        assertThat(jdbc.queryForObject("select count(*) from collab_requirement_stage_reviewer_role rr join collab_stage s on s.id=rr.stage_id where s.project_id=(select id from collab_project where project_key=?) and s.stage_key='PRODUCT'",Integer.class,l2Requirement.requirementKey())).isEqualTo(2);
        assertThat(jdbc.query("select a.user_id from collab_requirement_stage_actor a join collab_stage s on s.id=a.stage_id where s.project_id=(select id from collab_project where project_key=?) and s.stage_key='PRODUCT' and a.duty='REVIEWER'",(rs,n)->rs.getLong(1),l2Requirement.requirementKey())).containsExactlyInAnyOrder(owner,memberA,memberB);

        var reqA=createRequirement(group.groupKey(),"Requirement A","L3",List.of("REQUIREMENT","PRODUCT"));
        var roleA=inTx(()->requirements.roles(reqA.requirementKey(),owner));
        var productA=stage(roleA,"PRODUCT");
        assertThat(productA.ownerUserIds()).containsExactlyInAnyOrder(owner,memberA);
        assertThat(productA.reviewerUserIds()).containsExactlyInAnyOrder(owner,memberA,memberB);
        assertThat(productA.actorsFrozen()).isFalse();

        inTx(()->groups.replaceRoleMembers(group.groupKey(),owner,ownerRole.roleKey(),List.of(owner,memberB)));
        var reqB=createRequirement(group.groupKey(),"Requirement B","L3",List.of("REQUIREMENT","PRODUCT"));
        var roleB=inTx(()->requirements.roles(reqB.requirementKey(),owner));
        assertThat(stage(roleB,"PRODUCT").ownerUserIds()).containsExactlyInAnyOrder(owner,memberB);
        assertThat(stage(inTx(()->requirements.roles(reqA.requirementKey(),owner)),"PRODUCT").ownerUserIds()).containsExactlyInAnyOrder(owner,memberA);

        // A waiting stage can be explicitly adjusted while preserving the frozen role binding.
        var adjusted=inTx(()->requirements.replaceActors(reqA.requirementKey(),"PRODUCT","OWNER",ownerRole.roleKey(),List.of(memberA),productA.versionNo(),owner));
        assertThat(adjusted.ownerUserIds()).containsExactly(memberA);
        assertThatThrownBy(()->inTx(()->requirements.replaceActors(reqA.requirementKey(),"REQUIREMENT","OWNER",ownerRole.roleKey(),List.of(memberA),0,owner)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("frozen");

        // Completing stage 1 activates and freezes stage 2 in the real workflow service.
        var requirementStage=requirementStage(reqA.requirementKey());
        String content="## Scope\nIntegration test";
        var uploaded=inTx(()->flow.upload(reqA.requirementKey(),"REQUIREMENT",new ArtifactUpload(requirementStage.stageId(),1,
                requirementStage.artifactKind(),requirementStage.title(),sha256(content),content,null),"group-it-upload",owner));
        inTx(()->flow.submit(reqA.requirementKey(),"REQUIREMENT",new SubmissionRequest(requirementStage.stageId(),1,
                uploaded.artifactVersionId(),(long)requirementStage.versionNo()),"group-it-submit",owner));
        var frozen=inTx(()->requirements.roles(reqA.requirementKey(),owner));
        assertThat(stage(frozen,"PRODUCT").actorsFrozen()).isTrue();
        assertThatThrownBy(()->inTx(()->requirements.replaceActors(reqA.requirementKey(),"PRODUCT","OWNER",ownerRole.roleKey(),List.of(owner),
                stage(frozen,"PRODUCT").versionNo(),owner))).isInstanceOf(BusinessException.class).hasMessageContaining("frozen");
        assertThat(jdbc.queryForObject("select count(*) from collab_outbox where project_id=? and event_type in ('STAGE_HANDOFF','CREATE_WIKI_FOLDER')",Integer.class,requirementId(reqA.requirementKey())))
                .isGreaterThanOrEqualTo(2);
    }

    private CollabGroupService.GroupView createGroup(String name){return inTx(()->groups.create(new CollabGroupService.CreateGroup(team,name,"integration", "https://gitlab.it.taikang.com/ai_coding/test-group"),owner));}
    private CollabGroupService.RequirementView createRequirement(String key,String name,String level,List<String> stages){return inTx(()->groups.createRequirement(key,owner,new CollabGroupService.CreateRequirement(name,"description",level,stages,latestVersion(key))));}
    private CollabGroupService.GroupView currentGroup(String key){return inTx(()->groups.get(key,owner));}
    private int latestVersion(String key){return jdbc.queryForObject("select template_version from collab_group where group_key=?",Integer.class,key);}
    private long groupId(String key){return jdbc.queryForObject("select id from collab_group where group_key=?",Long.class,key);}
    private long requirementId(String key){return jdbc.queryForObject("select id from collab_project where project_key=?",Long.class,key);}
    private StageExecution requirementStage(String key){return jdbc.queryForObject("select s.id,s.artifact_kind,s.title,s.attempt_no,s.version_no from collab_stage s join collab_project p on p.id=s.project_id where p.project_key=? and s.stage_key='REQUIREMENT'",(rs,n)->new StageExecution(rs.getLong("id"),rs.getString("artifact_kind"),rs.getString("title"),rs.getInt("attempt_no"),rs.getInt("version_no")),key);}
    private void setWikiReady(String key,long actor){long id=groupId(key);jdbc.update("update collab_group_feishu_binding set wiki_space_id='it-space',wiki_root_node_token='it-root',wiki_root_url='https://feishu.cn/wiki/it-root',wiki_group_node_token='it-group-node',publish_actor_user_id=?,status='ACTIVE' where group_id=?",actor,id);}
    private CollabRequirementService.StageActorsView stage(CollabRequirementService.RolesView roles,String key){return roles.stages().stream().filter(s->s.stageKey().equals(key)).findFirst().orElseThrow();}
    private Long insertUser(String key){jdbc.update("insert into iam_user(time_created,time_updated,identity_provider,external_user_id,username,display_name,status,version_no) values(now(3),now(3),'FEISHU',?,?,?,'ACTIVE',0)","collab-group-it-"+key,"collab-group-it-"+key,key);return jdbc.queryForObject("select id from iam_user where external_user_id=?",Long.class,"collab-group-it-"+key);}
    private Long insertTeam(String key){jdbc.update("insert into org_team(time_created,time_updated,provider,external_department_id,parent_id,team_name,status,version_no) values(now(3),now(3),'FEISHU',?,null,?,'ACTIVE',0)","collab-group-it-"+key,key);return jdbc.queryForObject("select id from org_team where external_department_id=?",Long.class,"collab-group-it-"+key);}
    private void addTeamMember(Long team,Long user){jdbc.update("insert into org_team_member(time_created,time_updated,team_id,user_id,membership_type) values(now(3),now(3),?,?,'MEMBER')",team,user);}
    private void grantProjectManager(Long team,Long user){jdbc.update("insert into iam_scoped_role_assignment(time_created,time_updated,user_id,role_key,scope_type,team_id,granted_by,version_no) values(now(3),now(3),?,'PROJECT_MANAGER','TEAM',?,?,0)",user,team,user);}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private <T>T inTx(Supplier<T> work){return tx.execute(status->work.get());}
    private record StageExecution(long stageId,String artifactKind,String title,int attemptNo,int versionNo){}

    private static void clearFixture() throws SQLException {
        try(Connection c=jdbc.getDataSource().getConnection();var s=c.createStatement()){
            s.execute("SET FOREIGN_KEY_CHECKS=0");
            for(String table:List.of("collab_group_outbox","collab_group_template_audit","collab_group_feishu_binding","collab_group_role_member","collab_group_stage_template","collab_group_role","collab_group_member","collab_requirement_stage_actor","collab_requirement_stage_role","collab_requirement_role","collab_feishu_publication","collab_outbox","collab_feishu_binding","collab_stage_event","collab_approval","collab_submission","collab_artifact_version","collab_artifact","collab_stage_additional_reviewer","collab_stage","collab_project_member","collab_project","collab_group"))s.execute("TRUNCATE TABLE "+table);
            s.execute("DELETE FROM iam_scoped_role_assignment WHERE user_id IN (SELECT id FROM iam_user WHERE external_user_id LIKE 'collab-group-it-%')");
            s.execute("DELETE FROM org_team_member WHERE user_id IN (SELECT id FROM iam_user WHERE external_user_id LIKE 'collab-group-it-%')");
            s.execute("DELETE FROM org_team WHERE external_department_id LIKE 'collab-group-it-%'");
            s.execute("DELETE FROM iam_user WHERE external_user_id LIKE 'collab-group-it-%'");
            s.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }
}
