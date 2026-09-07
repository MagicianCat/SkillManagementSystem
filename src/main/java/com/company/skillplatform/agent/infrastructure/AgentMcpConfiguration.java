package com.company.skillplatform.agent.infrastructure;

import com.company.skillplatform.agent.application.AgentRunService;
import com.company.skillplatform.skill.application.SkillService;
import com.company.skillplatform.skill.domain.DevelopmentStage;
import com.company.skillplatform.storage.domain.ObjectStoragePort;
import com.company.skillplatform.version.domain.LifecycleStatus;
import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;
import com.company.skillplatform.version.infrastructure.repository.SkillVersionRepository;
import com.company.skillplatform.agent.infrastructure.repository.AgentRecommendationRepository;
import com.company.skillplatform.agent.infrastructure.entity.AgentRecommendationEntity;
import com.company.skillplatform.agent.infrastructure.repository.AgentRunRepository;
import com.company.skillplatform.agent.application.AgentEventHub;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentMcpConfiguration {
    private static final int MAX_BYTES = 64 * 1024;
    private final ObjectMapper objectMapper;
    private final SkillService skills;
    private final SkillVersionRepository versions;
    private final ObjectStoragePort storage;
    private final AgentRunService runs;
    private final AgentRecommendationRepository recommendations;
    private final AgentRunRepository persistentRuns;
    private final AgentEventHub events;

    public AgentMcpConfiguration(ObjectMapper objectMapper, SkillService skills, SkillVersionRepository versions,
                                 ObjectStoragePort storage, AgentRunService runs, AgentRecommendationRepository recommendations,
                                 AgentRunRepository persistentRuns, AgentEventHub events) {
        this.objectMapper = objectMapper; this.skills = skills; this.versions = versions; this.storage = storage; this.runs = runs; this.recommendations = recommendations;this.persistentRuns=persistentRuns;this.events=events;
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> agentMcpServlet() {
        var mapper = new JacksonMcpJsonMapper(objectMapper);
        var transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(mapper).mcpEndpoint("/internal/mcp")
                .contextExtractor(req -> io.modelcontextprotocol.common.McpTransportContext.create(Map.of("request", req)))
                .maxRequestSize(256 * 1024).build();
        McpSyncServer server = McpServer.sync(transport).serverInfo("skill-platform", "1.0")
                .instructions("Use skill search/detail tools, then submit one structured recommendation.")
                .tools(tool("search_skills", "Search published skills by development stage, platform and OS.",
                        schema("object", List.of()), this::search),
                        tool("get_skill_detail", "Get metadata and a segment of the root skill.md.",
                                schema("object", List.of("skillKey")), this::detail),
                        tool("get_skill_file_content", "Get a safe, bounded segment of a published text file.",
                                schema("object", List.of("skillKey", "path")), this::file),
                        tool("submit_skill_recommendation", "Submit the final structured skill recommendation.",
                                recommendationSchema(), this::submit))
                .build();
        return new ServletRegistrationBean<>(transport, "/internal/mcp/*");
    }

    private io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification tool(String name, String description,
            McpSchema.JsonSchema schema, java.util.function.BiFunction<io.modelcontextprotocol.server.McpSyncServerExchange, Map<String,Object>, McpSchema.CallToolResult> fn) {
        return new io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification(
                McpSchema.Tool.builder().name(name).description(description).inputSchema(schema).build(), fn);
    }
    private McpSchema.JsonSchema schema(String type, List<String> required) {
        Map<String,Object> props = new LinkedHashMap<>();
        props.put("developmentStage", Map.of("type", "string", "enum", Arrays.stream(DevelopmentStage.values()).map(Enum::name).toList()));
        props.put("platform", Map.of("type", "string")); props.put("osType", Map.of("type", "string"));
        props.put("skillKey", Map.of("type", "string")); props.put("path", Map.of("type", "string"));
        props.put("offset", Map.of("type", "integer", "minimum", 0)); props.put("maxBytes", Map.of("type", "integer", "minimum", 1, "maximum", MAX_BYTES));
        props.put("page", Map.of("type", "integer", "minimum", 0)); props.put("pageSize", Map.of("type", "integer", "minimum", 1, "maximum", 20));
        props.put("summary", Map.of("type", "string", "maxLength", 2000)); props.put("items", Map.of("type", "array", "maxItems", 20));
        return new McpSchema.JsonSchema(type, props, required, false, Map.of(), Map.of());
    }
    private McpSchema.JsonSchema recommendationSchema() {
        Map<String,Object> itemProperties=new LinkedHashMap<>();
        itemProperties.put("skillKey",Map.of("type","string","minLength",1,"maxLength",64));
        itemProperties.put("priority",Map.of("type","string","enum",List.of("REQUIRED","RECOMMENDED","OPTIONAL")));
        itemProperties.put("reason",Map.of("type","string","minLength",1,"maxLength",2048));
        Map<String,Object> item=Map.of("type","object","properties",itemProperties,"required",List.of("skillKey","priority","reason"),"additionalProperties",false);
        Map<String,Object> props=new LinkedHashMap<>();
        props.put("summary",Map.of("type","string","maxLength",2000));
        props.put("items",Map.of("type","array","maxItems",20,"items",item));
        props.put("citations",Map.of("type","array","maxItems",20));
        return new McpSchema.JsonSchema("object",props,List.of("summary","items"),false,Map.of(),Map.of());
    }
    private McpSchema.CallToolResult search(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        run(ex); String stage = str(args, "developmentStage"); int page = integer(args, "page", 0), size = Math.min(integer(args, "pageSize", 20), 20);
        var result = skills.search(null, null, null, str(args,"platform"), str(args,"osType"), "PUBLISHED", null, stage, null,
                org.springframework.data.domain.PageRequest.of(page, size, org.springframework.data.domain.Sort.by("skillKey").ascending()));
        return ok(Map.of("items", result.getContent(), "page", result.getNumber(), "pageSize", result.getSize(), "total", result.getTotalElements()));
    }
    private McpSchema.CallToolResult detail(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        run(ex); String key = required(args,"skillKey"); var view = skills.get(key); String content = readText(key, "skill.md", integer(args,"offset",0), bounded(args));
        return ok(Map.of("skill", view, "file", "skill.md", "content", content, "offset", integer(args,"offset",0), "hasMore", content.length() >= bounded(args)));
    }
    private McpSchema.CallToolResult file(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        run(ex); String key = required(args,"skillKey"), path = required(args,"path"); if (path.startsWith("/") || path.contains("..")) throw new IllegalArgumentException("unsafe path");
        String content = readText(key, path, integer(args,"offset",0), bounded(args));
        return ok(Map.of("skillKey", key, "path", path, "content", content, "offset", integer(args,"offset",0), "hasMore", content.length() >= bounded(args)));
    }
    private McpSchema.CallToolResult submit(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var run = run(ex); Object items = args.get("items"); if (!(items instanceof List<?> list) || list.size() > 20) throw new IllegalArgumentException("items must contain 0..20 recommendations");
        List<Map<String,Object>> accepted = new ArrayList<>(); for (Object item : list) { if (!(item instanceof Map<?,?> m)) throw new IllegalArgumentException("invalid item"); String key = String.valueOf(m.get("skillKey")); var view = skills.get(key); if (view.status() != com.company.skillplatform.skill.domain.SkillStatus.ACTIVE || view.latestPublishedVersion() == null) continue; Object priority=m.get("priority"), reason=m.get("reason"); String p=priority==null?"RECOMMENDED":String.valueOf(priority).toUpperCase(); if (!Set.of("REQUIRED","RECOMMENDED","OPTIONAL").contains(p)) throw new IllegalArgumentException("invalid priority"); Map<String,Object>trusted=new LinkedHashMap<>();trusted.put("skillKey",key);trusted.put("displayName",view.displayName());trusted.put("description",view.description());trusted.put("version",view.latestPublishedVersion());trusted.put("developmentStage",view.developmentStage());trusted.put("priority",p);trusted.put("reason",reason==null?"":String.valueOf(reason));accepted.add(trusted); }
        String summary = args.get("summary") == null ? "" : String.valueOf(args.get("summary"));
        String status=accepted.size()==list.size()?"VALID":accepted.isEmpty()?"EMPTY":"PARTIALLY_VALID";
        Map<String,Object> result = Map.of("accepted", true, "runRef", run.runRef(), "summary", summary, "status",status,"items", accepted);
        String payload=write(result);var existing=recommendations.findByRunRef(run.runRef());
        if(existing.isPresent()&&!existing.get().getPayload().equals(payload))throw new IllegalArgumentException("recommendation already submitted");
        if(existing.isEmpty()){var persistent=persistentRuns.findByRunKey(run.runRef()).orElse(null);recommendations.save(new AgentRecommendationEntity(run.runRef(),persistent,summary,status,payload));events.publish(run.runRef(),"recommendation.completed",new LinkedHashMap<>(result));}
        return ok(result);
    }
    private com.company.skillplatform.agent.domain.AgentRun run(io.modelcontextprotocol.server.McpSyncServerExchange ex) { HttpServletRequest req=(HttpServletRequest)ex.transportContext().get("request"); String h=req==null?null:req.getHeader("Authorization"); if(h==null||!h.startsWith("Bearer ")) throw new IllegalArgumentException("agent token required"); var run=runs.require(h.substring(7)); var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(run.userId(), null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("skill:browse"))); org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth); return run; }
    private String readText(String key,String path,int offset,int max) { SkillVersionEntity v=versions.findFirstBySkillIdAndLifecycleStatusInOrderByPublishedAtDesc(skills.get(key).id(), List.of(LifecycleStatus.PUBLISHED)).orElseThrow(); try(InputStream in=storage.get(v.getSourceObjectKey()); ZipInputStream zip=new ZipInputStream(in, StandardCharsets.UTF_8)){ ZipEntry e; while((e=zip.getNextEntry())!=null){ if(e.isDirectory()||!e.getName().equalsIgnoreCase(path)) continue; byte[] b=zip.readNBytes(MAX_BYTES+1); if(b.length>MAX_BYTES) throw new IllegalArgumentException("file too large"); String s=new String(b, StandardCharsets.UTF_8); return offset>=s.length()?"":s.substring(offset, Math.min(s.length(), offset+max)); } throw new IllegalArgumentException("SKILL_FILE_NOT_FOUND"); } catch(Exception e){ throw new IllegalArgumentException(e.getMessage(), e); } }
    private McpSchema.CallToolResult ok(Object value){ return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(write(value))), false, value, Map.of()); }
    private String write(Object v){ try{return objectMapper.writeValueAsString(v);}catch(Exception e){return "{}";} }
    private String str(Map<String,Object> a,String k){Object v=a.get(k);return v==null?null:String.valueOf(v);} private int integer(Map<String,Object>a,String k,int d){Object v=a.get(k);return v instanceof Number n?n.intValue():d;} private int bounded(Map<String,Object>a){return Math.min(integer(a,"maxBytes",32768),MAX_BYTES);} private String required(Map<String,Object>a,String k){String v=str(a,k);if(v==null||v.isBlank())throw new IllegalArgumentException(k+" is required");return v;}
}
