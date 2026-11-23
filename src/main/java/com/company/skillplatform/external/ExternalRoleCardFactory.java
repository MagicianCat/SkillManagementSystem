package com.company.skillplatform.external;

import com.company.skillplatform.collabproject.application.CollabFeishuCardLayout;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ExternalRoleCardFactory {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ExternalResourceAdminService imports;
    private final AsdmToolImportService asdm;
    private final int limit;
    private final String webBase;

    public ExternalRoleCardFactory(JdbcTemplate db,ObjectMapper json,ExternalResourceAdminService imports,AsdmToolImportService asdm,
            @Value("${integration.external.role-card.max-wiki-options:80}") int limit,
            @Value("${feishu.bot-web-base-url:http://127.0.0.1:5173}") String webBase) {
        this.db=db;this.json=json;this.imports=imports;this.asdm=asdm;this.limit=Math.max(1,Math.min(limit,90));this.webBase=webBase.replaceAll("/$","");
    }

    public Map<String,Object> prepare(long eventId,Object stored) {
        try {
            Map<String,Object> snapshot;
            if(stored instanceof String raw && raw.trim().startsWith("[")) snapshot=new LinkedHashMap<>(Map.of("resources",json.readValue(raw,new TypeReference<List<Map<String,Object>>>(){})));
            else snapshot=json.readValue(String.valueOf(stored),new TypeReference<>(){});
            if(snapshot.containsKey("docs")) return snapshot;
            @SuppressWarnings("unchecked") List<Map<String,Object>> resources=(List<Map<String,Object>>)snapshot.get("resources");
            Map<String,Object> docs=new LinkedHashMap<>(); int index=0; boolean overflow=false;
            for(Map<String,Object> resource:resources) if("DOCUMENT".equals(resource.get("resourceType"))) {
                long version=number(resource.get("versionId"));
                try {
                    for(var entry:imports.documents(version)) {
                        if(index>=limit){overflow=true;break;}
                        docs.put("doc_"+String.format("%03d",++index),Map.of("resourceId",number(resource.get("resourceId")),"versionId",version,"path",entry.path(),"label",resource.get("name")+" / "+entry.path()));
                    }
                } catch(RuntimeException invalid){overflow=true;}
            }
            snapshot.put("docs",docs);snapshot.put("docsOverflow",overflow);
            return snapshot;
        } catch(Exception ex){throw new IllegalStateException("Invalid external role card snapshot",ex);}
    }

    public Map<String,Object> card(Map<String,Object> event,Map<String,Object> snapshot) {
        long eventId=number(event.get("id"));long roleId=number(event.get("role_id"));
        String status=String.valueOf(event.get("role_status"));
        List<Object> elements=new ArrayList<>();
        elements.add(markdown("**来源：** "+escape(event.get("source_system"))+"  \n**角色：** "+escape(event.get("role_name"))+"（"+escape(event.get("external_role_id"))+"）  \n**状态：** "+status));
        try {
            Map<?,?> summary=json.readValue(String.valueOf(event.get("change_summary_json")),Map.class);
            elements.add(markdown("本次推送：新增 "+summary.get("created")+" · 更新 "+summary.get("updated")+" · 未变化 "+summary.get("unchanged")+" · 失败 "+summary.get("failed")));
            if(summary.get("changes") instanceof List<?> changes && !changes.isEmpty()) {
                List<String> details=new ArrayList<>();
                for(Object item:changes) {
                    if(details.size()>=20) break;
                    if(!(item instanceof Map<?,?> change)) continue;
                    String changeStatus=switch(String.valueOf(change.get("status"))) {
                        case "CREATED" -> "新增"; case "UPDATED" -> "更新"; case "UNCHANGED" -> "未变化"; default -> "失败";
                    };
                    Object name=change.get("name")!=null?change.get("name"):change.get("resourceId");
                    String line="• "+changeStatus+"："+escape(name)+"（"+escape(change.get("type"))+" · "+escape(change.get("version"))+"）";
                    if("失败".equals(changeStatus) && change.get("errorCode")!=null) line+=" · "+escape(change.get("errorCode"));
                    details.add(line);
                }
                if(changes.size()>20) details.add("另有 "+(changes.size()-20)+" 项，请在后台查看");
                elements.add(markdown("**本次资源明细**  \n"+String.join("  \n",details)));
            }
        } catch(Exception ignored){}
        elements.add(markdown("事件 #"+eventId+" · "+String.valueOf(event.get("created_at"))));
        if(!"ACTIVE".equals(status)) return CollabFeishuCardLayout.card("研枢 · 外部角色资源更新","grey",elements);
        @SuppressWarnings("unchecked") List<Map<String,Object>> resources=(List<Map<String,Object>>)snapshot.get("resources");
        List<Map<String,Object>> skills=resources.stream().filter(r->"SKILL".equals(r.get("resourceType"))).toList();
        if(!skills.isEmpty()) {
            elements.add(markdown("**Skill（"+skills.size()+"）**  \n"+String.join("、",skills.stream().map(r->escape(r.get("name"))).toList())));
            elements.add(button("全部推送到全平台","SKILL_PUBLISH_ALL",eventId,null));
        }
        @SuppressWarnings("unchecked") Map<String,Map<String,Object>> docs=(Map<String,Map<String,Object>>)snapshot.get("docs");
        if(!docs.isEmpty()) {
            elements.add(markdown("**普通 Wiki（"+docs.size()+" 篇 Markdown）**"));
            if(Boolean.TRUE.equals(snapshot.get("docsOverflow"))) elements.add(markdown("文档选项较多，请[打开后台选择]("+webBase+"/admin/external-resources)"));
            else {
                List<Map<String,Object>> activeTeams=db.queryForList("SELECT id,team_name FROM org_team WHERE status='ACTIVE' ORDER BY team_name LIMIT 81");
                List<Object> form=new ArrayList<>();
                form.add(select("multi_select_static","docs","选择 Markdown",docs.entrySet().stream().map(e->option(String.valueOf(e.getValue().get("label")),e.getKey())).toList(),null));
                List<Map<String,Object>> scopes=new ArrayList<>();scopes.add(option("平台级 Wiki","platform"));
                if(activeTeams.size()<=80) activeTeams.forEach(t->scopes.add(option(String.valueOf(t.get("team_name")),"team:"+t.get("id"))));
                form.add(select("select_static","scope","Wiki 发布范围",scopes,"platform"));
                form.add(select("select_static","category","知识分类",List.of(option("Skill 与工具","SKILL_TOOL"),option("项目背景","PROJECT_CONTEXT"),option("内部知识","INTERNAL_KNOWLEDGE"),option("其他","OTHER")),"OTHER"));
                List<Map<String,Object>> parents=new ArrayList<>();parents.add(option("根目录 / 不指定","root"));
                List<Map<String,Object>> rows=db.queryForList("SELECT d.id,d.title,d.team_id,t.team_name FROM wiki_document d LEFT JOIN org_team t ON t.id=d.team_id WHERE d.status='ACTIVE' ORDER BY d.id DESC LIMIT ?",limit+1);
                if(rows.size()>limit) form.add(markdown("父 Wiki 较多，请[打开后台选择父节点]("+webBase+"/admin/external-resources)"));
                else {
                    for(var row:rows)parents.add(option("["+(row.get("team_id")==null?"平台":row.get("team_name"))+"] "+row.get("title"),"parent:"+row.get("id")));
                    form.add(select("select_static","parent","父 Wiki",parents,"root"));
                }
                var ready=imports.readiness(roleId);
                if(!ready.ready()) form.add(markdown("请先完成全部 Skill 推送，之后再关联。"));
                form.add(select("select_static","linkSkills","关联全部已发布 Skill",List.of(option("不关联 Skill","false"),option("关联当前角色全部已成功发布的 Skill","true")),"false"));
                form.add(Map.of("tag","button","text",plain("提交 Wiki 导入"),"type","primary","action_type","form_submit","name","external_role_wiki","value",Map.of("action","WIKI_IMPORT","eventId",eventId)));
                elements.add(Map.of("tag","form","name","external_role_wiki_form","elements",form));
            }
        }
        for(Map<String,Object> resource:resources) if("ASDM-TOOL".equals(resource.get("resourceType"))) {
            long version=number(resource.get("versionId"));
            String label="推送";
            try { var state=asdm.status(version);label=state.imported()&&state.wikiActive()?"同步工具集分类":state.wikiActive()?"更新平台 Wiki":"推送"; }
            catch(RuntimeException ignored){}
            elements.add(markdown("**ASDM 工具：** "+escape(resource.get("name"))+" · "+escape(resource.get("externalVersion"))));
            elements.add(button(label,"ASDM_PUBLISH",eventId,version));
        }
        return CollabFeishuCardLayout.card("研枢 · 外部角色资源更新","blue",elements);
    }

    private Map<String,Object> select(String tag,String name,String placeholder,List<Map<String,Object>> options,String initial) {
        Map<String,Object> value=new LinkedHashMap<>();value.put("tag",tag);value.put("name",name);value.put("placeholder",plain(placeholder));value.put("options",options);
        if(initial!=null)value.put("initial_option",initial);
        return value;
    }
    private Map<String,Object> button(String label,String action,long eventId,Long versionId) {
        Map<String,Object> value=new LinkedHashMap<>(Map.of("action",action,"eventId",eventId));if(versionId!=null)value.put("versionId",versionId);
        return Map.of("tag","button","text",plain(label),"type","primary","value",value);
    }
    private Map<String,Object> option(String text,String value){return Map.of("text",plain(text),"value",value);}
    private Map<String,Object> plain(String text){return Map.of("tag","plain_text","content",text);}
    private Map<String,Object> markdown(String text){return CollabFeishuCardLayout.markdown(text);}
    private String escape(Object value){return CollabFeishuCardLayout.escape(value==null?"":String.valueOf(value));}
    private long number(Object value){return ((Number)value).longValue();}
}
