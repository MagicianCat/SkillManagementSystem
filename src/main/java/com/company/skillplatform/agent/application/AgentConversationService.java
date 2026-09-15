package com.company.skillplatform.agent.application;

import com.company.skillplatform.agent.infrastructure.entity.*;
import com.company.skillplatform.agent.infrastructure.repository.*;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

@Service
public class AgentConversationService {
    private final AgentSessionRepository sessions;private final AgentRunRepository runs;private final AgentMessageRepository messages;
    private final AgentRecommendationRepository recommendations;private final AgentRunService tokens;private final AgentRuntimeClient runtime;
    private final AgentEventHub events;private final AgentMcpAuditService mcpAudits;private final ExecutorService executor;private final ObjectMapper json;private final String runtimeVersion;private final String modelKey;
    private final AgentTargetContextResolver targetContextResolver; private final AgentFastPathService fastPath;
    @org.springframework.beans.factory.annotation.Autowired
    public AgentConversationService(AgentSessionRepository sessions,AgentRunRepository runs,AgentMessageRepository messages,
            AgentRecommendationRepository recommendations,AgentRunService tokens,AgentRuntimeClient runtime,AgentEventHub events,AgentMcpAuditService mcpAudits,
            ExecutorService executor,ObjectMapper json,@Value("${agent.runtime.version:local}")String runtimeVersion,
            @Value("${agent.runtime.model-key:deepseek-v4-pro}")String modelKey,AgentTargetContextResolver targetContextResolver,AgentFastPathService fastPath){this.sessions=sessions;this.runs=runs;this.messages=messages;this.recommendations=recommendations;this.tokens=tokens;this.runtime=runtime;this.events=events;this.mcpAudits=mcpAudits;this.executor=executor;this.json=json;this.runtimeVersion=runtimeVersion;this.modelKey=modelKey;this.targetContextResolver=targetContextResolver;this.fastPath=fastPath;}
    public AgentConversationService(AgentSessionRepository sessions,AgentRunRepository runs,AgentMessageRepository messages,
            AgentRecommendationRepository recommendations,AgentRunService tokens,AgentRuntimeClient runtime,AgentEventHub events,AgentMcpAuditService mcpAudits,
            ExecutorService executor,ObjectMapper json,String runtimeVersion,String modelKey,AgentTargetContextResolver targetContextResolver){this(sessions,runs,messages,recommendations,tokens,runtime,events,mcpAudits,executor,json,runtimeVersion,modelKey,targetContextResolver,null);}

