package com.company.skillplatform.collabproject.asdm.application;

import com.company.skillplatform.collabproject.application.CollabFeishuChatGateway;
import com.company.skillplatform.collabproject.application.CollabFeishuCardLayout;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.auth.infrastructure.FeishuCardActionTagger;
import com.company.skillplatform.auth.infrastructure.FeishuCardProperties;
import com.lark.oapi.event.cardcallback.model.CallBackToast;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/** Feishu candidate card sender and authenticated publish callback router. */
@Service
public class CollabAsdmPublishCardService {
    private static final Logger log=LoggerFactory.getLogger(CollabAsdmPublishCardService.class);
    private static final String ACTION="collab_asdm_publish";
    private final JdbcTemplate jdbc;
    private final CollabFeishuChatGateway chats;
    private final CollabAsdmDetectionService detection;
    private final String webBaseUrl;
    private final FeishuCardActionTagger cardTagger;
    private final boolean callbacksEnabled;

    /** Compatibility constructor retained for callers that instantiate this service directly. */
    public CollabAsdmPublishCardService(JdbcTemplate jdbc,CollabFeishuChatGateway chats,CollabAsdmDetectionService detection,
            @Value("${collab.web-base-url:http://127.0.0.1:5173}") String webBaseUrl){
        this(jdbc, chats, detection, webBaseUrl, null, null);
    }

    @Autowired
    public CollabAsdmPublishCardService(JdbcTemplate jdbc,CollabFeishuChatGateway chats,CollabAsdmDetectionService detection,
            @Value("${collab.web-base-url:http://127.0.0.1:5173}") String webBaseUrl,
            FeishuCardProperties cardProperties, FeishuCardActionTagger cardTagger){
        this.jdbc=jdbc;this.chats=chats;this.detection=detection;this.webBaseUrl=webBaseUrl.replaceAll("/$", "");
        this.cardTagger=cardTagger;this.callbacksEnabled=cardProperties==null||cardProperties.isActionEnabled();
    }

    public boolean handles(P2CardActionTrigger event){
        if(event==null||event.getEvent()==null||event.getEvent().getAction()==null)return false;
        var action=event.getEvent().getAction();
        return action.getValue()!=null&&ACTION.equals(action.getValue().get("action"))
                || action.getName()!=null&&action.getName().startsWith(ACTION);
    }

    public P2CardActionTriggerResponse handle(P2CardActionTrigger event){
        P2CardActionTriggerResponse response=new P2CardActionTriggerResponse();CallBackToast toast=new CallBackToast();response.setToast(toast);
        try{
            if(!handles(event))return result(response,toast,"error","需求产物卡片无效");
            var callback=event.getEvent();var operator=callback.getOperator();
            String openId=operator==null?null:operator.getOpenId();String feishuUserId=operator==null?null:operator.getUserId();
            if(blank(openId)&&blank(feishuUserId))return result(response,toast,"error","无法识别飞书操作者，请打开研枢需求页面");
            List<Long> actors=jdbc.query("select id from iam_user where status='ACTIVE' and ((? is not null and feishu_open_id=?) or (? is not null and feishu_user_id=?)) limit 2",(rs,n)->rs.getLong(1),openId,openId,feishuUserId,feishuUserId);
            if(actors.size()!=1)return result(response,toast,"error","飞书账号尚未关联唯一的研枢用户");
            Map<String,Object> value=callback.getAction().getValue();
            if(value==null||!ACTION.equals(value.get("action")))return result(response,toast,"error","需求产物卡片已失效，请等待最新通知");
            long candidateId=parseLong(value.get("candidateId"));int revision=Math.toIntExact(parseLong(value.get("candidateRevision")));
            if(candidateId<1||revision<1)return result(response,toast,"error","需求产物卡片已失效，请等待最新通知");
            detection.requestPublish(candidateId,revision,actors.get(0));
            return result(response,toast,"success","已收到发布请求，正在校验 GitLab 内容");
        }catch(BusinessException denied){
            String message=switch(denied.getCode()){
                case "COLLAB_STAGE_OWNER_REQUIRED","COLLAB_PROJECT_MEMBER_REQUIRED"->"仅当前阶段负责人可以发布此产物";
                case "ASDM_CANDIDATE_STALE"->"此卡片已过期或已处理，请使用最新通知";
                case "ASDM_CANDIDATE_NOT_FOUND"->"候选产物不存在或已失效";
                default->"发布请求未受理，请打开研枢需求页面查看状态";
            };
            return result(response,toast,"error",message);
        }catch(Exception failure){log.warn("event=asdm.publish.card.failed error={}",failure.getClass().getSimpleName());return result(response,toast,"error","发布请求未受理，请稍后重试");}
    }

