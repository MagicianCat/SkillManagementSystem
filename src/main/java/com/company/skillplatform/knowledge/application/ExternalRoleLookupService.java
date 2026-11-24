package com.company.skillplatform.knowledge.application;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.wiki.application.WikiDocumentService;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ExternalRoleLookupService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ExternalRoleLookupService.class);
    private static final String FROM = """
            FROM external_role er JOIN external_role_resource rr ON rr.role_id=er.id
            JOIN external_resource r ON r.id=rr.resource_id JOIN wiki_document d ON
            """ + ExternalWikiRoleResolver.SOURCE_MATCH + """
            WHERE er.status='ACTIVE' AND d.status='ACTIVE' AND d.current_revision_id IS NOT NULL
            AND ((d.platform_visible=1 AND EXISTS (
                SELECT 1 FROM wiki_platform_snapshot ps JOIN wiki_document_revision rev
                ON rev.id=ps.approved_revision_id AND rev.document_id=d.id WHERE ps.document_id=d.id))
              OR d.team_id IN (:teams))
            """;
    @org.springframework.beans.factory.annotation.Autowired private ExternalRoleDirectoryService directory;
    private final NamedParameterJdbcTemplate db;
    private final WikiDocumentService wiki;
    private final boolean enabled;
    public ExternalRoleLookupService(NamedParameterJdbcTemplate db, WikiDocumentService wiki,
            @Value("${knowledge.search.role-routing-enabled:false}") boolean enabled) {
        this.db=db; this.wiki=wiki; this.enabled=enabled;
    }

    public void requireReady() {
        if (!enabled || !Boolean.TRUE.equals(db.getJdbcTemplate().queryForObject("""
                SELECT EXISTS(SELECT 1 FROM knowledge_role_metadata_scan WHERE status='COMPLETED')
                   AND NOT EXISTS(SELECT 1 FROM knowledge_role_metadata_sync
                                  WHERE applied_generation=0 AND status NOT IN ('SUCCEEDED','WAIT_INDEX') AND document_id <=
                                    (SELECT MIN(high_watermark) FROM knowledge_role_metadata_scan WHERE status='COMPLETED'))
                """,Boolean.class))) throw bad("ROLE_SEARCH_NOT_READY","角色索引尚未就绪");
    }

    public List<Role> requireVisible(List<Long> ids,List<Long> teams) {
        requireReady();
        var roles = query(" AND er.id IN (:ids) ORDER BY er.id",Map.of("ids",ids),teams);
        if (roles.size()!=ids.size()) throw bad("KNOWLEDGE_ROLE_NOT_FOUND_OR_NOT_VISIBLE","所选角色不可用，请重新选择");
        return roles;
    }

    public Lookup lookup(long userId,String scope,String input,String source,int limit) {
        long started=System.nanoTime();
        String name=normalize(input);
        if(name.isEmpty()||name.length()>255) throw bad("KNOWLEDGE_ROLE_NAME_INVALID","请输入有效角色名称");
        var candidates=directory.lookup(userId,scope,name,source);
        var exact=candidates.stream().filter(item->normalize(item.term()).equals(name)).toList();
        var chosen=exact.isEmpty()?candidates:exact;
        var matches=chosen.stream().map(item->new Role(item.id(),item.name(),item.source(),item.externalId()))
                .distinct().limit(Math.max(2,Math.min(10,limit))).toList();
        if(!exact.isEmpty()&&matches.size()==1)return report("EXACT",matches,started);
        return report(matches.isEmpty()?"NOT_FOUND_OR_NOT_VISIBLE":"AMBIGUOUS",matches,started);
    }

    private Lookup report(String status,List<Role> matches,long started) {
        log.info("event=knowledge.role_lookup status={} matches={} durationMs={}",status,matches.size(),(System.nanoTime()-started)/1_000_000);
        return new Lookup(status,matches);
    }

    private static String normalize(String value) {
        return value==null?"":java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC)
                .strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
    }

    private List<Role> query(String extra, Map<String,?> params,List<Long> teams) {
        Map<String,Object> args=new HashMap<>(params);
        args.put("teams",teams.isEmpty()?List.of(-1L):teams);
        return db.query("SELECT DISTINCT er.id,er.role_name,er.source_system,er.external_role_id "+FROM+extra,args,
                (rs,row)->new Role(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)));
    }

    public boolean pending(List<Long> ids,List<Long> teams) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 "+FROM+"""
                AND er.id IN (:ids) AND EXISTS (SELECT 1 FROM knowledge_role_metadata_sync s
                    WHERE s.document_id=d.id AND s.status<>'SUCCEEDED'))
                """,Map.of("ids",ids,"teams",teams.isEmpty()?List.of(-1L):teams),Boolean.class));
    }

    static BusinessException bad(String code,String message) {return new BusinessException(code,message,HttpStatus.BAD_REQUEST);}
    public record Role(long id,String name,String sourceSystem,String externalRoleId) {}
    public record Lookup(String status,List<Role> candidates) {}
}
