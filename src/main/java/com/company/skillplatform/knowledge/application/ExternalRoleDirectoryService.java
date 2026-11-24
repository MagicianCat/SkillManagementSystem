package com.company.skillplatform.knowledge.application;

import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import static com.company.skillplatform.knowledge.application.StructuredCatalogService.*;

@Service
public class ExternalRoleDirectoryService {
    private final NamedParameterJdbcTemplate db;
    private final StructuredCatalogService catalogs;

    public ExternalRoleDirectoryService(NamedParameterJdbcTemplate db, StructuredCatalogService catalogs) {
        this.db=db; this.catalogs=catalogs;
    }

    public List<Name> mentions(long user,String scope,String normalizedQuery) {
        return find(user,scope,normalizedQuery,null,true);
    }

    public List<Name> lookup(long user,String scope,String normalizedName,String source) {
        return find(user,scope,normalizedName,source,false);
    }

    private List<Name> find(long user,String scope,String text,String source,boolean mention) {
        var args=new HashMap<String,Object>();
        args.put("text",text);args.put("source",source);args.put("teams",catalogs.teams(user,scope));
        args.put("like","%"+text.replace("!","!!").replace("%","!%").replace("_","!_")+"%");
        String match=mention?"LOCATE(c.term,:text)>0":"c.term LIKE :like ESCAPE '!'";
        return db.query("""
                WITH candidates AS (
                  SELECT id,LOWER(TRIM(role_name)) term FROM external_role
                  UNION SELECT id,LOWER(TRIM(external_role_id)) FROM external_role
                  UNION SELECT role_id,normalized_alias FROM external_role_alias WHERE status='ACTIVE'
                )
                SELECT DISTINCT er.id,er.role_name,er.source_system,er.external_role_id,c.term
                FROM candidates c JOIN external_role er ON er.id=c.id
                WHERE er.status='ACTIVE' AND (:source IS NULL OR er.source_system=:source)
                AND CHAR_LENGTH(c.term)>1 AND
                """+match+"""
                 AND (
                  EXISTS (SELECT 1 FROM external_role_resource rr JOIN external_resource r ON r.id=rr.resource_id
                """+WIKI+"""
                   WHERE rr.role_id=er.id AND d.status='ACTIVE'
                     AND ((d.team_id IN (:teams) AND EXISTS (SELECT 1 FROM wiki_document_revision rev
                              WHERE rev.id=d.current_revision_id AND rev.document_id=d.id))
                         OR (d.platform_visible=1 AND EXISTS (
                              SELECT 1 FROM wiki_platform_snapshot ps JOIN wiki_document_revision rev
                              ON rev.id=ps.approved_revision_id AND rev.document_id=d.id WHERE ps.document_id=d.id))))
                  OR EXISTS (SELECT 1 FROM external_role_resource rr JOIN external_resource r ON r.id=rr.resource_id
                """+SKILL+" WHERE rr.role_id=er.id AND "+SKILL_VISIBLE+"""
                  )
                  OR EXISTS (SELECT 1 FROM external_role_resource rr JOIN external_resource r ON r.id=rr.resource_id
                """+WIKI+ASDM+PLATFORM+" WHERE rr.role_id=er.id AND "+ASDM_VISIBLE+"""
                  )
                 ) ORDER BY er.id,c.term
                """,args,(rs,row)->new Name(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5)));
    }

    public record Name(long id,String name,String source,String externalId,String term) {}
}
