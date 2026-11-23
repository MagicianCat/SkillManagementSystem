package com.company.skillplatform.external;

import com.company.skillplatform.common.application.BusinessException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ExternalRoleWikiParentService {
    private static final String FROM = " FROM external_role er JOIN external_role_resource rr ON rr.role_id=er.id " +
            "JOIN external_resource r ON r.id=rr.resource_id " +
            "JOIN wiki_document d ON d.source_system=r.source_system AND d.source_resource_id=r.external_resource_id " +
            "JOIN wiki_platform_snapshot ps ON ps.document_id=d.id " +
            "JOIN wiki_document_revision rev ON rev.id=ps.approved_revision_id AND rev.document_id=d.id " +
            "LEFT JOIN org_team t ON t.id=d.team_id " +
            "WHERE er.id=? AND er.status='ACTIVE' AND d.status='ACTIVE' AND d.platform_visible=1 " +
            "AND ((r.resource_type='DOCUMENT' AND d.source_entry_path IS NOT NULL AND d.source_entry_path<>'ASDM-TOOL') " +
            "OR (r.resource_type='ASDM-TOOL' AND d.source_entry_path='ASDM-TOOL'))";
    private static final String SELECT = "SELECT DISTINCT d.id,ps.approved_title title,d.team_id,t.team_name,r.resource_type source_type," +
            "r.external_resource_id source_resource_id,d.source_entry_path,ps.published_at";
    private final JdbcTemplate db;

    public ExternalRoleWikiParentService(JdbcTemplate db) { this.db=db; }

    public Page list(long roleId,Long teamId,String keyword,int page,int size) {
        requireActive(roleId);
        if(page<0 || size<1 || size>50) throw bad("EXTERNAL_WIKI_PARENT_QUERY_INVALID","分页参数无效");
        String search=keyword==null?"":keyword.trim();
        if(search.length()>100) throw bad("EXTERNAL_WIKI_PARENT_QUERY_INVALID","关键词不能超过 100 字符");
        String filter=" AND d.team_id <=> ?"+(search.isEmpty()?"":" AND (ps.approved_title LIKE ? ESCAPE '!' OR r.name LIKE ? ESCAPE '!')");
        String like="%"+search.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        Object[] args=search.isEmpty()?new Object[]{roleId,teamId}:new Object[]{roleId,teamId,like,like};
        Long total=db.queryForObject("SELECT COUNT(DISTINCT d.id)"+FROM+filter,Long.class,args);
        Object[] pageArgs=java.util.Arrays.copyOf(args,args.length+2);pageArgs[args.length]=size;pageArgs[args.length+1]=(long)page*size;
        List<ParentOption> items=db.query(SELECT+FROM+filter+" ORDER BY ps.published_at DESC,d.id DESC LIMIT ? OFFSET ?",this::map,pageArgs);
        return new Page(items,page,size,total==null?0:total);
    }

    public List<ParentOption> listForCard(long roleId,int limitPlusOne) {
        requireActive(roleId);
        if(limitPlusOne<1) throw bad("EXTERNAL_WIKI_PARENT_QUERY_INVALID","候选数量无效");
        return db.query(SELECT+FROM+" ORDER BY ps.published_at DESC,d.id DESC LIMIT ?",this::map,roleId,limitPlusOne);
    }

    public ParentOption requireEligible(long roleId,Long teamId,long parentWikiId) {
        requireActive(roleId);
        if(parentWikiId<=0) throw bad("EXTERNAL_WIKI_PARENT_NOT_ELIGIBLE","父 Wiki 不属于当前角色的已发布资源");
        List<ParentOption> matches=db.query(SELECT+FROM+" AND d.id=? AND d.team_id <=> ?",this::map,roleId,parentWikiId,teamId);
        if(matches.isEmpty()) throw bad("EXTERNAL_WIKI_PARENT_NOT_ELIGIBLE","父 Wiki 不属于当前角色的已发布资源，或与所选发布范围不一致；请选择对应范围或根目录");
        return matches.get(0);
    }

    private void requireActive(long roleId) {
        Integer count=db.queryForObject("SELECT COUNT(*) FROM external_role WHERE id=? AND status='ACTIVE'",Integer.class,roleId);
        if(count==null || count==0) throw bad("EXTERNAL_ROLE_NOT_ACTIVE","外部角色不存在或已停用");
    }

    private ParentOption map(java.sql.ResultSet rs,int row) throws java.sql.SQLException {
        long id=rs.getLong("id");Long teamId=(Long)rs.getObject("team_id");
        String scope=teamId==null?"平台":rs.getString("team_name");
        if(scope==null) scope="团队 #"+teamId;
        String type=rs.getString("source_type"),path=rs.getString("source_entry_path"),title=rs.getString("title");
        String source="ASDM-TOOL".equals(type)?"ASDM 工具":"文档 ZIP："+path;
        return new ParentOption(id,title,teamId,scope,type,rs.getString("source_resource_id"),path,
                rs.getTimestamp("published_at").toLocalDateTime(),"["+scope+"] "+title+" · "+source+"（Wiki #"+id+"）");
    }

    private BusinessException bad(String code,String message) { return new BusinessException(code,message,HttpStatus.BAD_REQUEST); }
    public record ParentOption(long id,String title,Long teamId,String scopeLabel,String sourceType,String sourceResourceId,
                               String sourceEntryPath,LocalDateTime publishedAt,String label) {}
    public record Page(List<ParentOption> items,int page,int size,long total) {}
}
