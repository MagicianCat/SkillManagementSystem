package com.company.skillplatform.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.company.skillplatform.ops.OpsEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class IntentPlanService {
    private static final Set<String> POLICIES = Set.of("SKILL", "WIKI", "KNOWLEDGE", "CHAT");
    private final JdbcTemplate jdbc;private final OpsEventService ops;
    private final ObjectMapper json;
    private final HttpClient http;
    private final String endpoint, apiKey, model;
    @org.springframework.beans.factory.annotation.Autowired
    public IntentPlanService(JdbcTemplate jdbc, ObjectMapper json,OpsEventService ops,
            @Value("${agent.intent.endpoint}") String endpoint,
            @Value("${agent.intent.api-key:${DECISION_MODEL_API_KEY:}}") String apiKey,
            @Value("${agent.intent.model}") String model) {
        this.jdbc=jdbc;this.ops=ops; this.json=json; this.endpoint=endpoint.replaceAll("/$", ""); this.apiKey=apiKey; this.model=model;
        this.http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
    }
    public Map<String,Object> classify(String runKey, String current, List<String> history) {
        Map<String,Object> existing=load(runKey);
        if(existing!=null) return existing;
        long start=System.nanoTime();
        var definitions=jdbc.query("select intent_code,decision_type,instructions,yes_description,no_description,policy_key,min_yes_probability,version_no from agent_intent_definition where enabled=1 order by sort_order,id",
                (rs,n)->new Definition(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getDouble(7),rs.getInt(8)));
        if(definitions.isEmpty() || definitions.size()>32 || apiKey.isBlank()) throw new IllegalStateException("Intent model is not configured");
        Map<String,Object> questions=new LinkedHashMap<>();
        for(var d:definitions) {
            if(!POLICIES.contains(d.policy()) || !Set.of("INTENT","CONSTRAINT").contains(d.type())) throw new IllegalStateException("Invalid intent definition");
            questions.put((d.type().equals("CONSTRAINT")?"constraint__":"intent__")+d.code(), Map.of("type","choice","instructions",d.instructions(),"criteria",Map.of("YES",d.yes(),"NO",d.no())));
        }
        boolean needsReference=current.matches("(?s).*(?:这个|那个|刚才|上一个|前面|之前|继续|再把|它).*");
        String state="任务：判断本轮用户的实际请求。当前消息优先，历史仅用于解析指代。\n"+(needsReference?"最近对话："+String.join(" | ",history)+"\n":"")+"当前用户消息："+current;
        if(state.length()>3000) state=state.substring(state.length()-3000);
        JsonNode response=requestModel(runKey,state,questions);
        Set<String> selected=new LinkedHashSet<>(), constraints=new LinkedHashSet<>(), policies=new LinkedHashSet<>(), artifacts=new LinkedHashSet<>(), capabilities=new LinkedHashSet<>();
        Map<String,Double> probabilities=new LinkedHashMap<>();
        for(var d:definitions) {
            String key=(d.type().equals("CONSTRAINT")?"constraint__":"intent__")+d.code();
            JsonNode answer=response.path("answers").path(key);
            JsonNode yes=answer.path("probabilities").path("YES");
            if(!yes.isNumber() || yes.asDouble()<0 || yes.asDouble()>1 || !"choice".equals(answer.path("type").asText())) throw new IllegalStateException("INTENT_MODEL_INVALID_RESPONSE");
            double probability=yes.asDouble(); probabilities.put(d.code(),probability);
            if(probability>=d.threshold()) { if(d.type().equals("CONSTRAINT")) constraints.add(d.code()); else {selected.add(d.code());policies.add(d.policy());} }
        }
        Map<String,String> choices=new LinkedHashMap<>();
        for(var d:definitions) if(d.type().equals("INTENT")) choices.put(d.code(),d.yes());
        if(choices.size()<2) throw new IllegalStateException("At least two enabled intents are required");
        JsonNode primary=requestModel(runKey,state,Map.of("primary_intent",Map.of("type","choice","instructions","选择本轮用户最需要的任务或产物。岗位或角色的上下文、背景资料属于角色上下文，不等于 Skill 推荐。只有明确索要 Skill 时才选择 Skill 推荐。历史仅用于指代，当前消息优先。","criteria",choices)));
        JsonNode primaryAnswer=primary.path("answers").path("primary_intent");
        String primaryChoice=primaryAnswer.path("choice").asText();
        double primaryProbability=primaryAnswer.path("probabilities").path(primaryChoice).asDouble(0);
        Definition primaryDefinition=definitions.stream().filter(d->d.type().equals("INTENT")&&d.code().equals(primaryChoice)).findFirst().orElseThrow(()->new IllegalStateException("INTENT_MODEL_INVALID_RESPONSE"));
        if(primaryProbability>=0.75 && (selected.isEmpty()||selected.size()==1)) {selected.clear();policies.clear();selected.add(primaryChoice);policies.add(primaryDefinition.policy());}
        if(selected.contains("SKILL_RECOMMEND") && !"SKILL_RECOMMEND".equals(primaryChoice)) {
            JsonNode check=requestModel(runKey,"只判断当前用户消息："+current,Map.of("skill_requested",Map.of("type","choice","instructions","用户是否明确要求最终输出 Skill 推荐或 Skill 清单？询问岗位上下文、背景资料或 Wiki 不等于要求 Skill。","criteria",Map.of("YES","明确要求推荐、列出或挑选 Skill","NO","没有明确要求 Skill 推荐"))));
            if(check.path("answers").path("skill_requested").path("probabilities").path("YES").asDouble(0)<0.75) {selected.remove("SKILL_RECOMMEND");policies.remove("SKILL");}
        }
        if(selected.size()>1 && selected.remove("GENERAL_CHAT")) policies.remove("CHAT");
        String status=selected.isEmpty()?"EXPLORE":policies.contains("SKILL")&&constraints.contains("EXCLUDE_SKILL_OUTPUT")?"CONFLICT":"READY";
        boolean recommend="READY".equals(status)&&policies.contains("SKILL")&&!constraints.contains("EXCLUDE_SKILL_OUTPUT");
        capabilities.add("user.context.read");
        if(!selected.equals(Set.of("GENERAL_CHAT"))) capabilities.addAll(Set.of("knowledge.search","wiki.search","wiki.read"));
        if(policies.contains("KNOWLEDGE")||"EXPLORE".equals(status)) capabilities.addAll(Set.of("feishu.search","feishu.read"));
        if(recommend) {capabilities.addAll(Set.of("skill.search","skill.detail","skill.file.read","recommendation.submit"));artifacts.add("SKILL");}
        if(policies.contains("WIKI")) artifacts.add("WIKI_DOCUMENT");
        if(selected.contains("ASDM_TOOL")) artifacts.add("ASDM_PACKAGE_LINK");
        if(policies.contains("KNOWLEDGE")||policies.contains("CHAT")||"EXPLORE".equals(status)) artifacts.add("ANSWER");
        Map<String,Object> plan=new LinkedHashMap<>();plan.put("schemaVersion",1);plan.put("status",status);plan.put("intents",selected);plan.put("constraints",constraints);plan.put("allowedArtifacts",artifacts);plan.put("excludedArtifacts",constraints.contains("EXCLUDE_SKILL_OUTPUT")?List.of("SKILL"):List.of());plan.put("policyKeys",policies);plan.put("allowedCapabilities",capabilities);plan.put("recommendSkills",recommend);
        try {
            jdbc.update("insert into agent_run_intent_plan(run_key,status,intents_json,constraints_json,allowed_artifacts_json,allowed_capabilities_json,registry_snapshot_json,model_key,model_request_id,decision_latency_ms) values(?,?,?,?,?,?,?,?,?,?)",
                    runKey,status,json.writeValueAsString(selected),json.writeValueAsString(constraints),json.writeValueAsString(artifacts),json.writeValueAsString(capabilities),json.writeValueAsString(Map.of("definitions",definitions,"probabilities",probabilities,"primaryChoice",primaryChoice,"primaryProbability",primaryProbability)),model,response.path("request_id").asText(null),(System.nanoTime()-start)/1_000_000);
        } catch(Exception e) {throw new IllegalStateException("INTENT_PLAN_PERSIST_FAILED",e);}
        return plan;
    }
    public Map<String,Object> load(String runKey) {
        var rows=jdbc.query("select status,intents_json,constraints_json,allowed_artifacts_json,allowed_capabilities_json,registry_snapshot_json from agent_run_intent_plan where run_key=?",(rs,n)->{
            try {
                Set<String> intents=new LinkedHashSet<>(json.readValue(rs.getString(2),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}));
                Set<String> constraints=new LinkedHashSet<>(json.readValue(rs.getString(3),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}));
                List<String> artifacts=json.readValue(rs.getString(4),new com.fasterxml.jackson.core.type.TypeReference<>(){});
                List<String> caps=json.readValue(rs.getString(5),new com.fasterxml.jackson.core.type.TypeReference<>(){});
                Set<String> policies=new LinkedHashSet<>();
                JsonNode definitions=json.readTree(rs.getString(6)).path("definitions");
                for(JsonNode d:definitions) if(intents.contains(d.path("code").asText())) policies.add(d.path("policy").asText());
                if(policies.isEmpty() && !"EXPLORE".equals(rs.getString(1))) throw new IllegalStateException("Intent policy missing in frozen snapshot");
                boolean recommend=policies.contains("SKILL")&&!constraints.contains("EXCLUDE_SKILL_OUTPUT")&&"READY".equals(rs.getString(1));
                return Map.<String,Object>of("schemaVersion",1,"status",rs.getString(1),"intents",intents,"constraints",constraints,"allowedArtifacts",artifacts,"excludedArtifacts",constraints.contains("EXCLUDE_SKILL_OUTPUT")?List.of("SKILL"):List.of(),"policyKeys",policies,"allowedCapabilities",caps,"recommendSkills",recommend);
            } catch(Exception e){throw new IllegalStateException("Invalid stored intent plan",e);}
        },runKey);
        return rows.isEmpty()?null:rows.get(0);
    }
    public boolean allows(String runKey,String capability){Map<String,Object> plan=load(runKey);return plan!=null&&((Collection<?>)plan.get("allowedCapabilities")).contains(capability);}
    private JsonNode requestModel(String runKey,String state,Map<String,Object> questions) {
        long started=System.nanoTime();
        try {
            String body=json.writeValueAsString(Map.of("model",model,"state",state,"questions",questions));
            HttpRequest request=HttpRequest.newBuilder(URI.create(endpoint+"/v1/systemone"))
                    .timeout(Duration.ofMillis(1500)).header("Authorization","Bearer "+apiKey)
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> result=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(result.statusCode()!=200) throw new IllegalStateException("Decision Model HTTP "+result.statusCode());
            JsonNode parsed=json.readTree(result.body());
            ops.record(new OpsEventService.Event(null,"agent.intent.request","AGENT_INTENT","classify","SUCCESS",model,null,runKey,null,null,null,(System.nanoTime()-started)/1_000_000,null));
            return parsed;
        } catch(Exception e) {
            ops.recordFailure(new OpsEventService.Event(null,"agent.intent.request","AGENT_INTENT","classify","FAILED",model,"INTENT_MODEL_UNAVAILABLE",runKey,null,null,null,(System.nanoTime()-started)/1_000_000,null));
            throw new IllegalStateException("INTENT_MODEL_UNAVAILABLE",e); }
    }
    private record Definition(String code,String type,String instructions,String yes,String no,String policy,double threshold,int version) {}
}
