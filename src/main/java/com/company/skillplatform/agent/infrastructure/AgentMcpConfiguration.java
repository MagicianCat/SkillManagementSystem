package com.company.skillplatform.agent.infrastructure;

import com.company.skillplatform.agent.application.AgentRunService;
import com.company.skillplatform.agent.domain.AgentRun;
import com.company.skillplatform.agent.application.AgentFeishuCallGuard;
import com.company.skillplatform.common.application.BusinessException;
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
import com.company.skillplatform.project.application.ProjectControlService;
import com.company.skillplatform.project.application.DocumentAgentSessionService;
import com.company.skillplatform.wiki.application.WikiDocumentService;
import com.company.skillplatform.knowledge.application.KnowledgeSearchService;
import com.company.skillplatform.codegraph.application.CodeGraphToolPolicyGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

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
    private final WikiDocumentService wiki;
    private final FeishuDocumentMcpProxy feishu;
    private final AgentFeishuCallGuard feishuGuard;
    private final String webBaseUrl;
    private final ProjectControlService projectControl;
    private final DocumentAgentSessionService documentAgents;
    private final JdbcTemplate jdbc;
    private final com.company.skillplatform.agentworkflow.application.WorkflowRuntimeEventService workflowEvents;
    private final com.company.skillplatform.agentworkflow.application.WorkflowSseService workflowSse;
    private final RestClient workflowGateway;
    private final String workflowServiceToken;
    private final KnowledgeSearchService knowledge;
    @org.springframework.beans.factory.annotation.Autowired
    private com.company.skillplatform.agent.application.RetrievalScopeGuard retrievalGuard;
    @org.springframework.beans.factory.annotation.Autowired
    private com.company.skillplatform.knowledge.application.StructuredCatalogService structuredCatalog;
    @org.springframework.beans.factory.annotation.Autowired
    private com.company.skillplatform.agent.application.RetrievalPlanStore retrievalPlans;
    @org.springframework.beans.factory.annotation.Autowired
    private com.company.skillplatform.knowledge.application.ExternalRoleLookupService externalRoles;
    @org.springframework.beans.factory.annotation.Autowired
    private com.company.skillplatform.knowledge.application.ExternalWikiRoleResolver wikiRoles;
    private final CodeGraphToolPolicyGuard codeGraphPolicy;

    public AgentMcpConfiguration(ObjectMapper objectMapper, SkillService skills, SkillVersionRepository versions,
                                 ObjectStoragePort storage, AgentRunService runs, AgentRecommendationRepository recommendations,
                                 AgentRunRepository persistentRuns, AgentEventHub events, WikiDocumentService wiki, FeishuDocumentMcpProxy feishu,
                                 AgentFeishuCallGuard feishuGuard, ProjectControlService projectControl, DocumentAgentSessionService documentAgents,
                                 JdbcTemplate jdbc, com.company.skillplatform.agentworkflow.application.WorkflowRuntimeEventService workflowEvents,
                                 com.company.skillplatform.agentworkflow.application.WorkflowSseService workflowSse,
                                 RestClient.Builder restClientBuilder, KnowledgeSearchService knowledge,
                                 CodeGraphToolPolicyGuard codeGraphPolicy,
                                 @Value("${skill-platform.agent-runtime.gateway-url:http://127.0.0.1:18080}") String gatewayUrl,
                                 @Value("${skill-platform.internal-service-token:${SMS_SERVICE_TOKEN:local-sms-service-token}}") String workflowServiceToken,
                                 @Value("${skill-platform.agent-web-base-url:${skill-platform.feishu.bot-web-base-url:http://127.0.0.1:5173}}") String webBaseUrl) {
        this.objectMapper = objectMapper; this.skills = skills; this.versions = versions; this.storage = storage; this.runs = runs; this.recommendations = recommendations;this.persistentRuns=persistentRuns;this.events=events; this.wiki=wiki; this.feishu=feishu; this.feishuGuard=feishuGuard; this.projectControl=projectControl; this.documentAgents=documentAgents; this.jdbc=jdbc; this.workflowEvents=workflowEvents; this.workflowSse=workflowSse; this.workflowGateway=restClientBuilder.baseUrl(gatewayUrl).build(); this.workflowServiceToken=workflowServiceToken; this.webBaseUrl=webBaseUrl.replaceAll("/$", ""); this.knowledge=knowledge; this.codeGraphPolicy=codeGraphPolicy;
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> agentMcpServlet() {
        var mapper = new JacksonMcpJsonMapper(objectMapper);
        var transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(mapper).mcpEndpoint("/internal/mcp")
                .contextExtractor(req -> io.modelcontextprotocol.common.McpTransportContext.create(Map.of("request", req)))
                .maxRequestSize(256 * 1024).build();
        McpSyncServer server = McpServer.sync(transport).serverInfo("skill-platform", "1.0")
                .instructions("Search authorized platform knowledge first for Skill and non-Skill questions. Matched Wiki results contain a bounded directory of visible descendants; inspect childDocuments before answering from a parent Wiki, and read relevant children with get_wiki_document. If the user asks to download an ASDM tool package, search the platform Wiki for that tool, read its Markdown with get_wiki_document, and include the exact /asdm-tools/download/{assetId} Markdown link from the document in the answer. Do not invent an asset ID or link. For external role context, resolve_external_roles first and use the confirmed internal IDs in search_knowledge. Prefer PROJECT_CONTEXT. Use STRICT when explicitly limited to a role. Ask for clarification on ambiguity. Cite only role-matched Wiki as role evidence and label GENERAL_FALLBACK as general knowledge. For STRICT with no evidence, report the empty result or pending synchronization. Use Feishu or unfiltered Wiki as general fallback only for unrestricted requests or after user approval. Wiki results include sourceUrl and downloadUrl for citations; Feishu sources need only their document URL. Submit structured recommendations when recommending Skills.")
                .tools(tool("get_current_user_context", "Get the current user's visible teams before recommending skills.",
                        schema("object", List.of()), this::currentUserContext),
                        tool("search_skills", "Search published skills by development stage, platform and OS.",
                        schema("object", List.of()), this::search),
                        tool("get_skill_detail", "Get metadata and a segment of the root skill.md.",
                                schema("object", List.of("skillKey")), this::detail),
                        tool("search_wiki_documents", "Search Wiki documents visible to the current user, or only the selected job whitelist for a document-agent run. Normal user results include sourceUrl, downloadUrl and a bounded childDocuments directory.",
                                schema("object", List.of()), this::searchWiki),
                        tool("get_wiki_document", "Read a bounded segment of a visible Wiki Markdown document. When following a search_knowledge PLATFORM result, pass audience=PLATFORM to read the approved version. Results include sourceUrl, downloadUrl and a bounded childDocuments directory.",
                                wikiReadSchema(), this::wikiDocument),
                        tool("list_wiki_children", "List direct child Wiki documents of a visible parent. Call again with a returned child documentId to drill down one level. Results include sourceUrl and downloadUrl.",
                                schema("object", List.of("documentId")), this::wikiChildren),
                        tool("get_retrieval_plan", "Read the frozen retrieval scope for the current run.",
                                schema("object",List.of()),this::getRetrievalPlan),
                        tool("refine_retrieval_task","Narrow a document task category using an exact quote from the current user message. Never changes role or capabilities.",
                                new McpSchema.JsonSchema("object",Map.of("taskId",Map.of("type","string"),
                                        "category",Map.of("type","string"),"revision",Map.of("type","integer","minimum",1),
                                        "evidence",Map.of("type","string","maxLength",100)),
                                        List.of("taskId","category","revision","evidence"),false,Map.of(),Map.of()),this::refineRetrievalTask),
                        tool("list_knowledge_catalog", "List all authorized results in the frozen task scope with stable pagination.",
                                new McpSchema.JsonSchema("object",Map.of("taskId",Map.of("type","string"),"page",Map.of("type","integer","minimum",0),
                                        "pageSize",Map.of("type","integer","minimum",1,"maximum",50),"keyword",Map.of("type","string","maxLength",200)),
                                        List.of(),false,Map.of(),Map.of()),this::listKnowledgeCatalog),
                        tool("resolve_external_roles", "Resolve an external role among Wiki visible to this run. Use only confirmed IDs for role search. Ask the user to disambiguate non-exact matches.",
                                new McpSchema.JsonSchema("object",Map.of("name",Map.of("type","string"),
                                        "sourceSystem",Map.of("type","string"),"limit",Map.of("type","integer","maximum",10)),
                                        List.of("name"),false,Map.of(),Map.of()), this::resolveExternalRoles),
                        tool("search_knowledge", "Semantically search authorized Wiki snippets and linked Skills. Pass confirmed externalRoleIds with roleMode STRICT for explicit role limits or PREFER for role results with clearly labeled general fallback. Optional knowledgeCategory: SKILL_TOOL (Skill, ASDM tools, commands), PROJECT_CONTEXT (project background), INTERNAL_KNOWLEDGE (company knowledge), FDE_WIKI (FDE area), OTHER. Use PREFER for a confident single-topic hint; it falls back across categories. Use STRICT only when the user explicitly asks to limit the category. Omit the category for mixed or uncertain questions. Category never grants access. Cite the actual Wiki title and sourceUrl. Results include sourceUrl, downloadUrl, childDocuments and childrenTruncated, plus asdmToolDownloadUrl when present.",
                                knowledgeSearchSchema(), this::searchKnowledge),
                        tool("search_feishu_documents", "Search documents visible to the current Feishu user.",
                                schema("object", List.of("query")), this::searchFeishu),
                        tool("get_feishu_document", "Read a readable Feishu Docs document. Use docType from search results; never call this for readable=false.",
                                schema("object", List.of("docId", "docType")), this::getFeishu),
                        tool("get_project_context", "Read the current virtual project and its visible document context.",
                                schema("object", List.of()), this::projectContext),
                        tool("list_project_artifacts", "List documents available in the current virtual project.",
                                schema("object", List.of()), this::projectArtifacts),
                        tool("get_project_artifact", "Read one authorized project document.",
                                schema("object", List.of("artifactId")), this::projectArtifact),
                        tool("save_artifact_draft", "Create or update the current project document draft.",
                                schema("object", List.of("artifactType", "title", "content")), this::saveProjectDraft),
                        tool("validate_artifact", "Validate the minimum structure of a project document draft.",
                                schema("object", List.of("artifactType", "content")), this::validateProjectArtifact),
                        tool("workflow_request_human_input", "Pause the current workflow Agent and ask the human one explicit question. Ask only one question at a time.",
                                schema("object", List.of("question")), this::requestHumanInput),
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
        props.put("taskId", Map.of("type", "string"));
        props.put("versionId", Map.of("type", "integer", "minimum", 1));
        props.put("developmentStage", Map.of("type", "string", "enum", Arrays.stream(DevelopmentStage.values()).map(Enum::name).toList()));
        props.put("platform", Map.of("type", "string")); props.put("osType", Map.of("type", "string"));
        props.put("skillKey", Map.of("type", "string")); props.put("path", Map.of("type", "string"));
        props.put("teamId", Map.of("type", "integer", "minimum", 1));
        props.put("documentId", Map.of("type", "integer", "minimum", 1));
        props.put("audience", Map.of("type", "string", "enum", List.of("TEAM", "PLATFORM")));
        props.put("keyword", Map.of("type", "string", "maxLength", 200)); props.put("query", Map.of("type", "string", "minLength", 1, "maxLength", 200)); props.put("docId", Map.of("type", "string", "minLength", 1, "maxLength", 512));
        props.put("docType", Map.of("type", "string", "enum", List.of("DOCX", "DOC", "SHEET", "BITABLE", "SLIDES", "MINDNOTE", "WIKI", "UNKNOWN")));
        props.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 10));
        props.put("artifactId", Map.of("type", "integer", "minimum", 1));
        props.put("revisionId", Map.of("type", "integer", "minimum", 1));
        props.put("artifactType", Map.of("type", "string", "enum", List.of("REQUIREMENT", "PRD", "ARCHITECTURE", "UI_DESIGN")));
        props.put("title", Map.of("type", "string", "minLength", 1, "maxLength", 255));
        props.put("content", Map.of("type", "string", "minLength", 1, "maxLength", 1000000));
        props.put("versionNo", Map.of("type", "integer", "minimum", 0));
        props.put("sourceArtifactIds", Map.of("type", "array", "items", Map.of("type", "integer", "minimum", 1), "maxItems", 20));
        props.put("profileKey", Map.of("type", "string", "maxLength", 64));
        props.put("agentSessionId", Map.of("type", "string", "maxLength", 128));
        props.put("agentJobId", Map.of("type", "string", "maxLength", 128));
        props.put("offset", Map.of("type", "integer", "minimum", 0)); props.put("maxBytes", Map.of("type", "integer", "minimum", 1, "maximum", MAX_BYTES));
        props.put("page", Map.of("type", "integer", "minimum", 0)); props.put("pageSize", Map.of("type", "integer", "minimum", 1, "maximum", 20));
        props.put("summary", Map.of("type", "string", "maxLength", 2000)); props.put("items", Map.of("type", "array", "maxItems", 20));
        props.put("question", Map.of("type", "string", "minLength", 1, "maxLength", 4000));
        props.put("choices", Map.of("type", "array", "items", Map.of("type", "string", "minLength", 1, "maxLength", 500), "maxItems", 10));
        return new McpSchema.JsonSchema(type, props, required, false, Map.of(), Map.of());
    }
    private McpSchema.JsonSchema wikiReadSchema() {
        Map<String,Object> props=new LinkedHashMap<>();
        props.put("taskId",Map.of("type","string"));
        props.put("documentId",Map.of("type","integer","minimum",1));
        props.put("versionId",Map.of("type","integer","minimum",1));
        props.put("offset",Map.of("type","integer","minimum",0));
        props.put("maxBytes",Map.of("type","integer","minimum",1));
        props.put("audience",Map.of("type","string","enum",List.of("PLATFORM","TEAM")));
        props.put("externalRoleIds",Map.of("type","array","items",Map.of("type","integer","minimum",1),"minItems",1,"maxItems",10,"uniqueItems",true));
        props.put("roleMode",Map.of("type","string","enum",List.of("STRICT")));
        return new McpSchema.JsonSchema("object",props,List.of("documentId"),false,Map.of(),Map.of());
    }
    private McpSchema.JsonSchema knowledgeSearchSchema() {
        Map<String,Object> props=new LinkedHashMap<>();
        props.put("taskId",Map.of("type","string"));
        props.put("query",Map.of("type","string","minLength",1,"maxLength",1000));
        props.put("developmentStage",Map.of("type","string","enum",Arrays.stream(DevelopmentStage.values()).map(Enum::name).toList()));
        props.put("knowledgeCategory",Map.of("type","string","enum",List.of("SKILL_TOOL","PROJECT_CONTEXT","INTERNAL_KNOWLEDGE","FDE_WIKI","OTHER")));
        props.put("externalRoleIds", Map.of("type","array","items",Map.of("type","integer","minimum",1),"minItems",1,"maxItems",10,"uniqueItems",true));
        props.put("roleMode",Map.of("type","string","enum",List.of("STRICT","PREFER")));
        props.put("categoryMode",Map.of("type","string","enum",List.of("PREFER","STRICT"),"default","PREFER"));
        props.put("limit",Map.of("type","integer","minimum",1,"maximum",10,"default",5));
        return new McpSchema.JsonSchema("object",props,List.of("query"),false,Map.of(),Map.of());
    }
    private McpSchema.JsonSchema recommendationSchema() {
        Map<String,Object> itemProperties=new LinkedHashMap<>();
        itemProperties.put("skillKey",Map.of("type","string","minLength",1,"maxLength",64));
        itemProperties.put("platform",Map.of("type","string","maxLength",32));
        itemProperties.put("osType",Map.of("type","string","maxLength",32));
        itemProperties.put("priority",Map.of("type","string","enum",List.of("REQUIRED","RECOMMENDED","OPTIONAL")));
        itemProperties.put("reason",Map.of("type","string","minLength",1,"maxLength",2048));
        itemProperties.put("usageExample",Map.of("type","string","maxLength",4096));
        Map<String,Object> item=Map.of("type","object","properties",itemProperties,"required",List.of("skillKey","priority","reason"),"additionalProperties",false);
        Map<String,Object> props=new LinkedHashMap<>();
        props.put("taskId",Map.of("type","string"));
        props.put("summary",Map.of("type","string","maxLength",2000));
        props.put("items",Map.of("type","array","maxItems",20,"items",item));
        props.put("citations",Map.of("type","array","maxItems",20));
        return new McpSchema.JsonSchema("object",props,List.of("summary","items"),false,Map.of(),Map.of());
    }
    private McpSchema.JsonSchema codeGraphSchema(List<String> required) {
        Map<String,Object> selectorProperties = new LinkedHashMap<>();
        selectorProperties.put("uid", Map.of("type", "string", "maxLength", 200));
        selectorProperties.put("name", Map.of("type", "string", "maxLength", 300));
        selectorProperties.put("kind", Map.of("type", "string", "maxLength", 80));
        selectorProperties.put("filePath", Map.of("type", "string", "maxLength", 500));
        var selector = Map.of("type", "object", "properties", selectorProperties, "additionalProperties", false);
        Map<String,Object> props = new LinkedHashMap<>();
        props.put("query", Map.of("type", "string", "minLength", 1, "maxLength", 500));
        props.put("repository", Map.of("type", "string", "maxLength", 128));
        props.put("target", selector); props.put("from", selector); props.put("to", selector);
        props.put("direction", Map.of("type", "string", "enum", List.of("UPSTREAM", "DOWNSTREAM")));
        props.put("depth", Map.of("type", "integer", "minimum", 1, "maximum", 10));
        props.put("maxDepth", Map.of("type", "integer", "minimum", 1, "maximum", 20));
        props.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 100));
        return new McpSchema.JsonSchema("object", props, required, false, Map.of(), Map.of());
    }
    private McpSchema.CallToolResult search(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent = run(ex, "skill.search"); String stage = str(args, "developmentStage"); String platform = normalizePlatform(str(args,"platform")), osType = normalizeOs(str(args,"osType")); int page = integer(args, "page", 0), size = Math.min(integer(args, "pageSize", 20), 20);
        var task=retrievalGuard==null?null:retrievalGuard.task(agent,"skill.search",str(args,"taskId"));
        if(task!=null){
            if(stage!=null&&!stage.equals(task.stage()))throw com.company.skillplatform.agent.application.RetrievalScopeGuard.denied();
            stage=task.stage();
            platform=lockedTarget(platform,agent.platform());
            osType=lockedTarget(osType,agent.osType());
        }
        final String targetPlatform=platform,targetOs=osType;
        var result = skills.search(null, null, null, platform, osType, "PUBLISHED", null, stage, null,
                org.springframework.data.domain.PageRequest.of(page, size, org.springframework.data.domain.Sort.by("skillKey").ascending()));
        List<Map<String,Object>> items = result.getContent().stream().filter(view->task==null||skillBelongsToRole(view.id(),task.roleId())).map(view -> {
            Map<String,Object> item = objectMapper.convertValue(view, new TypeReference<Map<String,Object>>() {});
            item.put("targetPlatform", targetPlatform == null ? "DEFAULT" : targetPlatform);
            item.put("targetOsType", targetOs == null ? "DEFAULT" : targetOs);
            item.put("detailPath", detailPath(view.skillKey(), targetPlatform, targetOs));
            item.put("selectionHint", "Use this detailPath to preserve the requested platform and operating system.");
            return item;
        }).toList();
        return ok(Map.of("target", target(platform, osType), "items", items, "page", result.getNumber(), "pageSize", result.getSize(), "total", result.getTotalElements(), "selectionHint", "Each item contains targetPlatform, targetOsType and detailPath; preserve them in the final recommendation."));
    }
    private McpSchema.CallToolResult currentUserContext(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent = run(ex, "user.context.read");
        var teams = "PLATFORM_PUBLIC_ONLY".equals(agent.knowledgeScope()) ? List.of() : wiki.visibleTeams(agent.userId());
        return ok(Map.of("userId", agent.userId(), "knowledgeScope", agent.knowledgeScope(), "teams", teams,
                "rankingRule", "Prefer skills linked from the current user's team Wiki; never infer access to another team."));
    }
    private McpSchema.CallToolResult getRetrievalPlan(io.modelcontextprotocol.server.McpSyncServerExchange ex,Map<String,Object> args) {
        var agent=run(ex,"retrieval.plan.read");
        var plan=retrievalPlans.find(agent.runRef()).orElseThrow();
        return ok(Map.of("strategy",plan.strategy(),"tasks",plan.tasks(),"roles",plan.roles(),
                "revision",retrievalPlans.revision(agent.runRef())));
    }

    private McpSchema.CallToolResult refineRetrievalTask(io.modelcontextprotocol.server.McpSyncServerExchange ex,Map<String,Object> args) {
        var agent=run(ex,"retrieval.task.refine");
        return ok(retrievalPlans.refine(agent.runRef(),required(args,"taskId"),integer(args,"revision",0),
                required(args,"category"),required(args,"evidence")));
    }

    private McpSchema.CallToolResult listKnowledgeCatalog(io.modelcontextprotocol.server.McpSyncServerExchange ex,Map<String,Object> args) {
        var agent=run(ex,"knowledge.catalog.list");
        var task=retrievalGuard.task(agent,"knowledge.catalog.list",str(args,"taskId"));
        var page=structuredCatalog.list(agent.userId(),agent.knowledgeScope(),task.roleId(),task.resourceKind(),
                task.category(),integer(args,"page",0),integer(args,"pageSize",30),str(args,"keyword"),null);
        retrievalPlans.record(agent.runRef(),task,page);
        return ok(page);
    }

    private McpSchema.CallToolResult resolveExternalRoles(io.modelcontextprotocol.server.McpSyncServerExchange ex,Map<String,Object> args) {
        var agent=run(ex,"knowledge.search");
        requireRoleSearchScope(agent);
        return ok(externalRoles.lookup(agent.userId(),agent.knowledgeScope(),required(args,"name"),
                str(args,"sourceSystem"),integer(args,"limit",10)));
    }

    private void requireRoleSearchScope(AgentRun run) {
        if(run.sessionKey()!=null || run.projectKey()!=null || (run.runRef()!=null && run.runRef().startsWith("workflow-")))
            throw new org.springframework.security.access.AccessDeniedException("External role search is unavailable in a restricted run");
    }

    private List<Long> roleIds(Map<String,Object> args) {
        Object input=args.get("externalRoleIds");
        if(input==null)return null;
        if(!(input instanceof List<?> values))throw new IllegalArgumentException("externalRoleIds must be an array");
        return values.stream().map(value -> {
            if(!(value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger))
                throw new IllegalArgumentException("externalRoleIds must contain integers");
            return Long.valueOf(value.toString());
        }).toList();
    }

    private McpSchema.CallToolResult searchKnowledge(io.modelcontextprotocol.server.McpSyncServerExchange ex,Map<String,Object> args){
        var agent=run(ex,"knowledge.search");String query=required(args,"query");String stage=str(args,"developmentStage");if(stage!=null)DevelopmentStage.valueOf(stage);
        List<Long> ids=roleIds(args);
        String roleMode=str(args,"roleMode");
        if(ids!=null||roleMode!=null)requireRoleSearchScope(agent);
        var task=retrievalGuard==null?null:retrievalGuard.task(agent,"knowledge.search",str(args,"taskId"));
        if(task!=null){
            if(task.roleId()>0){
                if(!"DOCUMENT".equals(task.resourceKind())||(ids!=null&&!ids.equals(List.of(task.roleId())))
                        ||(roleMode!=null&&!"STRICT".equals(roleMode)))throw com.company.skillplatform.agent.application.RetrievalScopeGuard.denied();
                ids=List.of(task.roleId());roleMode="STRICT";
            }else if(ids!=null||roleMode!=null)throw com.company.skillplatform.agent.application.RetrievalScopeGuard.denied();
        }
        String category=str(args,"knowledgeCategory"), categoryMode=str(args,"categoryMode");
        if(task!=null&&task.category()!=null){
            String enforcedMode="CATEGORY_PREFER".equals(task.resourceKind())?"PREFER":"STRICT";
            if((category!=null&&!task.category().equals(category))||(categoryMode!=null&&!enforcedMode.equals(categoryMode)))
                throw new org.springframework.security.access.AccessDeniedException("RETRIEVAL_SCOPE_CONFLICT");
            category=task.category();categoryMode=enforcedMode;
        }
        com.company.skillplatform.knowledge.application.KnowledgeSearchResult result;
        try { result=knowledge.search(agent.userId(),agent.knowledgeScope(),query,stage,category,categoryMode,integer(args,"limit",5),ids,roleMode,task!=null&&task.roleId()>0?task.resourceKind():null);
        } catch(com.company.skillplatform.common.application.BusinessException failure) {
            if(task==null||task.roleId()==0||!"ROLE_SEARCH_NOT_READY".equals(failure.getCode()))throw failure;
            return scopedSearchFallback(agent,task,"ROLE_INDEX_PENDING");
        }
        if(task!=null&&task.roleId()>0&&(result.retrieverUnavailable()||result.roleIndexPending()))
            return scopedSearchFallback(agent,task,result.roleIndexPending()?"ROLE_INDEX_PENDING":"SEMANTIC_UNAVAILABLE");
        Map<String,Object> response=objectMapper.convertValue(result,new TypeReference<>() {});
        List<Map<String,Object>> documents=objectMapper.convertValue(result.results(),new TypeReference<>() {});
        if(task!=null&&task.roleId()>0) documents.removeIf(document->!task.resourceKind().equals(
                wikiRoles.resolve(((Number)document.get("documentId")).longValue()).resourceType()));
        for(Map<String,Object> document:documents) {
            long id=((Number)document.get("documentId")).longValue();
            String asdmLink=asdmDownloadLink(wiki.get(id,agent.userId(),"PLATFORM".equals(document.get("teamScope"))).markdownContent());
            if(asdmLink!=null) document.put("asdmToolDownloadUrl",asdmLink);
            document.putAll(wikiLinks(id,"PLATFORM".equals(document.get("teamScope"))));if(task==null)addWikiDirectory(document,id,agent.userId(),"PLATFORM".equals(document.get("teamScope")),3,20,"STRICT".equals(result.roleMode())?ids:null);
        }
        response.put("results",documents);return ok(response);
    }
    private McpSchema.CallToolResult scopedSearchFallback(AgentRun agent,
            com.company.skillplatform.agent.application.RetrievalPlanner.Task task,String reason) {
        var directory=structuredCatalog.list(agent.userId(),agent.knowledgeScope(),task.roleId(),
                task.resourceKind(),task.category(),0,20);
        retrievalPlans.record(agent.runRef(),task,directory);
        return ok(Map.of("status",reason,"results",List.of(),"catalog",directory,
                "message","语义索引暂不可用。可从当前任务目录选择文档，按返回的 audience 和 versionId 读取原文。"));
    }

    private McpSchema.CallToolResult detail(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent = run(ex, "skill.detail"); String key = required(args,"skillKey"); var view = skills.get(key); var task=retrievalGuard==null?null:retrievalGuard.task(agent,"skill.detail",str(args,"taskId"));
        requireScopedSkill(task,view.id(),view.developmentStage());
        String content = readText(key, "skill.md", integer(args,"offset",0), bounded(args),scopedVersion(task,optionalLong(args,"versionId")));
        var docs = "PLATFORM_PUBLIC_ONLY".equals(agent.knowledgeScope()) ? List.of() : task!=null?List.of():wiki.search(agent.userId(), null, key, null, PageRequest.of(0, 20)).items().stream().map(this::linkedWiki).toList();
        String platform = normalizePlatform(str(args,"platform")), osType = normalizeOs(str(args,"osType"));
        return ok(Map.of("skill", view, "target", target(platform, osType), "detailPath", detailPath(key, platform, osType), "file", "skill.md", "content", content, "offset", integer(args,"offset",0), "hasMore", content.length() >= bounded(args), "wikiDocuments", docs));
    }
    private McpSchema.CallToolResult searchWiki(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent = run(ex, "wiki.search");
        Long workflowId = workflowRunId(agent);
        if (workflowId != null) return workflowWikiSearch(workflowId, str(args, "keyword"));
        if (agent.sessionKey() != null) return ok(Map.of("items", documentAgents.searchSelectedWiki(agent.sessionKey(), agent.runRef(), str(args,"keyword")), "page", 0, "size", 10));
        var task=retrievalGuard==null?null:retrievalGuard.task(agent,"wiki.search",str(args,"taskId"));
        if(task!=null){
            if(!"DOCUMENT".equals(task.resourceKind())||args.get("teamId")!=null||args.get("skillKey")!=null)
                throw new org.springframework.security.access.AccessDeniedException("RETRIEVAL_SCOPE_CONFLICT");
            return scopedCatalog(agent,task,args,null);
        }
        requireUserKnowledge(agent); var result = wiki.search(agent.userId(), optionalLong(args,"teamId"), str(args,"skillKey"), str(args,"keyword"), PageRequest.of(integer(args,"page",0), Math.min(integer(args,"pageSize",20),20)));
        return ok(Map.of("items", result.items().stream().map(document -> {
            Map<String,Object> item=linkedWiki(document);addWikiDirectory(item,document.id(),agent.userId(),false,1,5);return item;
        }).toList(), "page", result.page(), "size", result.size(), "totalElements", result.totalElements(), "totalPages", result.totalPages()));
    }
    private McpSchema.CallToolResult wikiDocument(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent = run(ex, "wiki.read"); long id = longRequired(args,"documentId"); int offset = integer(args,"offset",0); int max = bounded(args);
        List<Long> ids=roleIds(args);
        String mode=str(args,"roleMode");
        if(ids!=null||mode!=null){
            requireRoleSearchScope(agent);
            KnowledgeSearchService.validateRoleOptions(ids,mode);
            if(mode!=null&&!"STRICT".equals(mode))throw new IllegalArgumentException("Wiki reads require STRICT role mode");

        }
        Long workflowId = workflowRunId(agent);
        if (workflowId != null) return workflowWikiRead(workflowId, id, offset, max);
        if (agent.sessionKey() != null) return ok(objectMapper.convertValue(documentAgents.readSelectedWiki(agent.sessionKey(), agent.runRef(), id, offset, max), new TypeReference<Map<String,Object>>() {}));
        var task=retrievalGuard==null?null:retrievalGuard.task(agent,"wiki.read",str(args,"taskId"));
        if(task!=null&&task.roleId()>0){
            if(ids!=null&&!ids.equals(List.of(task.roleId())))throw new org.springframework.security.access.AccessDeniedException("RETRIEVAL_SCOPE_CONFLICT");
            ids=List.of(task.roleId());
        }
        if(task==null)requireUserKnowledge(agent);
        if("PLATFORM_PUBLIC_ONLY".equals(agent.knowledgeScope())&&"TEAM".equals(str(args,"audience")))
            throw com.company.skillplatform.agent.application.RetrievalScopeGuard.denied();
        boolean platformAudience=task!=null&&"ASDM-TOOL".equals(task.resourceKind())||"PLATFORM_PUBLIC_ONLY".equals(agent.knowledgeScope()) ||"PLATFORM".equals(str(args,"audience"));var document = wiki.get(id, agent.userId(),platformAudience); String text = document.markdownContent(); String content = offset >= text.length() ? "" : text.substring(offset, Math.min(text.length(), offset + max));
        Map<String,Object> response=new LinkedHashMap<>(Map.of("documentId", id, "title", document.title(), "skills", document.skills(), "content", content, "offset", offset, "hasMore", offset + content.length() < text.length()));
        Long requestedVersion=optionalLong(args,"versionId");
        if(requestedVersion!=null){
            String sql=platformAudience?
                    "SELECT approved_revision_id FROM wiki_platform_snapshot WHERE document_id=?":
                    "SELECT current_revision_id FROM wiki_document WHERE id=?";
            var selected=jdbc.query(sql,(rs,row)->rs.getObject(1,Long.class),id).stream().findFirst().orElse(null);
            if(!requestedVersion.equals(selected)||!Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM wiki_document_revision WHERE id=? AND document_id=? AND revision_no=?)
                    """,Boolean.class,requestedVersion,id,document.revisionNo())))
                throw new com.company.skillplatform.common.application.BusinessException("RETRIEVAL_VERSION_CHANGED",
                        "Wiki 发布版本已变化，请刷新目录后重试",org.springframework.http.HttpStatus.CONFLICT);
        }
        response.put("parentId", document.parentId());
        String asdmLink = asdmDownloadLink(text);
        if (asdmLink != null) response.put("asdmToolDownloadUrl", asdmLink);
        response.put("knowledgeCategory",document.knowledgeCategory());
        if(wikiRoles!=null) {
            var metadata=wikiRoles.resolve(id);
            if(task!=null)retrievalGuard.requireWiki(task,metadata,document.knowledgeCategory());
            var effectiveIds=ids;
            if(effectiveIds!=null&&metadata.roles().stream().noneMatch(role->effectiveIds.contains(role.id())))
                throw new org.springframework.security.access.AccessDeniedException("Wiki does not match the selected external roles");
            response.putAll(metadata.toPayload());
        }
        response.putAll(wikiLinks(id,platformAudience));if(task==null)addWikiDirectory(response,id,agent.userId(),platformAudience,3,20,ids);return ok(response);
    }
    private McpSchema.CallToolResult wikiChildren(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent = run(ex, "wiki.read");
        long id = longRequired(args, "documentId");
        var task=retrievalGuard==null?null:retrievalGuard.task(agent,"wiki.read",str(args,"taskId"));
        if(task!=null){
            if(task.roleId()==0)throw com.company.skillplatform.agent.application.RetrievalScopeGuard.denied();
            boolean platform="PLATFORM_PUBLIC_ONLY".equals(agent.knowledgeScope())||"PLATFORM".equals(str(args,"audience"));
            var parent=wiki.get(id,agent.userId(),platform);
            retrievalGuard.requireWiki(task,wikiRoles.resolve(id),parent.knowledgeCategory());
            return scopedCatalog(agent,task,args,id);
        }
        requireUserKnowledge(agent);
        return ok(Map.of("parentId", id, "items", wiki.children(id, agent.userId()).stream().map(document -> {
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("documentId", document.id()); item.put("title", document.title());
            item.put("parentId", document.parentId());
            item.put("revisionNo", document.revisionNo()); item.putAll(wikiLinks(document.id()));
            return item;
        }).toList()));
    }
    private McpSchema.CallToolResult scopedCatalog(AgentRun agent,
            com.company.skillplatform.agent.application.RetrievalPlanner.Task task,Map<String,Object> args,Long parentId) {
        String scope="PLATFORM".equals(str(args,"audience"))?"PLATFORM_PUBLIC_ONLY":agent.knowledgeScope();
        var page=structuredCatalog.list(agent.userId(),scope,task.roleId(),task.resourceKind(),task.category(),
                integer(args,"page",0),integer(args,"pageSize",30),str(args,"keyword"),parentId);
        retrievalPlans.record(agent.runRef(),task,page);
        return ok(page);
    }
    private McpSchema.CallToolResult searchFeishu(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent = run(ex, "feishu.search"); requireUserKnowledge(agent); if(retrievalGuard!=null)retrievalGuard.task(agent,"feishu.search",str(args,"taskId")); String query = required(args, "query");
        Long workflowId = workflowRunId(agent);
        if (workflowId != null) return workflowFeishuSearch(workflowId, query);
        var budget = feishuGuard.beforeSearch(agent.runRef());
        if (!budget.allowed()) return ok(Map.of("status", budget.code(), "query", query, "message", "检索预算已用尽，请基于已获得的资料直接回答。"));
        int limit = Math.min(integer(args, "limit", 10), 10), offset = Math.min(integer(args, "offset", 0), 10_000);
        try {
            return ok(Map.of("query", query, "offset", offset, "limit", limit,
                    "result", withFeishuRetry(() -> feishu.search(agent.userId(), query, limit, offset))));
        } catch (RuntimeException failure) {
            return feishuFailure("search", query, null, failure);
        }
    }
    private McpSchema.CallToolResult getFeishu(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent = run(ex, "feishu.read"); requireUserKnowledge(agent); if(retrievalGuard!=null)retrievalGuard.task(agent,"feishu.read",str(args,"taskId")); String docId = required(args, "docId"); String docType = required(args, "docType");
        Long workflowId = workflowRunId(agent);
        if (workflowId != null && !workflowContextAllows(workflowId, "FEISHU", null, docId, docType)) throw new BusinessException("WORKFLOW_CONTEXT_DENIED", "The Feishu document was not selected for this workflow run", org.springframework.http.HttpStatus.FORBIDDEN);
        if (agent.sessionKey() != null && !documentAgents.allowsFeishu(agent.sessionKey(), docId, docType))
            throw new BusinessException("DOCUMENT_AGENT_CONTEXT_DENIED", "The Feishu document was not selected for the active document job", org.springframework.http.HttpStatus.FORBIDDEN);
        var budget = feishuGuard.beforeRead(agent.runRef(), docId);
        if (!budget.allowed()) return ok(Map.of("status", budget.code(), "docId", docId, "message", "该文档已读取过或检索预算已用尽，请基于已有资料直接回答。"));
        try {
            int offset = integer(args, "offset", 0), maxBytes = bounded(args);
            var result = withFeishuRetry(() -> feishu.fetch(agent.userId(), docId, docType, offset, maxBytes));
            feishuGuard.readSucceeded(agent.runRef(), docId);
            return ok(Map.of("docId", docId, "result", result));
        } catch (RuntimeException failure) {
            feishuGuard.readFailed(agent.runRef(), docId);
            return feishuFailure("read", null, docId, failure);
        }
    }
    private Long workflowRunId(AgentRun agent) { if (agent.runRef()==null || !agent.runRef().startsWith("workflow-")) return null; try { return Long.valueOf(agent.runRef().substring("workflow-".length(), agent.runRef().indexOf("-agent-"))); } catch (Exception ignored) { return null; } }
    private boolean workflowContextAllows(long runId,String kind,Long wikiId,String docId,String docType) { Integer n=jdbc.queryForObject("select count(*) from workflow_run_context_snapshot where workflow_run_id=? and context_kind=? and ((? is not null and wiki_document_id=?) or (? is not null and feishu_doc_id=? and feishu_doc_type=?))",Integer.class,runId,kind,wikiId,wikiId,docId,docId,docType); return n!=null&&n>0; }
    private McpSchema.CallToolResult workflowWikiSearch(long runId,String keyword) { String q=keyword==null?"":keyword; List<Map<String,Object>> items=jdbc.query("select s.wiki_document_id,p.approved_title,r.revision_no from workflow_run_context_snapshot s join wiki_document d on d.id=s.wiki_document_id join wiki_platform_snapshot p on p.document_id=d.id join wiki_document_revision r on r.id=p.approved_revision_id where s.workflow_run_id=? and s.context_kind='PLATFORM_WIKI' and d.status='ACTIVE' and d.platform_visible=1 and (?='' or p.approved_title like concat('%',?,'%')) order by p.approved_title",(r,n)->Map.of("documentId",r.getLong(1),"title",r.getString(2),"revisionNo",r.getObject(3)),runId,q,q); return ok(Map.of("items",items,"page",0,"size",items.size(),"totalElements",items.size())); }
    private McpSchema.CallToolResult workflowWikiRead(long runId,long documentId,int offset,int max) { Map<String,Object> row=jdbc.queryForMap("select r.revision_no,p.approved_title,r.markdown_content from workflow_run_context_snapshot s join wiki_document d on d.id=s.wiki_document_id join wiki_platform_snapshot p on p.document_id=d.id join wiki_document_revision r on r.id=p.approved_revision_id where s.workflow_run_id=? and s.context_kind='PLATFORM_WIKI' and s.wiki_document_id=? and d.status='ACTIVE' and d.platform_visible=1",runId,documentId); String text=String.valueOf(row.get("markdown_content"));String content=offset>=text.length()?"":text.substring(offset,Math.min(text.length(),offset+max));return ok(Map.of("documentId",documentId,"title",row.get("approved_title"),"revisionNo",row.get("revision_no"),"content",content,"offset",offset,"hasMore",offset+content.length()<text.length())); }
    private McpSchema.CallToolResult workflowFeishuSearch(long runId,String query) { List<Map<String,Object>> items=jdbc.query("select feishu_doc_id,feishu_doc_type,title from workflow_run_context_snapshot where workflow_run_id=? and context_kind='FEISHU' and (?='' or title like concat('%',?,'%')) order by title",(r,n)->Map.of("docId",r.getString(1),"docType",r.getString(2),"title",r.getString(3),"readable",true),runId,query==null?"":query,query==null?"":query);return ok(Map.of("query",query,"result",Map.of("items",items))); }
    private McpSchema.CallToolResult requestHumanInput(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent=run(ex,"workflow.human_input.request"); Long workflowId=workflowRunId(agent); Long agentRunId=workflowAgentRunId(agent);
        if(workflowId==null||agentRunId==null)throw new BusinessException("WORKFLOW_CONTEXT_REQUIRED","Workflow context is required",org.springframework.http.HttpStatus.FORBIDDEN);
        String question=required(args,"question").trim(); Object choices=args.get("choices"); String choicesJson=choices==null?null:write(choices);
        Map<String,Object> ids=jdbc.queryForMap("select ar.session_id,ar.stage_run_id,ar.status,ar.runtime_conversation_id from agent_workflow_run ar join stage_run sr on sr.id=ar.stage_run_id where ar.id=? and sr.workflow_run_id=?",agentRunId,workflowId);
        String agentStatus=String.valueOf(ids.get("status"));
        if(!Set.of("STARTING","RUNNING","WAITING_HUMAN").contains(agentStatus))throw new BusinessException("WORKFLOW_AGENT_NOT_ACTIVE","The workflow Agent is no longer active",org.springframework.http.HttpStatus.CONFLICT);
        List<Map<String,Object>> pending=jdbc.queryForList("select id,question_text,choices_json from workflow_human_question where workflow_run_id=? and status in ('PENDING','ANSWER_SUBMITTED') order by id desc limit 1",workflowId);
        signalGatewayWaiting(agentRunId,ids.get("runtime_conversation_id"));
        if(!pending.isEmpty())return ok(Map.of("questionId",pending.get(0).get("id"),"status","WAITING_HUMAN","question",pending.get(0).get("question_text"),"instruction","Wait for the human answer and do not continue this turn."));
        jdbc.update("insert into workflow_human_question(time_created,time_updated,workflow_run_id,stage_run_id,agent_session_id,agent_run_id,question_text,choices_json,status) values(now(3),now(3),?,?,?,?,?,?,'PENDING')",workflowId,ids.get("stage_run_id"),ids.get("session_id"),agentRunId,question,choicesJson);
        Long questionId=jdbc.queryForObject("select id from workflow_human_question where agent_run_id=? order by id desc limit 1",Long.class,agentRunId);
        int changed=jdbc.update("update agent_workflow_run set status='WAITING_HUMAN',time_updated=now(3) where id=? and status in ('STARTING','RUNNING','WAITING_HUMAN')",agentRunId);
        if(changed!=1)throw new BusinessException("WORKFLOW_AGENT_NOT_ACTIVE","The workflow Agent stopped before the human question was recorded",org.springframework.http.HttpStatus.CONFLICT);
        jdbc.update("update agent_workflow_session set status='WAITING_HUMAN',time_updated=now(3) where id=?",ids.get("session_id"));
        jdbc.update("update stage_run set status='HUMAN_REQUIRED',time_updated=now(3) where id=?",ids.get("stage_run_id"));
        jdbc.update("update workflow_run set status='WAITING_HUMAN',time_updated=now(3) where id=?",workflowId);
        Map<String,Object> data=new LinkedHashMap<>();data.put("questionId",questionId);data.put("question",question);data.put("choices",choices==null?List.of():choices);data.put("stageId",ids.get("stage_run_id"));data.put("agentSessionId",ids.get("session_id"));data.put("agentRunId",agentRunId);data.put("agentNodeKey",jdbc.queryForObject("select node_key from agent_workflow_run where id=?",String.class,agentRunId));
        var stored=workflowEvents.append("human-question-"+questionId,workflowId,((Number)ids.get("stage_run_id")).longValue(),((Number)ids.get("session_id")).longValue(),agentRunId,"human.question.created",data);workflowSse.publish(workflowId,stored);
        return ok(Map.of("questionId",questionId,"status","WAITING_HUMAN","question",question,"instruction","Wait for the human answer and do not continue this turn."));
    }
    private McpSchema.CallToolResult codeGraphOverview(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent = run(ex, "code_graph.overview");
        return ok(codeGraphPolicy.execute(requiredWorkflowAgentRunId(agent), agent.userId(), "overview", null, Map.of()));
    }
    private McpSchema.CallToolResult codeGraphQuery(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        return codeGraph(ex, args, "query", "code_graph.query");
    }
    private McpSchema.CallToolResult codeGraphContext(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        return codeGraph(ex, args, "context", "code_graph.context");
    }
    private McpSchema.CallToolResult codeGraphImpact(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        return codeGraph(ex, args, "impact", "code_graph.impact");
    }
    private McpSchema.CallToolResult codeGraphTrace(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        return codeGraph(ex, args, "trace", "code_graph.trace");
    }
    private McpSchema.CallToolResult codeGraphRouteMap(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        return codeGraph(ex, args, "route-map", "code_graph.route_map");
    }
    private McpSchema.CallToolResult codeGraph(io.modelcontextprotocol.server.McpSyncServerExchange ex,
                                               Map<String,Object> args, String operation, String capability) {
        var agent = run(ex, capability);
        var parameters = new LinkedHashMap<String,Object>(args == null ? Map.of() : args);
        Object repository = parameters.remove("repository");
        var result = codeGraphPolicy.execute(requiredWorkflowAgentRunId(agent), agent.userId(), operation,
                repository == null ? null : String.valueOf(repository), parameters);
        return ok(result);
    }
    private long requiredWorkflowAgentRunId(AgentRun agent) {
        Long id = workflowAgentRunId(agent);
        if (id == null) throw new BusinessException("WORKFLOW_CONTEXT_REQUIRED", "Workflow context is required", org.springframework.http.HttpStatus.FORBIDDEN);
        return id;
    }
    private void signalGatewayWaiting(Long agentRunId,Object conversationId) {
        String conversation=conversationId==null?"":String.valueOf(conversationId).trim();
        if(conversation.isEmpty()||"null".equalsIgnoreCase(conversation))throw new BusinessException("WORKFLOW_RUNTIME_NOT_READY","The workflow runtime conversation is not ready",org.springframework.http.HttpStatus.CONFLICT);
        workflowGateway.post().uri("/internal/v1/conversations/{conversationId}/wait-human",conversation)
                .header("X-SMS-Service-Token",workflowServiceToken).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("requestId","human-wait-"+agentRunId+"-"+UUID.randomUUID())).retrieve().toBodilessEntity();
    }
    private Long workflowAgentRunId(AgentRun agent) { if(agent.runRef()==null)return null;try{return Long.valueOf(agent.runRef().substring(agent.runRef().indexOf("-agent-")+7));}catch(Exception ignored){return null;} }
    private McpSchema.CallToolResult projectContext(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent = run(ex, "project.context.read"); requireProject(agent);
        var project = projectControl.get(agent.projectKey(), agent.userId());
        var docs = projectControl.listDocuments(agent.projectKey(), agent.userId(), PageRequest.of(0, 50)).items();
        return ok(Map.of("project", project, "project_id", agent.projectKey(), "documents", docs, "externalResources", List.of()));
    }
    private McpSchema.CallToolResult projectArtifacts(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent = run(ex, "project.artifact.list"); requireProject(agent);
        return ok(Map.of("project_id", agent.projectKey(), "artifacts", projectControl.listDocuments(agent.projectKey(), agent.userId(), PageRequest.of(0, 50)).items()));
    }
    private McpSchema.CallToolResult projectArtifact(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent = run(ex, "project.artifact.read"); requireProject(agent);
        long id = longRequired(args, "artifactId");
        Object revision = args.get("revisionId");
        if (revision == null) return ok(projectControl.getDocument(agent.projectKey(), id, agent.userId()));
        return ok(Map.of("artifactId", id, "revision",
                projectControl.getDocumentRevision(agent.projectKey(), id, Long.valueOf(String.valueOf(revision)), agent.userId())));
    }
    private McpSchema.CallToolResult saveProjectDraft(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent = run(ex, "project.artifact.write"); requireProject(agent);
        String type = required(args, "artifactType").toUpperCase(Locale.ROOT), title = required(args, "title"), content = required(args, "content");
        Long artifactId = args.get("artifactId") == null ? null : Long.valueOf(String.valueOf(args.get("artifactId")));
        String profile = str(args, "profileKey");
        ProjectControlService.DocumentView saved = documentAgents.saveAgentDraft(agent, type, title, content, artifactId,
                args.get("versionNo") == null ? null : Integer.parseInt(String.valueOf(args.get("versionNo"))),
                args.get("skillSnapshots"), args.get("assumptions"), args.get("openQuestions"), longList(args.get("sourceArtifactIds")));
        return ok(Map.of("saved", true, "artifact", saved));
    }
    private McpSchema.CallToolResult validateProjectArtifact(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent = run(ex, "project.artifact.validate"); requireProject(agent); String type = required(args, "artifactType").toUpperCase(Locale.ROOT), content = required(args, "content");
        List<String> required = switch (type) { case "REQUIREMENT" -> List.of("背景", "目标", "验收"); case "PRD" -> List.of("问题", "用户", "功能", "验收"); case "ARCHITECTURE" -> List.of("上下文", "组件", "数据", "风险"); case "UI_DESIGN" -> List.of("信息架构", "用户流程", "页面"); default -> throw new IllegalArgumentException("invalid artifact type"); };
        List<String> missing = required.stream().filter(s -> !content.contains(s)).toList();
        return ok(Map.of("valid", missing.isEmpty(), "artifact_type", type, "missing_sections", missing));
    }
    private void requireProject(com.company.skillplatform.agent.domain.AgentRun agent) { if (agent.projectKey() == null) throw new com.company.skillplatform.common.application.BusinessException("PROJECT_CONTEXT_REQUIRED", "Project agent context is required", org.springframework.http.HttpStatus.FORBIDDEN); }
    private List<Long> longList(Object value) { if (!(value instanceof List<?> values)) return null; return values.stream().map(v -> Long.valueOf(String.valueOf(v))).toList(); }

    private <T> T withFeishuRetry(java.util.function.Supplier<T> operation) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try { return operation.get(); }
            catch (RuntimeException failure) {
                last = failure;
                if (!retryableFeishuFailure(failure) || attempt == 2) break;
                try { Thread.sleep(150L * (1L << attempt)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interruptedFailure();
                }
            }
        }
        throw last == null ? new IllegalStateException("Feishu operation failed") : last;
    }

    private boolean retryableFeishuFailure(RuntimeException failure) {
        if (!(failure instanceof BusinessException business)) return true;
        return Set.of("FEISHU_UPSTREAM_ERROR", "FEISHU_RATE_LIMITED", "FEISHU_TOOL_FAILED").contains(business.getCode())
                && business.getStatus().is5xxServerError();
    }

    private RuntimeException interruptedFailure() {
        return new BusinessException("FEISHU_UPSTREAM_ERROR", "Feishu document service was interrupted", org.springframework.http.HttpStatus.BAD_GATEWAY);
    }

    private McpSchema.CallToolResult feishuFailure(String operation, String query, String docId, RuntimeException failure) {
        String code = failure instanceof BusinessException business ? business.getCode() : "FEISHU_UPSTREAM_ERROR";
        boolean retryable = retryableFeishuFailure(failure);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("status", code);
        result.put("retryable", retryable);
        result.put("operation", operation);
        if (query != null) result.put("query", query);
        if (docId != null) result.put("docId", docId);
        result.put("message", retryable ? "飞书资料暂时不可用，请基于已有资料继续回答。" : "该飞书资料当前无法读取，请跳过并继续处理其他资料。");
        return ok(result);
    }
    private McpSchema.CallToolResult file(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var actor=run(ex, "skill.file.read"); String key = required(args,"skillKey"), path = required(args,"path"); if (path.startsWith("/") || path.contains("..")) throw new IllegalArgumentException("unsafe path");
        var task=retrievalGuard==null?null:retrievalGuard.task(actor,"skill.file.read",str(args,"taskId"));
        var view=skills.get(key);
        requireScopedSkill(task,view.id(),view.developmentStage());
        String content = readText(key, path, integer(args,"offset",0), bounded(args),scopedVersion(task,optionalLong(args,"versionId")));
        return ok(Map.of("skillKey", key, "path", path, "content", content, "offset", integer(args,"offset",0), "hasMore", content.length() >= bounded(args)));
    }
    private McpSchema.CallToolResult submit(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var run = run(ex, "recommendation.submit"); Object items = args.get("items"); if (!(items instanceof List<?> list) || list.size() > 20) throw new IllegalArgumentException("items must contain 0..20 recommendations");
        var task=retrievalGuard==null?null:retrievalGuard.task(run,"recommendation.submit",str(args,"taskId"));
        String runPlatform=com.company.skillplatform.agent.application.AgentRequestTarget.optionalPlatform(run.platform());String runOsType=com.company.skillplatform.agent.application.AgentRequestTarget.optionalOsType(run.osType());
        List<Map<String,Object>> accepted = new ArrayList<>(); for (Object item : list) { if (!(item instanceof Map<?,?> m)) throw new IllegalArgumentException("invalid item"); String key = String.valueOf(m.get("skillKey")); var view = skills.get(key); requireScopedSkill(task,view.id(),view.developmentStage()); if (view.status() != com.company.skillplatform.skill.domain.SkillStatus.ACTIVE || view.latestPublishedVersion() == null) continue;var trustedVersion=versions.findFirstBySkillIdAndLifecycleStatusInOrderByPublishedAtDesc(view.id(),List.of(LifecycleStatus.PUBLISHED,LifecycleStatus.DEPRECATED)).orElse(null);if(trustedVersion==null||!view.latestPublishedVersion().equals(trustedVersion.getVersion()))continue; if(task!=null) requireArtifact(trustedVersion.getId(),runPlatform,runOsType); Object priority=m.get("priority"), reason=m.get("reason"); String p=priority==null?"RECOMMENDED":String.valueOf(priority).toUpperCase(); if (!Set.of("REQUIRED","RECOMMENDED","OPTIONAL").contains(p)) throw new IllegalArgumentException("invalid priority"); Map<String,Object>trusted=new LinkedHashMap<>();trusted.put("skillKey",key);trusted.put("displayName",view.displayName());trusted.put("description",view.description());trusted.put("version",trustedVersion.getVersion());trusted.put("versionId",trustedVersion.getId());trusted.put("developmentStage",view.developmentStage());trusted.put("priority",p);trusted.put("reason",reason==null?"":String.valueOf(reason));if(runPlatform!=null)trusted.put("platform",runPlatform);if(runOsType!=null)trusted.put("osType",runOsType);trusted.put("detailPath",detailPath(key,runPlatform,runOsType));accepted.add(trusted); }
        if(task!=null&&accepted.size()!=list.size())throw com.company.skillplatform.agent.application.RetrievalScopeGuard.denied();
        accepted=accepted.stream().map(com.company.skillplatform.agent.application.AgentAnswerPresentation::recommendationItem).toList();
        String summary = args.get("summary") == null ? "" : com.company.skillplatform.agent.application.AgentAnswerPresentation.clean(String.valueOf(args.get("summary")));
        String status=accepted.size()==list.size()?"VALID":accepted.isEmpty()?"EMPTY":"PARTIALLY_VALID";
        Map<String,Object> result = Map.of("accepted", true, "runRef", run.runRef(), "summary", summary, "status",status,"items", accepted);
        String payload=write(result);var existing=recommendations.findByRunRef(run.runRef());
        if(existing.isPresent()&&!existing.get().getPayload().equals(payload))throw new IllegalArgumentException("recommendation already submitted");
        if(existing.isEmpty()){var persistent=persistentRuns.findByRunKey(run.runRef()).orElse(null);recommendations.save(new AgentRecommendationEntity(run.runRef(),persistent,summary,status,payload));events.publish(run.runRef(),"recommendation.completed",new LinkedHashMap<>(result));}
        return ok(result);
    }
    private com.company.skillplatform.agent.domain.AgentRun run(io.modelcontextprotocol.server.McpSyncServerExchange ex, String capability) { HttpServletRequest req=(HttpServletRequest)ex.transportContext().get("request"); String h=req==null?null:req.getHeader("Authorization"); if(h==null||!h.startsWith("Bearer ")) throw new IllegalArgumentException("agent token required"); var run=runs.require(h.substring(7)); runs.requireCapability(run, capability); if(retrievalGuard!=null)retrievalGuard.authorize(run,capability); var authorities=new java.util.ArrayList<org.springframework.security.core.authority.SimpleGrantedAuthority>(); authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("skill:browse")); authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("agent:mcp")); if("PLATFORM_PUBLIC_ONLY".equals(run.knowledgeScope())) authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("agent:platform-public")); var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(run.userId(), null, authorities); org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth); return run; }
    private void requireUserKnowledge(com.company.skillplatform.agent.domain.AgentRun run) { if("PLATFORM_PUBLIC_ONLY".equals(run.knowledgeScope())) throw new org.springframework.security.access.AccessDeniedException("This Agent session is restricted to public platform knowledge"); }
    private String readText(String key,String path,int offset,int max,Long versionId) {
        var view=skills.get(key);
        SkillVersionEntity v=versionId==null?versions.findFirstBySkillIdAndLifecycleStatusInOrderByPublishedAtDesc(view.id(), List.of(LifecycleStatus.PUBLISHED)).orElseThrow():
                versions.findById(versionId).filter(item->item.getSkill().getId().equals(view.id())
                        &&view.latestPublishedVersion()!=null&&view.latestPublishedVersion().equals(item.getVersion())
                        &&Set.of(LifecycleStatus.PUBLISHED,LifecycleStatus.DEPRECATED).contains(item.getLifecycleStatus()))
                        .orElseThrow(com.company.skillplatform.agent.application.RetrievalScopeGuard::denied); try(InputStream in=storage.get(v.getSourceObjectKey()); ZipInputStream zip=new ZipInputStream(in, StandardCharsets.UTF_8)){ ZipEntry e; while((e=zip.getNextEntry())!=null){ if(e.isDirectory()||!e.getName().equalsIgnoreCase(path)) continue; byte[] b=zip.readNBytes(MAX_BYTES+1); if(b.length>MAX_BYTES) throw new IllegalArgumentException("file too large"); String s=new String(b, StandardCharsets.UTF_8); return offset>=s.length()?"":s.substring(offset, Math.min(s.length(), offset+max)); } throw new IllegalArgumentException("SKILL_FILE_NOT_FOUND"); } catch(Exception e){ throw new IllegalArgumentException(e.getMessage(), e); } }
    private String lockedTarget(String requested,String frozen){
        if(requested!=null&&frozen!=null&&!requested.equals(frozen))
            throw com.company.skillplatform.agent.application.RetrievalScopeGuard.denied();
        return frozen==null?requested:frozen;
    }

    private boolean skillBelongsToRole(long skillId,long roleId) {
        if(roleId==0)return true;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM external_role er JOIN external_role_resource rr ON rr.role_id=er.id
                JOIN external_resource r ON r.id=rr.resource_id AND r.resource_type='SKILL'
                JOIN external_resource_version ev ON ev.resource_id=r.id
                JOIN external_import_record ir ON ir.resource_version_id=ev.id AND ir.result='SUCCESS' AND ir.target_type='SKILL'
                JOIN skill s ON s.id=ir.target_id AND s.latest_published_version_id=ir.draft_version_id
                WHERE er.id=? AND er.status='ACTIVE' AND s.id=?)
                """,Boolean.class,roleId,skillId));
    }

    private void requireScopedSkill(com.company.skillplatform.agent.application.RetrievalPlanner.Task task,long skillId,Object stage){
        if(task!=null&&((task.stage()!=null&&!task.stage().equals(String.valueOf(stage)))
                ||(task.objectId()!=null&&task.objectId()!=skillId)||!skillBelongsToRole(skillId,task.roleId())))
            throw com.company.skillplatform.agent.application.RetrievalScopeGuard.denied();
    }

    private Long scopedVersion(com.company.skillplatform.agent.application.RetrievalPlanner.Task task,Long requested) {
        if(task==null||task.versionId()==null)return requested;
        if(requested!=null&&!task.versionId().equals(requested))
            throw com.company.skillplatform.agent.application.RetrievalScopeGuard.denied();
        return task.versionId();
    }

    private void requireArtifact(long versionId,String platform,String os){
        if(!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM skill_artifact a JOIN platform p ON p.id=a.platform_id
                   WHERE a.skill_version_id=? AND a.status='AVAILABLE' AND (? IS NULL OR p.platform_key=?)
                     AND (? IS NULL OR a.os_type=? OR a.os_type='ANY'))
                """,Boolean.class,versionId,platform,platform,os,os)))
            throw com.company.skillplatform.agent.application.RetrievalScopeGuard.denied();
    }
    private McpSchema.CallToolResult ok(Object value){ return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(write(value))), false, value, Map.of()); }
    private String write(Object v){ try{return objectMapper.writeValueAsString(v);}catch(Exception e){return "{}";} }
    private Map<String,Object> target(String platform, String osType) {
        Map<String,Object> target = new LinkedHashMap<>();
        target.put("platform", platform == null ? "DEFAULT" : platform);
        target.put("osType", osType == null ? "DEFAULT" : osType);
        return target;
    }
    private String detailPath(String key, String platform, String osType) {
        String path = key == null ? "/skills/{skillKey}" : "/skills/" + key;
        List<String> query = new ArrayList<>();
        if (platform != null) query.add("platform=" + platform);
        if (osType != null) query.add("osType=" + osType);
        return webBaseUrl + path + (query.isEmpty() ? "" : "?" + String.join("&", query));
    }
    private Map<String,Object> linkedWiki(WikiDocumentService.DocumentView document) {
        Map<String,Object> response=objectMapper.convertValue(document,new TypeReference<>() {});
        String asdmLink = asdmDownloadLink(document.markdownContent());
        if (asdmLink != null) response.put("asdmToolDownloadUrl", asdmLink);
        response.putAll(wikiLinks(document.id()));return response;
    }
    private String asdmDownloadLink(String markdown) {
        if (markdown == null) return null;
        var matcher=java.util.regex.Pattern.compile("https?://[^\\s)]+/asdm-tools/download/[1-9][0-9]*").matcher(markdown);
        return matcher.find() ? matcher.group() : null;
    }
    private Map<String,Object> wikiLinks(long documentId) {
        return wikiLinks(documentId,false);
    }
    private Map<String,Object> wikiLinks(long documentId,boolean platformAudience) {
        String page=webBaseUrl+"/wiki?documentId="+documentId+(platformAudience?"&audience=PLATFORM":"");
        return Map.of("sourceUrl",page,"downloadUrl",page+"&download=1");
    }
    private void addWikiDirectory(Map<String,Object> response,long documentId,long userId,boolean platformOnly,int depth,int limit) {
        addWikiDirectory(response,documentId,userId,platformOnly,depth,limit,null);
    }

    private void addWikiDirectory(Map<String,Object> response,long documentId,long userId,boolean platformOnly,int depth,int limit,List<Long> strictRoleIds) {
        var directory=wiki.visibleDescendants(documentId,userId,platformOnly,depth,limit);
        var metadata=wikiRoles==null?Map.<Long,com.company.skillplatform.knowledge.domain.ExternalRoleMetadata>of():
                wikiRoles.resolveAll(directory.items().stream().map(WikiDocumentService.DescendantSummary::documentId).toList());
        List<Map<String,Object>> children=directory.items().stream().filter(child -> strictRoleIds==null
                || metadata.get(child.documentId()).roles().stream().anyMatch(role->strictRoleIds.contains(role.id()))).map(child -> {
            Map<String,Object> item=new LinkedHashMap<>(objectMapper.convertValue(child,new TypeReference<Map<String,Object>>() {}));
            var roles=metadata.get(child.documentId());
            if(roles!=null)item.putAll(roles.toPayload());
            item.putAll(wikiLinks(child.documentId(),platformOnly));return item;
        }).toList();
        response.put("childDocuments",children);
        response.put("childrenTruncated",directory.truncated());
    }
    private String normalizePlatform(String value) { return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT); }
    private String normalizeOs(String value) { return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT); }
    private long longRequired(Map<String,Object> a,String k){Object v=a.get(k);if(!(v instanceof Number n)||n.longValue()<1)throw new IllegalArgumentException(k+" is required");return n.longValue();}
    private Long optionalLong(Map<String,Object> a,String k){Object v=a.get(k);if(v==null)return null;if(!(v instanceof Number n)||n.longValue()<1)throw new IllegalArgumentException(k+" must be a positive integer");return n.longValue();}
    private String str(Map<String,Object> a,String k){Object v=a.get(k);return v==null?null:String.valueOf(v);} private int integer(Map<String,Object>a,String k,int d){Object v=a.get(k);return v instanceof Number n?n.intValue():d;} private int bounded(Map<String,Object>a){return Math.min(integer(a,"maxBytes",32768),MAX_BYTES);} private String required(Map<String,Object>a,String k){String v=str(a,k);if(v==null||v.isBlank())throw new IllegalArgumentException(k+" is required");return v;}
}
