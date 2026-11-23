package com.company.skillplatform.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ExternalRoleCardFactoryTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ExternalResourceAdminService imports=mock(ExternalResourceAdminService.class);
    private final ExternalRoleCardFactory factory=new ExternalRoleCardFactory(db,new ObjectMapper(),imports,
            mock(AsdmToolImportService.class),80,"http://localhost:5173");

    @Test void mapsDocumentsAcrossArchivesToEventScopedValues() {
        when(imports.documents(11L)).thenReturn(List.of(new ExternalResourceAdminService.DocumentEntry("a.md",1)));
        when(imports.documents(22L)).thenReturn(List.of(new ExternalResourceAdminService.DocumentEntry("nested/b.md",1)));
        String resources="""
                [{"resourceId":1,"resourceType":"DOCUMENT","versionId":11,"name":"first"},
                 {"resourceId":2,"resourceType":"DOCUMENT","versionId":22,"name":"second"}]
                """;
        Map<String,Object> snapshot=factory.prepare(7,resources);
        @SuppressWarnings("unchecked") Map<String,Map<String,Object>> docs=(Map<String,Map<String,Object>>)snapshot.get("docs");
        assertThat(docs).containsKeys("doc_001","doc_002");
        assertThat(docs.get("doc_001")).containsEntry("resourceId",1L).containsEntry("path","a.md");
        assertThat(docs.get("doc_002")).containsEntry("resourceId",2L).containsEntry("path","nested/b.md");
    }

    @Test void inactiveRoleCardHasNoActions() {
        Map<String,Object> event=Map.of("id",7L,"role_id",3L,"source_system","source","external_role_id","role",
                "role_name","Archived","role_status","INACTIVE","change_summary_json","{}","created_at","now");
        Map<String,Object> snapshot=Map.of("resources",List.of(Map.of("resourceType","SKILL","name","skill")),"docs",Map.of());
        Map<String,Object> card=factory.card(event,snapshot);
        assertThat(card.toString()).doesNotContain("SKILL_PUBLISH_ALL","ASDM_PUBLISH","form_submit");
        verifyNoInteractions(db);
    }

    @Test void cardShowsWhichResourceChangedInThisPush() {
        String summary="""
                {"created":0,"updated":1,"unchanged":1,"failed":0,"changes":[
                  {"status":"UPDATED","resourceId":"skill-a","name":"发布核对 Skill","type":"SKILL","version":"1.0.2"},
                  {"status":"UNCHANGED","resourceId":"doc-a","name":"总纲文档","type":"DOCUMENT","version":"1.0.1"}]}
                """;
        Map<String,Object> event=Map.of("id",8L,"role_id",3L,"source_system","source","external_role_id","role",
                "role_name","联调角色","role_status","INACTIVE","change_summary_json",summary,"created_at","now");
        Map<String,Object> card=factory.card(event,Map.of("resources",List.of(),"docs",Map.of()));
        assertThat(card.toString()).contains("更新 1","未变化 1","更新：发布核对 Skill","未变化：总纲文档","1.0.2","1.0.1");
    }

    @Test void cardUsesOnlyCurrentRolePublishedWikiParents() {
        var parents=mock(ExternalRoleWikiParentService.class);
        factory.setRoleWikiParents(parents);
        when(parents.listForCard(3L,81)).thenReturn(List.of(new ExternalRoleWikiParentService.ParentOption(
                203L,"总纲",null,"平台","DOCUMENT","doc-1","总纲.md",java.time.LocalDateTime.now(),
                "[平台] 总纲 · 文档 ZIP：总纲.md（Wiki #203）")));
        when(imports.readiness(3L)).thenReturn(new ExternalResourceAdminService.Readiness(true,List.of(),List.of()));
        Map<String,Object> event=Map.of("id",8L,"role_id",3L,"source_system","source","external_role_id","role",
                "role_name","联调角色","role_status","ACTIVE","change_summary_json","{}","created_at","now");
        Map<String,Object> doc=Map.of("resourceId",1L,"versionId",11L,"path","总纲.md","label","总纲.md");
        Map<String,Object> card=factory.card(event,Map.of("resources",List.of(),"docs",Map.of("doc_001",doc)));
        assertThat(card.toString()).contains("parent:203","[平台] 总纲","form_submit")
                .doesNotContain("父 Wiki 较多","团队选项较多");
    }
}