    public List<ProfileView> profiles(){return List.of(new ProfileView("skill-advisor","研途助手","研发全流程助手：推荐平台 Skill、回答有权限访问的公司资料问题，并支持研发基础知识问答",List.of("SKILL_RECOMMENDATION","COMPANY_KNOWLEDGE_QA","ENGINEERING_KNOWLEDGE_QA")));}
    @Transactional public SessionView create(Long owner,String profile,String platform,String osType){
        return create(owner, profile, platform, osType, "WEB", "USER_VISIBLE");
    }
    @Transactional public SessionView create(Long owner,String profile,String platform,String osType,String sourceChannel,String knowledgeScope){
        if(!"skill-advisor".equals(profile))throw error("AGENT_PROFILE_NOT_FOUND","Unknown agent profile",HttpStatus.NOT_FOUND);
        return view(sessions.save(new AgentSessionEntity(owner,profile,platform,osType,sourceChannel,knowledgeScope)),List.of(),null,null);
    }
    @Transactional(readOnly=true) public List<SessionSummary> list(Long owner){return sessions.findByOwnerUserIdAndStatusNotOrderByLastMessageAtDescTimeCreatedDesc(owner,"DELETED").stream().map(this::summary).toList();}
    @Transactional(readOnly=true) public SessionView get(String key,Long owner){AgentSessionEntity session=requireSession(key,owner);AgentRunEntity latest=runs.findFirstBySessionOrderByRunNoDesc(session).orElse(null);return view(session,messages.findBySessionOrderBySequenceNoAsc(session),latest,recommendation(latest));}
    @Transactional public void close(String key,Long owner){AgentSessionEntity session=requireSession(key,owner);runs.findFirstBySessionAndStatusInOrderByRunNoDesc(session,List.of("PENDING","RUNNING")).ifPresent(run->cancel(run.getRunKey(),owner));session.softDelete();sessions.save(session);}
    @Transactional public SessionView updateContext(String key,Long owner,String platform,String osType){AgentSessionEntity session=requireSession(key,owner);if(runs.findFirstBySessionAndStatusInOrderByRunNoDesc(session,List.of("PENDING","RUNNING")).isPresent())throw error("AGENT_RUN_ACTIVE","The session already has an active run",HttpStatus.CONFLICT);session.updateContext(blankToNull(platform),blankToNull(osType));sessions.save(session);return get(key,owner);}
    @Transactional public AcceptedRun send(String key,Long owner,String content){
        return send(key,owner,content,null,null,false);
    }
    @Transactional public AcceptedRun send(String key,Long owner,String content,String platform,String osType,boolean contextPresent){
        AgentSessionEntity session=requireSession(key,owner);if(!"ACTIVE".equals(session.getStatus()))throw error("AGENT_SESSION_CLOSED","Agent session is closed",HttpStatus.CONFLICT);
        if(content==null||content.isBlank()||content.length()>10000)throw error("AGENT_MESSAGE_INVALID","Message must contain 1..10000 characters",HttpStatus.BAD_REQUEST);
        if(runs.findFirstBySessionAndStatusInOrderByRunNoDesc(session,List.of("PENDING","RUNNING")).isPresent())throw error("AGENT_RUN_ACTIVE","The session already has an active run",HttpStatus.CONFLICT);
        if(contextPresent){session.updateContext(blankToNull(platform),blankToNull(osType));sessions.save(session);}
        else {AgentTargetContextResolver.Resolution resolved=targetContextResolver.resolve(content);
            String nextPlatform=resolved.platformSpecified()?resolved.platform():session.getPlatform();
            String nextOsType=resolved.osTypeSpecified()?resolved.osType():session.getOsType();
            if(resolved.platformSpecified()||resolved.osTypeSpecified()){session.updateContext(nextPlatform,nextOsType);sessions.save(session);}}
        var preset=fastPath==null?Optional.<AgentFastPathService.FastPath>empty():fastPath.resolve(content,session.getPlatform(),session.getOsType());
        int runNo=(int)runs.countBySession(session)+1;AgentRunEntity run=runs.save(new AgentRunEntity(session,runNo,preset.isPresent()?"cached-wiki-v1":runtimeVersion,preset.isPresent()?"none":modelKey));
        long sequence=messages.countBySession(session)+1;messages.save(new AgentMessageEntity(session,run,sequence,"USER",content.trim(),"COMPLETE"));session.touch(content.trim());sessions.save(session);
        if(preset.isPresent()) return completePreset(run,session,preset.get());
        Runnable launch=()->executor.submit(()->dispatch(run.getRunKey(),owner));
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){launch.run();}});else launch.run();
        return new AcceptedRun(run.getRunKey(),run.getStatus(),session.getPlatform(),session.getOsType());
    }
    private AcceptedRun completePreset(AgentRunEntity run,AgentSessionEntity session,AgentFastPathService.FastPath preset){
        run.running("cached-wiki:"+run.getRunKey());runs.save(run);
        String payload=preset.payload().replace("\"runRef\":\"\"","\"runRef\":\""+run.getRunKey()+"\"");
        recommendations.save(new AgentRecommendationEntity(run.getRunKey(),run,preset.summary(),"VALID",payload));
        long sequence=messages.countBySession(session)+1;messages.save(new AgentMessageEntity(session,run,sequence,"ASSISTANT",preset.summary(),"COMPLETE"));
        run.succeeded();runs.save(run);
        events.publish(run.getRunKey(),"recommendation.completed",Map.of("runKey",run.getRunKey(),"summary",preset.summary(),"status","VALID","items",preset.items()));
        events.publish(run.getRunKey(),"run.completed",Map.of("runKey",run.getRunKey(),"message",preset.summary()));
        return new AcceptedRun(run.getRunKey(),run.getStatus(),session.getPlatform(),session.getOsType());
    }
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter subscribe(String runKey,Long owner,long after){AgentRunEntity run=requireRun(runKey,owner);restoreTerminalEvent(run);return events.subscribe(runKey,after);}
    @Transactional(readOnly=true) public RunView run(String key,Long owner){AgentRunEntity run=requireRun(key,owner);return runView(run,recommendation(run));}
    @Transactional public void cancel(String key,Long owner){AgentRunEntity run=requireRun(key,owner);if(!List.of("PENDING","RUNNING").contains(run.getStatus()))return;run.cancelled();runs.save(run);runtime.cancel(run.getRuntimeRunId()==null?run.getRunKey():run.getRuntimeRunId());events.publish(key,"run.cancelled",Map.of("runKey",key));}

    private void dispatch(String runKey,Long owner){
        AgentRunEntity run=requireRun(runKey,owner);AgentSessionEntity session=run.getSession();
        var issued=tokens.issueForRun(runKey,owner,session.getProfileKey(),session.getPlatform(),session.getOsType(),session.getKnowledgeScope());
        run.running(runKey);runs.save(run);events.publish(runKey,"run.started",Map.of("runKey",runKey));
        List<AgentRuntimeClient.Message> history=messages.findBySessionOrderBySequenceNoAsc(session).stream().map(m->new AgentRuntimeClient.Message(m.getRole(),m.getContent())).toList();
        try{runtime.run(new AgentRuntimeClient.RunRequest(runKey,session.getSessionKey(),history,issued.token(),session.getPlatform(),session.getOsType(),run.getRunNo()==1),event->handleRuntime(runKey,event));}
        catch(Exception e){AgentRunEntity failed=runs.findByRunKey(runKey).orElseThrow();if(List.of("PENDING","RUNNING").contains(failed.getStatus())){failed.failed("AGENT_RUNTIME_UNAVAILABLE","Agent runtime is temporarily unavailable");runs.save(failed);events.publish(runKey,"run.failed",Map.of("runKey",runKey,"code","AGENT_RUNTIME_UNAVAILABLE","message","Agent 服务暂时不可用，请稍后重试"));}}
    }
    private void handleRuntime(String runKey,AgentRuntimeClient.RuntimeEvent event){
        Map<String,Object> data=new LinkedHashMap<>(event.data());data.put("runKey",runKey);
        switch(event.type()){
            case "message.delta"->events.publish(runKey,"message.delta",data);
            case "tool.started"->{Map<String,Object> safe=safeToolData(data);mcpAudits.started(runKey,string(data.get("callId")),string(data.get("toolName")),safe);events.publish(runKey,event.type(),safe);}
            case "tool.completed"->{Map<String,Object> safe=safeToolData(data);mcpAudits.completed(runKey,string(data.get("callId")),!Boolean.FALSE.equals(data.get("success")),string(data.get("errorCode")));events.publish(runKey,event.type(),safe);}
            case "tool.failed"->{Map<String,Object> safe=safeToolData(data);mcpAudits.completed(runKey,string(data.get("callId")),false,string(data.get("errorCode")));events.publish(runKey,event.type(),safe);}
            case "run.phase"->events.publish(runKey,"run.phase",safePhaseData(data));
            case "runtime.completed"->completeRun(runKey,String.valueOf(data.getOrDefault("finalResponse","")));
            case "runtime.retrying"->events.publish(runKey,"run.retrying",Map.of("runKey",runKey,"attempt",data.getOrDefault("attempt",2),"message","模型未生成最终回答，正在重新整理已检索结果…"));
            case "runtime.failed"->{String code=runtimeFailureCode(data.get("code"));failRun(runKey,code,runtimeFailureMessage(code));}
            case "runtime.cancelled"->cancelRuntime(runKey);
            default->{ }
        }
    }
    private void completeRun(String runKey,String answer){AgentRunEntity run=runs.findByRunKey(runKey).orElseThrow();if(!"RUNNING".equals(run.getStatus()))return;long seq=messages.countBySession(run.getSession())+1;messages.save(new AgentMessageEntity(run.getSession(),run,seq,"ASSISTANT",answer,"COMPLETE"));run.succeeded();runs.save(run);events.publish(runKey,"run.completed",Map.of("runKey",runKey,"message",answer));}
    private void failRun(String runKey,String code,String message){AgentRunEntity run=runs.findByRunKey(runKey).orElseThrow();if(!List.of("PENDING","RUNNING").contains(run.getStatus()))return;run.failed(code,message);runs.save(run);events.publish(runKey,"run.failed",Map.of("runKey",runKey,"code",code,"message",message));}
    private void cancelRuntime(String runKey){AgentRunEntity run=runs.findByRunKey(runKey).orElseThrow();if(!List.of("PENDING","RUNNING").contains(run.getStatus()))return;run.cancelled();runs.save(run);events.publish(runKey,"run.cancelled",Map.of("runKey",runKey));}
    private String runtimeFailureCode(Object value){String code=value==null?"AGENT_RUNTIME_FAILED":String.valueOf(value);return Set.of("EMPTY_RESPONSE_AFTER_RETRY","MODEL_NO_PROGRESS","RUNTIME_TIMEOUT","AGENT_RUNTIME_FAILED").contains(code)?code:"AGENT_RUNTIME_FAILED";}
    private String runtimeFailureMessage(String code){return switch(code){case "EMPTY_RESPONSE_AFTER_RETRY"->"模型未生成最终回答，请稍后重试";case "MODEL_NO_PROGRESS"->"模型长时间没有进展，请稍后重试";case "RUNTIME_TIMEOUT"->"Agent 运行超时，请缩小问题范围后重试";default->"Agent 运行失败，请稍后重试";};}
    private Map<String,Object> safePhaseData(Map<String,Object> data){
        String phase=string(data.get("phase"));
        String message=switch(phase==null?"":phase){
            case "CONTEXT"->"正在确认你的团队和知识权限";
            case "SEARCHING_DOCUMENTS"->"正在检索相关飞书文档";
            case "READING_DOCUMENTS"->"正在读取可访问的业务资料";
            case "COMPOSING"->"资料检索完成，正在整理回答";
            default->"正在处理…";
        };
        return Map.of("runKey",data.get("runKey"),"phase",Set.of("CONTEXT","SEARCHING_DOCUMENTS","READING_DOCUMENTS","COMPOSING").contains(phase)?phase:"COMPOSING","message",message);
    }
    private Map<String,Object>safeToolData(Map<String,Object>data){Map<String,Object>safe=new LinkedHashMap<>();safe.put("runKey",data.get("runKey"));if(data.get("callId")!=null)safe.put("callId",data.get("callId"));if(data.get("toolName")!=null)safe.put("toolName",data.get("toolName"));if(data.get("arguments")!=null){String value=String.valueOf(data.get("arguments"));safe.put("argumentsSummary",value.substring(0,Math.min(1000,value.length())));}if(data.get("success")!=null)safe.put("success",data.get("success"));if(data.get("errorCode")!=null)safe.put("errorCode",data.get("errorCode"));return safe;}
    private String string(Object value){return value==null?null:String.valueOf(value);}
    private String blankToNull(String value){return value==null||value.isBlank()?null:value.trim().toUpperCase(Locale.ROOT);}
    private void restoreTerminalEvent(AgentRunEntity run){
        Map<String,Object>data=new LinkedHashMap<>();data.put("runKey",run.getRunKey());
        switch(run.getStatus()){
            case "SUCCEEDED"->events.restoreTerminal(run.getRunKey(),"run.completed",data);
            case "FAILED"->{data.put("code",Objects.requireNonNullElse(run.getErrorCode(),"AGENT_RUNTIME_FAILED"));data.put("message",Objects.requireNonNullElse(run.getErrorMessage(),"Agent 运行失败"));events.restoreTerminal(run.getRunKey(),"run.failed",data);}
            case "CANCELLED"->events.restoreTerminal(run.getRunKey(),"run.cancelled",data);
            default->{ }
        }
    }

    private AgentSessionEntity requireSession(String key,Long owner){AgentSessionEntity session=sessions.findBySessionKeyAndOwnerUserId(key,owner).orElseThrow(()->error("AGENT_SESSION_NOT_FOUND","Agent session not found",HttpStatus.NOT_FOUND));if(session.isDeleted())throw error("AGENT_SESSION_NOT_FOUND","Agent session not found",HttpStatus.NOT_FOUND);return session;}
    private AgentRunEntity requireRun(String key,Long owner){return runs.findByRunKeyAndSessionOwnerUserId(key,owner).orElseThrow(()->error("AGENT_RUN_NOT_FOUND","Agent run not found",HttpStatus.NOT_FOUND));}
    private BusinessException error(String code,String message,HttpStatus status){return new BusinessException(code,message,status);}
    private SessionSummary summary(AgentSessionEntity s){return new SessionSummary(s.getSessionKey(),s.getTitle(),s.getStatus(),s.getProfileKey(),s.getPlatform(),s.getOsType(),s.getLastMessageAt());}
    private SessionView view(AgentSessionEntity s,List<AgentMessageEntity>ms,AgentRunEntity run,RecommendationView rec){return new SessionView(summary(s),ms.stream().map(m->new MessageView(m.getSequenceNo(),m.getRole(),m.getContent(),m.getStatus(),m.getRun()==null?null:m.getRun().getRunKey(),"ASSISTANT".equals(m.getRole())?recommendation(m.getRun()):null)).toList(),run==null?null:runView(run,rec),rec);}
    private RunView runView(AgentRunEntity r,RecommendationView rec){return new RunView(r.getRunKey(),r.getStatus(),r.getRunNo(),r.getStartedAt(),r.getFinishedAt(),r.getErrorCode(),r.getErrorMessage(),rec);}
    @SuppressWarnings("unchecked") private RecommendationView recommendation(AgentRunEntity run){if(run==null)return null;return recommendations.findByRunRef(run.getRunKey()).map(r->{try{Map<String,Object>p=json.readValue(r.getPayload(),new TypeReference<>(){});return new RecommendationView(r.getSummary(),r.getStatus(),(List<Map<String,Object>>)p.getOrDefault("items",List.of()));}catch(Exception e){return new RecommendationView(r.getSummary(),r.getStatus(),List.of());}}).orElse(null);}
    public record ProfileView(String profileKey,String name,String description,List<String>capabilities){}
    public record SessionSummary(String sessionKey,String title,String status,String profileKey,String platform,String osType,java.time.Instant lastMessageAt){}
    public record MessageView(long sequence,String role,String content,String status,String runKey,RecommendationView recommendation){}
    public record RecommendationView(String summary,String status,List<Map<String,Object>>items){}
    public record RunView(String runKey,String status,int runNo,java.time.Instant startedAt,java.time.Instant finishedAt,String errorCode,String errorMessage,RecommendationView recommendation){}
    public record SessionView(SessionSummary session,List<MessageView>messages,RunView latestRun,RecommendationView latestRecommendation){}
    public record AcceptedRun(String runKey,String status,String platform,String osType){}
}
