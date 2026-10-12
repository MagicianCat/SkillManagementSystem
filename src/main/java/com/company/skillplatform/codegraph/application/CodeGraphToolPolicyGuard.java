package com.company.skillplatform.codegraph.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Enforces tool visibility, budgets, parameter bounds, cache isolation and audit at the MCP boundary. */
@Service
public class CodeGraphToolPolicyGuard {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CodeGraphPolicyResolver policies;
    private final CodeGraphQueryService queries;
    private final CodeGraphEvidenceService evidence;
    private final TransactionTemplate tx;

    public CodeGraphToolPolicyGuard(JdbcTemplate jdbc,ObjectMapper json,CodeGraphPolicyResolver policies,
                                    CodeGraphQueryService queries,CodeGraphEvidenceService evidence,
                                    PlatformTransactionManager transactionManager) {
        this.jdbc=jdbc;this.json=json;this.policies=policies;this.queries=queries;this.evidence=evidence;this.tx=new TransactionTemplate(transactionManager);
    }

    public Map<String,Object> execute(long agentRunId,long actorId,String operation,String repository,Map<String,Object> requested) {
        return execute(agentRunId,actorId,operation,repository,requested,false);
    }

    public Map<String,Object> preload(long agentRunId,long actorId,String operation,String repository,Map<String,Object> requested) {
        return execute(agentRunId,actorId,operation,repository,requested,true);
    }

    private Map<String,Object> execute(long agentRunId,long actorId,String operation,String repository,Map<String,Object> requested,boolean preload) {
        var resolved=policies.resolveForAgent(agentRunId,actorId);var policy=resolved.policy();String op=normalize(operation);
        if(!policy.enabled()||!policy.allowedTools().contains(op))return denied(resolved,op,"TOOL_NOT_ALLOWED",preload);
        var parameters=constrain(op,requested,policy);String queryHash=sha(resolved.graphBindingId()+"|"+op+"|"+(repository==null?"":repository)+"|"+write(parameters));
        String invocation="cgq_"+UUID.randomUUID().toString().replace("-","");long started=System.nanoTime();
        String budgetError=reserve(resolved,preload,false);if(budgetError!=null){audit(invocation,resolved,op,queryHash,"BUDGET_EXCEEDED",false,false,preload,0,0,elapsed(started),budgetError);return denied(resolved,op,budgetError,preload);}
        try {
            Map<String,Object> data=cache(resolved.workflowRunId(),resolved.graphBindingId(),queryHash);
            boolean cacheHit=data!=null;
            if(!cacheHit){String engineError=reserve(resolved,preload,true);if(engineError!=null){audit(invocation,resolved,op,queryHash,"BUDGET_EXCEEDED",false,false,preload,0,0,elapsed(started),engineError);return denied(resolved,op,engineError,preload);}data=queries.executeForAgent(agentRunId,actorId,op,repository,parameters).data();storeCache(resolved.workflowRunId(),resolved.graphBindingId(),queryHash,op,data);}
            var clipped=clip(data,policy.maxNodes(),policy.maxResultChars());String status=clipped.partial()?"PARTIAL":"COMPLETE";
            var envelope=envelope(resolved,op,status,cacheHit,clipped.value(),clipped.returnedCount(),clipped.totalCount(),clipped.partial(),clipped.partial()?"NODE_OR_CHARACTER_LIMIT":null);
            int chars=write(envelope).length();audit(invocation,resolved,op,queryHash,status,cacheHit,!cacheHit,preload,chars,clipped.count(),elapsed(started),null);
            evidence.merge(resolved.workflowRunId(),resolved.graphBindingId(),agentRunId,invocation,op,repository,clipped.value());
            envelope.put("queryRef",invocation);envelope.put("evidence",evidence.summary(resolved.workflowRunId(),resolved.graphBindingId()));return envelope;
        } catch(RuntimeException failure){audit(invocation,resolved,op,queryHash,"DEGRADED",false,false,preload,0,0,elapsed(started),failure.getClass().getSimpleName());var result=envelope(resolved,op,"DEGRADED",false,Map.of(),0,0,false,null);result.put("errorCode","CODE_GRAPH_UNAVAILABLE");result.put("retryable",true);return result;}
    }