    /** Sends one Feishu interactive card for a persisted, debounced candidate revision. */
    public void sendCandidate(long candidateId,int revision){
        Map<String,Object> row=jdbc.query("select c.detected_revision,c.notified_revision,c.status,c.attempt_no,c.source_path,c.source_commit_sha,c.notify_after,p.project_key,p.name requirement_name,p.asdm_branch,g.id group_id,g.name group_name,b.chat_id,g.owner_user_id,s.id stage_id,s.stage_key,s.title stage_title "
                +"from collab_asdm_candidate c join collab_project p on p.id=c.requirement_id join collab_group g on g.id=p.group_id join collab_stage s on s.id=c.stage_id left join collab_feishu_binding b on b.project_id=p.id where c.id=?",rs->rs.next()?row(rs):null,candidateId);
        if(row==null||!"NOTIFIED".equals(row.get("status"))||!Integer.valueOf(revision).equals(row.get("notified_revision"))||!Integer.valueOf(revision).equals(row.get("detected_revision")))return;
        String chatId=(String)row.get("chat_id");if(blank(chatId))throw new IllegalStateException("Requirement Feishu chat is not ready");
        long stageId=((Number)row.get("stage_id")).longValue();
        String ownerRoster = CollabFeishuCardLayout.owners(jdbc, stageId, true);
        if (ownerRoster.isBlank()) throw new IllegalStateException("No active frozen requirement owner is available");
        String branch=escape(String.valueOf(row.get("asdm_branch")));String group=escape(String.valueOf(row.get("group_name")));String requirement=escape(String.valueOf(row.get("requirement_name")));
        String path=String.valueOf(row.get("source_path"));String commit=String.valueOf(row.get("source_commit_sha"));
        String repo=jdbc.queryForObject("select asdm_repository_url from collab_group where id=?",String.class,((Number)row.get("group_id")).longValue());
        // Use a commit permalink so branch names containing '/' remain unambiguous.
        String gitUrl=repo.replaceAll("/$","")+"/-/blob/"+encodePath(commit)+"/"+encodePath(path);
        String stageKey=String.valueOf(row.get("stage_key"));
        String stageTitle=escape(String.valueOf(row.get("stage_title")));
        List<Map<String,Object>> inventory=jdbc.queryForList("select association,selected,source_path from collab_asdm_candidate_file where candidate_id=? and candidate_revision=? order by source_path",candidateId,revision);
        long confirmed=inventory.stream().filter(f->Boolean.TRUE.equals(f.get("selected"))&&"CONFIRMED".equals(f.get("association"))).count();
        long likely=inventory.stream().filter(f->"LIKELY".equals(f.get("association"))).count();
        long uncertain=inventory.stream().filter(f->"UNCERTAIN".equals(f.get("association"))).count();
        long external=inventory.stream().filter(f->"EXTERNAL_REF".equals(f.get("association"))).count();
        String found = inventory.isEmpty() ? "产物：`requirement-doc.md`"
                : "自动确认关联：**" + confirmed + " 份**\n推断关联待确认：**" + likely
                    + " 份**\n归属不明：**" + uncertain + " 份**（不会自动发布）\n外部引用：**" + external + " 项**";
        List<Object> elements = new ArrayList<>();
        elements.add(CollabFeishuCardLayout.markdown("项目组：**" + group + "**\n需求：**" + requirement + "**"));
        elements.add(Map.of("tag", "hr"));
        elements.add(CollabFeishuCardLayout.markdown("**负责人：**\n" + ownerRoster));
        elements.add(Map.of("tag", "hr"));
        elements.add(CollabFeishuCardLayout.markdown("**产物检测结果**\n" + found));
        elements.add(CollabFeishuCardLayout.markdown("分支：`" + branch + "`\nGit commit：`"
                + commit.substring(0, Math.min(7, commit.length())) + "`\n\n本阶段关联文件已达到配置的静默期。"));
        elements.add(Map.of("tag", "hr"));
        elements.add(CollabFeishuCardLayout.markdown("发布后将逐份同步已纳入清单的文档及附件，全部成功后进入审批或自动流转。"));
        if ("TESTING".equals(stageKey)) elements.add(CollabFeishuCardLayout.markdown(
                "**测试阶段说明**\n此提醒只表示发现测试设计交付信号，不代表测试执行通过。"));
        Map<String,Object> callbackValue = new java.util.LinkedHashMap<>();
        callbackValue.put("action", ACTION); callbackValue.put("candidateId", candidateId);
        callbackValue.put("candidateRevision", revision); callbackValue.put("stageId", stageId);
        callbackValue.put("attemptNo", row.get("attempt_no"));
        String callbackName = ACTION+"|"+candidateId+"|"+revision;
        if (cardTagger != null) {
            callbackValue = cardTagger.attachEnvironmentToCallbackValue(callbackValue);
            callbackName = cardTagger.appendEnvironmentToActionName(callbackName);
        }
        Map<String,Object> publishButton=Map.of("tag","button","type","primary","element_id","collab_asdm_publish",
                "name",callbackName,"text",Map.of("tag","plain_text","content","发布当前产物"),
                "behaviors",List.of(Map.of("type","callback","value",callbackValue)));
        Map<String,Object> gitlabButton=Map.of("tag","button","type","default","text",Map.of("tag","plain_text","content","查看 GitLab"),
                "behaviors",List.of(Map.of("type","open_url","default_url",gitUrl,"pc_url",gitUrl,"ios_url",gitUrl,"android_url",gitUrl)));
        String listUrl=webBaseUrl+"/collab-requirements/"+row.get("project_key")+"/stages/"+stageKey;
        Map<String,Object> listButton=Map.of("tag","button","type","default","text",Map.of("tag","plain_text","content","查看产物清单"),
                "behaviors",List.of(Map.of("type","open_url","default_url",listUrl,"pc_url",listUrl,"ios_url",listUrl,"android_url",listUrl)));
        Map<String,Object> gitlabColumn=Map.of("tag","column","width","weighted","weight",1,"elements",List.of(gitlabButton));
        Map<String,Object> listColumn=Map.of("tag","column","width","weighted","weight",1,"elements",List.of(listButton));
        List<Map<String,Object>> columns = new ArrayList<>(List.of(listColumn, gitlabColumn));
        if (callbacksEnabled) columns.add(publishColumn(publishButton));
        elements.add(Map.of("tag","column_set","flex_mode","none","background_style","default","columns",columns));
        chats.sendInteractive(chatId,CollabFeishuCardLayout.card("产物待发布 · " + String.valueOf(row.get("stage_title")), "blue", elements),"asdm-candidate:"+candidateId+":revision:"+revision);
    }

    private P2CardActionTriggerResponse result(P2CardActionTriggerResponse response,CallBackToast toast,String type,String content){toast.setType(type);toast.setContent(content);return response;}
    private Map<String,Object> publishColumn(Map<String,Object> button){return Map.of("tag","column","width","weighted","weight",1,"elements",List.of(button));}
    private long parseLong(Object value){if(value instanceof Number number)return number.longValue();return Long.parseLong(String.valueOf(value));}
    private boolean blank(String value){return value==null||value.isBlank()||"null".equals(value);}
    private Map<String,Object> row(ResultSet rs)throws SQLException{Map<String,Object> result=new java.util.HashMap<>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++)result.put(rs.getMetaData().getColumnLabel(i).toLowerCase(java.util.Locale.ROOT),rs.getObject(i));return result;}
    private String encodePath(String value){String[] parts=value.split("/",-1);List<String> encoded=new ArrayList<>();for(String part:parts)encoded.add(URLEncoder.encode(part,StandardCharsets.UTF_8).replace("+","%20"));return String.join("/",encoded);}
    private String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("[","\\[").replace("]","\\]").replace("*","\\*").replace("_","\\_");}
}
