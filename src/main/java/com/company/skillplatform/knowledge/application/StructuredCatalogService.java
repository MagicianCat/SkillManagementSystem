package com.company.skillplatform.knowledge.application;

import com.company.skillplatform.external.AsdmToolImportService;
import com.company.skillplatform.wiki.application.WikiDocumentService;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class StructuredCatalogService {
    static final String RESOURCES = """
            FROM external_role er JOIN external_role_resource rr ON rr.role_id=er.id
            JOIN external_resource r ON r.id=rr.resource_id
            """;
    static final String WIKI = " JOIN wiki_document d ON "+ExternalWikiRoleResolver.SOURCE_MATCH;
    static final String PLATFORM = """
            JOIN wiki_platform_snapshot ps ON ps.document_id=d.id
            JOIN wiki_document_revision rev ON rev.id=ps.approved_revision_id AND rev.document_id=d.id
            """;
    static final String SKILL = """
            JOIN external_resource_version ev ON ev.resource_id=r.id
            JOIN external_import_record ir ON ir.resource_version_id=ev.id AND ir.target_type='SKILL' AND ir.result='SUCCESS'
            JOIN skill s ON s.id=ir.target_id AND s.latest_published_version_id=ir.draft_version_id
            JOIN skill_version sv ON sv.id=s.latest_published_version_id
            """;
    static final String SKILL_VISIBLE = """
            r.resource_type='SKILL' AND s.status='ACTIVE' AND s.scope_type='PLATFORM'
            AND sv.lifecycle_status IN ('PUBLISHED','DEPRECATED')
            AND EXISTS (SELECT 1 FROM skill_artifact artifact WHERE artifact.skill_version_id=sv.id AND artifact.status='AVAILABLE')
            """;
    static final String ASDM = """
            JOIN external_asdm_tool_asset a ON a.resource_id=r.id AND a.wiki_document_id=d.id
            """;
    static final String ASDM_VISIBLE = """
            r.resource_type='ASDM-TOOL' AND a.status='READY' AND d.status='ACTIVE' AND d.platform_visible=1
            AND REGEXP_LIKE(rev.markdown_content,CONCAT('/asdm-tools/download/',a.id,'([^0-9]|$)'))
            """;
    private final NamedParameterJdbcTemplate db;
    private final WikiDocumentService wiki;
    private final AsdmToolImportService asdm;
    public StructuredCatalogService(NamedParameterJdbcTemplate db, WikiDocumentService wiki, AsdmToolImportService asdm) {
        this.db=db; this.wiki=wiki; this.asdm=asdm;
    }

    public Page list(long user,String scope,long roleId,String kind,String category,int page,int size) {
        return list(user,scope,roleId,kind,category,page,size,null,null);
    }

    public Page list(long user,String scope,long roleId,String kind,String category,int page,int size,String keyword,Long parentId) {
        int limit=Math.min(50,Math.max(1,size));
        if(page<0)throw new IllegalArgumentException("Invalid catalog page");
        List<Long> teamIds=teams(user,scope);
        var args=new HashMap<String,Object>();
        args.put("role",roleId); args.put("category",category); args.put("teams",teamIds);
        String term=keyword==null?"":keyword.strip();
        if(term.length()>200)throw new IllegalArgumentException("Catalog keyword too long");
        args.put("keyword",term.isEmpty()?null:"%"+term.replace("!","!!").replace("%","!%").replace("_","!_")+"%");
        args.put("parent",parentId);
        args.put("exact",term);
        args.put("limit",limit); args.put("offset",(long)page*limit);
        String where=" WHERE er.status='ACTIVE' AND er.id=:role AND ";
        String query=switch(kind) {
            case "DOCUMENT" -> """
                    SELECT id,title,versionId,audience,category,revisionNo FROM (
                      SELECT entries.*, ROW_NUMBER() OVER(PARTITION BY id ORDER BY audience DESC) rn FROM (
                    """+"SELECT DISTINCT d.id,ps.approved_title title,ps.approved_revision_id versionId,'PLATFORM' audience,ps.approved_knowledge_category category,rev.revision_no revisionNo "
                    +RESOURCES+WIKI+PLATFORM+where+"r.resource_type='DOCUMENT' AND d.status='ACTIVE' AND d.platform_visible=1 AND (:category IS NULL OR ps.approved_knowledge_category=:category) AND (:parent IS NULL OR ps.approved_parent_id=:parent) AND (:keyword IS NULL OR ps.approved_title LIKE :keyword ESCAPE '!')"
                    +" UNION ALL SELECT DISTINCT d.id,d.title,d.current_revision_id versionId,'TEAM' audience,d.knowledge_category category,rev.revision_no revisionNo "
                    +RESOURCES+WIKI+" JOIN wiki_document_revision rev ON rev.id=d.current_revision_id AND rev.document_id=d.id "
                    +where+"r.resource_type='DOCUMENT' AND d.status='ACTIVE' AND d.team_id IN (:teams) AND (:category IS NULL OR d.knowledge_category=:category) AND (:parent IS NULL OR d.parent_id=:parent) AND (:keyword IS NULL OR d.title LIKE :keyword ESCAPE '!')"
                    +") entries) ranked WHERE rn=1";
            case "SKILL" -> "SELECT DISTINCT s.id,s.display_name title,sv.id versionId,s.skill_key skillKey "
                    +RESOURCES+SKILL+where+SKILL_VISIBLE;
            case "ASDM-TOOL" -> "SELECT DISTINCT a.id,ps.approved_title title,a.resource_version_id versionId,d.id wikiId "
                    +RESOURCES+WIKI+ASDM+PLATFORM+where+ASDM_VISIBLE+"""
                    AND (:keyword IS NULL OR r.name LIKE :keyword ESCAPE '!' OR ps.approved_title LIKE :keyword ESCAPE '!'
                          OR EXISTS (SELECT 1 FROM external_asdm_command_category cm WHERE cm.asset_id=a.id
                                      AND cm.mapping_status='ACTIVE' AND cm.tool_name=:exact))
                    """;
            default -> throw new IllegalArgumentException("Unsupported catalog kind");
        };
        long total=Objects.requireNonNull(db.queryForObject("SELECT COUNT(*) FROM ("+query+") catalog",args,Long.class));
        var rows=db.queryForList(query+" ORDER BY id LIMIT :limit OFFSET :offset",args);
        for(var row:rows){
            long id=((Number)row.get("id")).longValue();
            String url=switch(kind){
                case "DOCUMENT" -> wikiUrl(id,"PLATFORM".equals(row.get("audience")));
                case "SKILL" -> "/skills/"+row.get("skillKey");
                default -> wikiUrl(((Number)row.get("wikiId")).longValue(),true);
            };
            row.put("sourceUrl",url);
            if("ASDM-TOOL".equals(kind))row.put("downloadUrl",asdm.urlFor(id));
        }
        return new Page(total,page,limit,rows);
    }

    List<Long> teams(long user,String scope){
        if("PLATFORM_PUBLIC_ONLY".equals(scope))return List.of(-1L);
        var ids=wiki.visibleTeams(user).stream().map(WikiDocumentService.TeamView::id).toList();
        return ids.isEmpty()?List.of(-1L):ids;
    }

    private String wikiUrl(long id,boolean platform){return "/wiki?documentId="+id+(platform?"&audience=PLATFORM":"");}
    public record Page(long total,int page,int pageSize,List<Map<String,Object>> items) {}
}