    private String reserve(CodeGraphPolicyResolver.ResolvedPolicy p,boolean preload,boolean engine) {
        return tx.execute(status->{jdbc.queryForObject("select id from stage_run where id=? for update",Long.class,p.stageRunId());
            jdbc.update("insert ignore into code_graph_budget_counter(stage_run_id,node_key,time_updated) values(?,?,now(3))",p.stageRunId(),p.nodeKey());
            Map<String,Object> totals=jdbc.queryForMap("select coalesce(sum(stage_tool_calls),0) tools,coalesce(sum(stage_engine_queries),0) engines,coalesce(sum(preload_queries),0) preloads from code_graph_budget_counter where stage_run_id=?",p.stageRunId());
            Map<String,Object> own=jdbc.queryForMap("select node_tool_calls,node_engine_queries,preload_queries from code_graph_budget_counter where stage_run_id=? and node_key=?",p.stageRunId(),p.nodeKey());
            if(preload){if(!engine&&((Number)own.get("preload_queries")).intValue()>=p.policy().maxPreloadQueries())return "PRELOAD_BUDGET_EXCEEDED";}
            else if(!engine){if(((Number)totals.get("tools")).intValue()>=p.policy().stageMaxToolCalls())return "STAGE_BUDGET_EXCEEDED";if(((Number)own.get("node_tool_calls")).intValue()>=p.policy().softToolCalls())return "NODE_BUDGET_EXCEEDED";}
            if(engine&&((Number)totals.get("engines")).intValue()>=p.policy().stageMaxEngineQueries())return "ENGINE_BUDGET_EXCEEDED";
            if(preload&&!engine)jdbc.update("update code_graph_budget_counter set preload_queries=preload_queries+1,time_updated=now(3) where stage_run_id=? and node_key=?",p.stageRunId(),p.nodeKey());
            else if(!preload&&!engine)jdbc.update("update code_graph_budget_counter set stage_tool_calls=stage_tool_calls+1,node_tool_calls=node_tool_calls+1,time_updated=now(3) where stage_run_id=? and node_key=?",p.stageRunId(),p.nodeKey());
            if(engine)jdbc.update("update code_graph_budget_counter set stage_engine_queries=stage_engine_queries+1,node_engine_queries=node_engine_queries+1,time_updated=now(3) where stage_run_id=? and node_key=?",p.stageRunId(),p.nodeKey());return null;});
    }

    private Map<String,Object> constrain(String op,Map<String,Object> raw,CodeGraphPolicyResolver.NodePolicy policy){var out=new LinkedHashMap<String,Object>(raw==null?Map.of():raw);out.remove("purpose");out.remove("escalationReason");
        if(out.containsKey("depth"))out.put("depth",Math.min(number(out.get("depth"),1),policy.maxDepth()));
        if(out.containsKey("maxDepth"))out.put("maxDepth",Math.min(number(out.get("maxDepth"),1),policy.maxDepth()));
        if("impact".equals(op)){out.putIfAbsent("depth",policy.maxDepth());out.put("maxNodes",Math.min(number(out.get("maxNodes"),policy.maxNodes()),policy.maxNodes()));out.put("maxPaths",Math.min(number(out.get("maxPaths"),policy.maxPaths()),policy.maxPaths()));out.put("limit",Math.min(number(out.get("limit"),policy.maxNodes()),policy.maxNodes()));}
        else if("trace".equals(op)){out.put("maxDepth",Math.min(number(out.get("maxDepth"),policy.maxDepth()),policy.maxDepth()));}
        else out.put("limit",Math.min(number(out.get("limit"),policy.maxNodes()),policy.maxNodes()));return out;}

    private Clip clip(Map<String,Object> source,int maxItems,int maxChars){var value=deepCopy(source);boolean partial=false;int total=0;
        for(String key:List.of("symbols","nodes","relations","edges","paths","routes","dependencies")){Object raw=value.get(key);if(raw instanceof List<?> list){total+=list.size();if(list.size()>maxItems){value.put(key,new ArrayList<>(list.subList(0,maxItems)));partial=true;}}}
        while(write(value).length()>maxChars){String largest=null;int size=0;for(var entry:value.entrySet())if(entry.getValue() instanceof List<?> list&&list.size()>size){largest=entry.getKey();size=list.size();}if(largest==null||size==0){value=Map.of("summary","Result exceeded character budget","availableKeys",new ArrayList<>(source.keySet()));partial=true;break;}var list=new ArrayList<>((List<?>)value.get(largest));list.remove(list.size()-1);value.put(largest,list);partial=true;}
        int returned=0;for(String key:List.of("symbols","nodes","relations","edges","paths","routes","dependencies"))if(value.get(key) instanceof List<?> list)returned+=list.size();return new Clip(value,returned,total,partial);}

