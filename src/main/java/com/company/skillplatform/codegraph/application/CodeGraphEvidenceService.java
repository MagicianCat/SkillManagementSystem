package com.company.skillplatform.codegraph.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Small, versioned evidence hand-off between agents in one frozen workflow graph. */
@Service
public class CodeGraphEvidenceService {
    private static final int MAX_FACTS = 30;
    private static final int SUMMARY_CHARS = 2_500;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CodeGraphEvidenceService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    @Transactional(readOnly = true)
    public Map<String,Object> latest(long workflowRunId,long bindingId) {
        var rows=jdbc.queryForList("select revision_no,pack_json from workflow_code_graph_evidence_pack where workflow_run_id=? and graph_binding_id=? order by revision_no desc limit 1",workflowRunId,bindingId);
        if(rows.isEmpty()) return empty(workflowRunId,bindingId);
        var value=read(rows.get(0).get("pack_json")); value.put("revision",rows.get(0).get("revision_no")); return value;
    }

    @Transactional
    public void merge(long workflowRunId,long bindingId,long agentRunId,String queryRef,String operation,String repository,Map<String,Object> result) {
        var pack=latest(workflowRunId,bindingId);
        var facts=new ArrayList<Map<String,Object>>();
        Object existing=pack.get("facts"); if(existing instanceof List<?> list) for(Object item:list) if(item instanceof Map<?,?> map) facts.add(copy(map));
        var known=new LinkedHashSet<String>(); for(var fact:facts) known.add(String.valueOf(fact.get("fingerprint")));
        int beforeKnown=known.size();int repoBefore=reposSize(pack);collect(result,operation,repository,queryRef,facts,known);
        if(facts.size()>MAX_FACTS) facts=new ArrayList<>(facts.subList(facts.size()-MAX_FACTS,facts.size()));
        var repos=new LinkedHashSet<String>(); Object oldRepos=pack.get("relatedRepositories");if(oldRepos instanceof List<?> list)list.forEach(x->repos.add(String.valueOf(x)));if(repository!=null&&!repository.isBlank())repos.add(repository);
        if(known.size()==beforeKnown&&repos.size()==repoBefore)return;
        pack.put("schemaVersion",1);pack.put("workflowRunId",workflowRunId);pack.put("graphBindingId",bindingId);pack.put("relatedRepositories",repos);pack.put("facts",facts);
        int revision=((Number)pack.getOrDefault("revision",0)).intValue()+1;pack.remove("revision");String value=write(pack);
        jdbc.update("insert into workflow_code_graph_evidence_pack(time_created,workflow_run_id,graph_binding_id,revision_no,pack_json,pack_hash,updated_by_agent_run_id) values(now(3),?,?,?,?,?,?)",workflowRunId,bindingId,revision,value,sha(value),agentRunId);
    }

    public Map<String,Object> summary(long workflowRunId,long bindingId) {
        var pack=latest(workflowRunId,bindingId);var summary=new LinkedHashMap<String,Object>();summary.put("revision",pack.getOrDefault("revision",0));summary.put("relatedRepositories",pack.getOrDefault("relatedRepositories",List.of()));
        var facts=new ArrayList<>();Object raw=pack.get("facts");if(raw instanceof List<?> list){for(Object value:list){facts.add(value);if(write(facts).length()>SUMMARY_CHARS){facts.remove(facts.size()-1);break;}}}
        summary.put("facts",facts);summary.put("factCount",raw instanceof List<?> list?list.size():0);summary.put("truncated",raw instanceof List<?> list&&facts.size()<list.size());return summary;
    }

    private void collect(Map<String,Object> data,String operation,String repository,String queryRef,List<Map<String,Object>> facts,LinkedHashSet<String> known) {
        for(String key:List.of("symbols","nodes","relations","edges","paths","routes","dependencies")) {
            Object raw=data.get(key);if(!(raw instanceof List<?> list))continue;
            for(Object item:list){if(!(item instanceof Map<?,?> source))continue;var fact=new LinkedHashMap<String,Object>();fact.put("type",operation.toUpperCase().replace('-','_'));fact.put("repository",repository);fact.put("confidence","GRAPH_CONFIRMED");
                for(String field:List.of("uid","nodeUid","name","kind","type","filePath","source","target","from","to","path","httpMethod"))if(source.get(field)!=null)fact.put(field,source.get(field));
                String fingerprint=sha(write(fact));fact.put("fingerprint",fingerprint);fact.put("queryRef",queryRef);if(known.add(fingerprint))facts.add(fact);if(facts.size()>=MAX_FACTS)return;}
        }
    }
    private Map<String,Object> empty(long run,long binding){var result=new LinkedHashMap<String,Object>();result.put("schemaVersion",1);result.put("workflowRunId",run);result.put("graphBindingId",binding);result.put("revision",0);result.put("relatedRepositories",List.of());result.put("facts",List.of());result.put("identifiedRisks",List.of());result.put("unresolvedQuestions",List.of());return result;}
    private int reposSize(Map<String,Object> pack){return pack.get("relatedRepositories") instanceof List<?> list?list.size():0;}
    private Map<String,Object> copy(Map<?,?> source){var out=new LinkedHashMap<String,Object>();source.forEach((k,v)->out.put(String.valueOf(k),v));return out;}
    private Map<String,Object> read(Object value){try{return json.readValue(String.valueOf(value),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){return new LinkedHashMap<>();}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){return "{}";}}
    private String sha(String value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
