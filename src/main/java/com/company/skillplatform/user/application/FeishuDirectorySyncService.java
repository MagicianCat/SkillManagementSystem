package com.company.skillplatform.user.application;

import com.company.skillplatform.auth.infrastructure.FeishuProperties;
import com.company.skillplatform.user.domain.IdentityProviderType;
import com.company.skillplatform.user.domain.UserStatus;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.entity.OrgTeamEntity;
import com.company.skillplatform.user.infrastructure.entity.OrgTeamMemberEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.company.skillplatform.user.infrastructure.repository.OrgTeamMemberRepository;
import com.company.skillplatform.user.infrastructure.repository.OrgTeamRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.notification.application.NotificationService;
import org.springframework.scheduling.annotation.Async;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class FeishuDirectorySyncService {
    private static final Logger log = LoggerFactory.getLogger(FeishuDirectorySyncService.class);
    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();
    private final FeishuProperties config; private final ObjectMapper json; private final OrgTeamRepository teams;
    private final OrgTeamMemberRepository members; private final IamUserRepository users; private final NotificationService notifications;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    public FeishuDirectorySyncService(FeishuProperties c,ObjectMapper j,OrgTeamRepository t,OrgTeamMemberRepository m,IamUserRepository u,NotificationService n){config=c;json=j;teams=t;members=m;users=u;notifications=n;}

    @Async
    public void syncAsync(Long actorId){
        if(!running.compareAndSet(false,true)){notifications.directorySyncCompleted(actorId,false,"已有同步任务正在执行，请稍后查看通知中心");return;}
        try { SyncResult result=sync(); notifications.directorySyncCompleted(actorId,true,"共同步 "+result.teamCount()+" 个团队、"+result.memberCount()+" 条成员关系"); }
        catch(Exception error){ notifications.directorySyncCompleted(actorId,false,"同步失败："+error.getMessage()); }
        finally { running.set(false); }
    }

    public SyncResult sync(){
        enabled(); String token=tenantToken(); Instant now=Instant.now(); int[] counts={0,0};
        syncDepartment("0",null,token,now,counts,new HashSet<>());
        log.info("event=feishu.directory.sync.completed teamCount={} memberCount={}",counts[0],counts[1]);
        return new SyncResult(counts[0],counts[1],now);
    }
    private void syncDepartment(String externalParent,OrgTeamEntity parent,String token,Instant now,int[] counts,Set<String> visited){
        if(!visited.add(externalParent)){log.warn("event=feishu.directory.department.cycle_skipped departmentId={}",externalParent);return;}
        for(JsonNode response:pages("/contact/v3/departments?parent_department_id="+enc(externalParent)+"&page_size=50",token)) for(JsonNode item:response.path("data").path("items")){
            String id=text(item,"open_department_id",text(item,"department_id","")); if(id.isBlank())continue;
            OrgTeamEntity team=teams.findByProviderAndExternalDepartmentId("FEISHU",id).orElseGet(()->teams.save(new OrgTeamEntity("FEISHU",id,parent,text(item,"name",id))));
            team.sync(text(item,"name",id),parent,now); teams.save(team); counts[0]++;
            syncMembers(team,token,counts); syncDepartment(id,team,token,now,counts,visited);
        }
    }
    private void syncMembers(OrgTeamEntity team,String token,int[] counts){
        for(JsonNode response:pages("/contact/v3/users/find_by_department?department_id="+enc(team.getExternalDepartmentId())+"&department_id_type=open_department_id&user_id_type=open_id&page_size=50",token)) for(JsonNode item:response.path("data").path("items")){
            String userId=text(item,"user_id",""); String openId=text(item,"open_id",""); String unionId=text(item,"union_id","");
            String external=!openId.isBlank()?openId:(!userId.isBlank()?userId:unionId); if(external.isBlank())continue;
            String email=text(item,"email",null), name=text(item,"name",external), username="feishu_"+external;
            IamUserEntity user=( !openId.isBlank() ? users.findByFeishuOpenId(openId) : java.util.Optional.<IamUserEntity>empty())
                    .or(() -> !userId.isBlank() ? users.findByFeishuUserId(userId) : java.util.Optional.empty())
                    .or(() -> users.findByIdentityProviderAndExternalUserId(IdentityProviderType.FEISHU,external))
                    .orElseGet(()->users.save(new IamUserEntity(IdentityProviderType.FEISHU,external,username,null,name,email)));
            user.updateExternalProfile(username,name,email); user.updateFeishuIds(openId,unionId,userId); user.updateAvatar(text(item,"avatar_url",null)); user.changeStatus(UserStatus.ACTIVE); users.save(user);
            if(!members.existsByTeamIdAndUserId(team.getId(),user.getId()))members.save(new OrgTeamMemberEntity(team,user)); counts[1]++;
        }
    }
    private String tenantToken(){
        try{String body=json.writeValueAsString(Map.of("app_id",config.appId(),"app_secret",config.appSecret()));
            JsonNode data=json.readTree(send(config.apiBaseUrl()+"/auth/v3/tenant_access_token/internal",body,"POST",null));
            String token=data.path("tenant_access_token").asText(data.path("data").path("tenant_access_token").asText()); if(token.isBlank())throw error("FEISHU_TOKEN_INVALID","Feishu tenant token exchange failed",HttpStatus.BAD_GATEWAY);return token;
        }catch(BusinessException e){throw e;}catch(Exception e){throw error("FEISHU_SYNC_FAILED","Feishu directory sync failed",HttpStatus.BAD_GATEWAY);}
    }
    private JsonNode get(String path,String token){try{return json.readTree(send(config.apiBaseUrl()+path,null,"GET",token));}catch(Exception e){throw error("FEISHU_SYNC_FAILED","Feishu directory request failed",HttpStatus.BAD_GATEWAY);}}
    private List<JsonNode> pages(String firstPath,String token){List<JsonNode> result=new ArrayList<>();String path=firstPath;for(int page=0;page<100;page++){JsonNode response=get(path,token);result.add(response);JsonNode data=response.path("data");if(!data.path("has_more").asBoolean(false))break;String next=data.path("page_token").asText("");if(next.isBlank())break;path=firstPath+"&page_token="+enc(next);}return result;}
    private String send(String url,String body,String method,String token)throws Exception{var b=HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(20));if(token!=null)b.header("Authorization","Bearer "+token);if(body!=null)b.header("Content-Type","application/json");HttpRequest r="GET".equals(method)?b.GET().build():b.method(method,HttpRequest.BodyPublishers.ofString(body)).build();var response=http.send(r,HttpResponse.BodyHandlers.ofString());if(response.statusCode()/100!=2)throw new IllegalStateException("HTTP "+response.statusCode());return response.body();}
    private void enabled(){if(!config.enabled()||config.appId().isBlank()||config.appSecret().isBlank())throw error("FEISHU_NOT_CONFIGURED","Feishu is not configured",HttpStatus.SERVICE_UNAVAILABLE);}
    private BusinessException error(String c,String m,HttpStatus s){return new BusinessException(c,m,s);} private String text(JsonNode n,String key,String fallback){JsonNode v=n.get(key);return v==null||v.isNull()?fallback:v.asText(fallback);} private String enc(String value){return java.net.URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8);}
    public record SyncResult(int teamCount,int memberCount,Instant syncedAt){}
}