    private Map<String,Object> envelope(CodeGraphPolicyResolver.ResolvedPolicy p,String op,String status,boolean cacheHit,Map<String,Object> result,int returned,int total,boolean hasMore,String reason){var out=new LinkedHashMap<String,Object>();out.put("status",status);out.put("operation",op);out.put("workflowRunId",p.workflowRunId());out.put("stageRunId",p.stageRunId());out.put("agentRunId",p.agentRunId());out.put("graphBindingId",p.graphBindingId());out.put("policyVersion",p.policy().version());out.put("cacheHit",cacheHit);out.put("result",result);out.put("returnedCount",returned);out.put("totalCount",total);out.put("hasMore",hasMore);if(reason!=null)out.put("truncatedReason",reason);out.put("remainingBudget",remaining(p));return out;}
    private Map<String,Object> denied(CodeGraphPolicyResolver.ResolvedPolicy p,String op,String code,boolean preload){var out=envelope(p,op,"TOOL_NOT_ALLOWED".equals(code)?"TOOL_NOT_ALLOWED":"BUDGET_EXCEEDED",false,Map.of(),0,0,false,null);out.put("errorCode",code);out.put("instruction","Use existing Evidence and complete the current role. Do not retry the same graph request.");out.put("evidence",evidence.summary(p.workflowRunId(),p.graphBindingId()));return out;}
    private Map<String,Object> remaining(CodeGraphPolicyResolver.ResolvedPolicy p){var total=jdbc.queryForMap("select coalesce(sum(stage_tool_calls),0) tools,coalesce(sum(stage_engine_queries),0) engines from code_graph_budget_counter where stage_run_id=?",p.stageRunId());var own=jdbc.queryForMap("select node_tool_calls,preload_queries from code_graph_budget_counter where stage_run_id=? and node_key=?",p.stageRunId(),p.nodeKey());return Map.of("stageToolCalls",Math.max(0,p.policy().stageMaxToolCalls()-((Number)total.get("tools")).intValue()),"nodeToolCalls",Math.max(0,p.policy().softToolCalls()-((Number)own.get("node_tool_calls")).intValue()),"stageEngineQueries",Math.max(0,p.policy().stageMaxEngineQueries()-((Number)total.get("engines")).intValue()),"preloadQueries",Math.max(0,p.policy().maxPreloadQueries()-((Number)own.get("preload_queries")).intValue()));}
    private void audit(String key,CodeGraphPolicyResolver.ResolvedPolicy p,String op,String hash,String status,boolean hit,boolean engine,boolean preload,int chars,int count,long ms,String error){jdbc.update("insert into code_graph_tool_invocation(invocation_key,workflow_run_id,stage_run_id,agent_run_id,node_key,graph_binding_id,operation,query_hash,status,cache_hit,engine_executed,preload,returned_chars,result_count,duration_ms,error_code,time_created,time_updated) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(3),now(3))",key,p.workflowRunId(),p.stageRunId(),p.agentRunId(),p.nodeKey(),p.graphBindingId(),op,hash,status,hit,engine,preload,chars,count,ms,error);}
    private Map<String,Object> cache(long run,long binding,String hash){var rows=jdbc.queryForList("select result_json from code_graph_query_cache where workflow_run_id=? and graph_binding_id=? and query_hash=?",run,binding,hash);return rows.isEmpty()?null:read(rows.get(0).get("result_json"));}
    private void storeCache(long run,long binding,String hash,String op,Map<String,Object> data){jdbc.update("insert ignore into code_graph_query_cache(time_created,time_updated,workflow_run_id,graph_binding_id,query_hash,operation,result_json) values(now(3),now(3),?,?,?,?,?)",run,binding,hash,op,write(data));}
    private String normalize(String operation){return operation==null?"":operation.toLowerCase().replace('_','-').replace("code-graph-","");}
    private int number(Object value,int fallback){return value instanceof Number n?n.intValue():fallback;}
    private long elapsed(long started){return Duration.ofNanos(System.nanoTime()-started).toMillis();}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){return "{}";}}
    private Map<String,Object> read(Object value){try{return json.readValue(String.valueOf(value),new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){return new LinkedHashMap<>();}}
    private Map<String,Object> deepCopy(Map<String,Object> value){return read(write(value));}
    private String sha(String value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private record Clip(Map<String,Object> value,int returnedCount,int totalCount,boolean partial){int count(){return returnedCount;}}
}
