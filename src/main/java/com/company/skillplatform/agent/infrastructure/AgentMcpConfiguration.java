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
import org.springframework.jdbc.core.JdbcTemplate;

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

    public AgentMcpConfiguration(ObjectMapper objectMapper, SkillService skills, SkillVersionRepository versions,
                                 ObjectStoragePort storage, AgentRunService runs, AgentRecommendationRepository recommendations,
                                 AgentRunRepository persistentRuns, AgentEventHub events, WikiDocumentService wiki, FeishuDocumentMcpProxy feishu,
                                 AgentFeishuCallGuard feishuGuard, ProjectControlService projectControl, DocumentAgentSessionService documentAgents,
                                 JdbcTemplate jdbc,
                                 @Value("${skill-platform.agent-web-base-url:${skill-platform.feishu.bot-web-base-url:http://127.0.0.1:5173}}") String webBaseUrl) {
        this.objectMapper = objectMapper; this.skills = skills; this.versions = versions; this.storage = storage; this.runs = runs; this.recommendations = recommendations;this.persistentRuns=persistentRuns;this.events=events; this.wiki=wiki; this.feishu=feishu; this.feishuGuard=feishuGuard; this.projectControl=projectControl; this.documentAgents=documentAgents; this.jdbc=jdbc; this.webBaseUrl=webBaseUrl.replaceAll("/$", "");
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
                .tools(tool("get_current_user_context", "Get the current user's visible teams before recommending skills.",
                        schema("object", List.of()), this::currentUserContext),
                        tool("search_skills", "Search published skills by development stage, platform and OS.",
                        schema("object", List.of()), this::search),
                        tool("get_skill_detail", "Get metadata and a segment of the root skill.md.",
                                schema("object", List.of("skillKey")), this::detail),
                        tool("search_wiki_documents", "Search Wiki documents visible to the current user, or only the selected job whitelist for a document-agent run.",
                                schema("object", List.of()), this::searchWiki),
                        tool("get_wiki_document", "Read a bounded segment of a visible Wiki Markdown document.",
                                schema("object", List.of("documentId")), this::wikiDocument),
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
        props.put("teamId", Map.of("type", "integer", "minimum", 1));
        props.put("documentId", Map.of("type", "integer", "minimum", 1));
        props.put("keyword", Map.of("type", "string", "maxLength", 200)); props.put("query", Map.of("type", "string", "minLength", 1, "maxLength", 200)); props.put("docId", Map.of("type", "string", "minLength", 1, "maxLength", 512));
        props.put("docType", Map.of("type", "string", "enum", List.of("DOCX", "DOC", "SHEET", "BITABLE", "SLIDES", "MINDNOTE", "WIKI", "UNKNOWN")));
        props.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 10));
        props.put("documentType", Map.of("type", "string", "enum", List.of("SKILL_README", "SKILL_GUIDE")));
        props.put("artifactId", Map.of("type", "integer", "minimum", 1));
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
        return new McpSchema.JsonSchema(type, props, required, false, Map.of(), Map.of());
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
        props.put("summary",Map.of("type","string","maxLength",2000));
        props.put("items",Map.of("type","array","maxItems",20,"items",item));
        props.put("citations",Map.of("type","array","maxItems",20));
        return new McpSchema.JsonSchema("object",props,List.of("summary","items"),false,Map.of(),Map.of());
    }
    private McpSchema.CallToolResult search(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent = run(ex, "skill.search"); String stage = str(args, "developmentStage"); String platform = normalizePlatform(str(args,"platform")), osType = normalizeOs(str(args,"osType")); int page = integer(args, "page", 0), size = Math.min(integer(args, "pageSize", 20), 20);
        var result = skills.search(null, null, null, platform, osType, "PUBLISHED", null, stage, null,
                org.springframework.data.domain.PageRequest.of(page, size, org.springframework.data.domain.Sort.by("skillKey").ascending()));
        List<Map<String,Object>> items = result.getContent().stream().map(view -> {
            Map<String,Object> item = objectMapper.convertValue(view, new TypeReference<Map<String,Object>>() {});
            item.put("targetPlatform", platform == null ? "DEFAULT" : platform);
            item.put("targetOsType", osType == null ? "DEFAULT" : osType);
            item.put("detailPath", detailPath(view.skillKey(), platform, osType));
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
    private McpSchema.CallToolResult detail(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent = run(ex, "skill.detail"); String key = required(args,"skillKey"); var view = skills.get(key); String content = readText(key, "skill.md", integer(args,"offset",0), bounded(args));
        var docs = "PLATFORM_PUBLIC_ONLY".equals(agent.knowledgeScope()) ? List.of() : wiki.search(agent.userId(), null, key, null, null, PageRequest.of(0, 20)).items();
        String platform = normalizePlatform(str(args,"platform")), osType = normalizeOs(str(args,"osType"));
        return ok(Map.of("skill", view, "target", target(platform, osType), "detailPath", detailPath(key, platform, osType), "file", "skill.md", "content", content, "offset", integer(args,"offset",0), "hasMore", content.length() >= bounded(args), "wikiDocuments", docs));
    }
    private McpSchema.CallToolResult searchWiki(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent = run(ex, "wiki.search");
        Long workflowId = workflowRunId(agent);
        if (workflowId != null) return workflowWikiSearch(workflowId, str(args, "keyword"));
        if (agent.sessionKey() != null) return ok(Map.of("items", documentAgents.searchSelectedWiki(agent.sessionKey(), agent.runRef(), str(args,"keyword")), "page", 0, "size", 10));
        requireUserKnowledge(agent); var result = wiki.search(agent.userId(), optionalLong(args,"teamId"), str(args,"skillKey"), str(args,"documentType"), str(args,"keyword"), PageRequest.of(integer(args,"page",0), Math.min(integer(args,"pageSize",20),20)));
        return ok(Map.of("items", result.items(), "page", result.page(), "size", result.size(), "totalElements", result.totalElements(), "totalPages", result.totalPages()));
    }
    private McpSchema.CallToolResult wikiDocument(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent = run(ex, "wiki.read"); long id = longRequired(args,"documentId"); int offset = integer(args,"offset",0); int max = bounded(args);
        Long workflowId = workflowRunId(agent);
        if (workflowId != null) return workflowWikiRead(workflowId, id, offset, max);
        if (agent.sessionKey() != null) return ok(objectMapper.convertValue(documentAgents.readSelectedWiki(agent.sessionKey(), agent.runRef(), id, offset, max), new TypeReference<Map<String,Object>>() {}));
        requireUserKnowledge(agent); var document = wiki.get(id, agent.userId()); String text = document.markdownContent(); String content = offset >= text.length() ? "" : text.substring(offset, Math.min(text.length(), offset + max));
        return ok(Map.of("documentId", id, "title", document.title(), "documentType", document.documentType(), "skills", document.skills(), "content", content, "offset", offset, "hasMore", offset + content.length() < text.length()));
    }
    private McpSchema.CallToolResult searchFeishu(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var agent = run(ex, "feishu.search"); requireUserKnowledge(agent); String query = required(args, "query");
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
        var agent = run(ex, "feishu.read"); requireUserKnowledge(agent); String docId = required(args, "docId"); String docType = required(args, "docType");
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
    private McpSchema.CallToolResult workflowWikiSearch(long runId,String keyword) { String q=keyword==null?"":keyword; List<Map<String,Object>> items=jdbc.query("select s.wiki_document_id,d.title,d.document_type,s.wiki_revision_no from workflow_run_context_snapshot s join wiki_document d on d.id=s.wiki_document_id where s.workflow_run_id=? and s.context_kind='PLATFORM_WIKI' and (?='' or d.title like concat('%',?,'%')) order by d.title",(r,n)->Map.of("documentId",r.getLong(1),"title",r.getString(2),"documentType",r.getString(3),"revisionNo",r.getObject(4)),runId,q,q); return ok(Map.of("items",items,"page",0,"size",items.size(),"totalElements",items.size())); }
    private McpSchema.CallToolResult workflowWikiRead(long runId,long documentId,int offset,int max) { Map<String,Object> row=jdbc.queryForMap("select s.wiki_revision_no,d.title,d.document_type,r.markdown_content from workflow_run_context_snapshot s join wiki_document d on d.id=s.wiki_document_id join wiki_document_revision r on r.document_id=d.id and r.revision_no=s.wiki_revision_no where s.workflow_run_id=? and s.context_kind='PLATFORM_WIKI' and s.wiki_document_id=?",runId,documentId); String text=String.valueOf(row.get("markdown_content"));String content=offset>=text.length()?"":text.substring(offset,Math.min(text.length(),offset+max));return ok(Map.of("documentId",documentId,"title",row.get("title"),"documentType",row.get("document_type"),"revisionNo",row.get("wiki_revision_no"),"content",content,"offset",offset,"hasMore",offset+content.length()<text.length())); }
    private McpSchema.CallToolResult workflowFeishuSearch(long runId,String query) { List<Map<String,Object>> items=jdbc.query("select feishu_doc_id,feishu_doc_type,title from workflow_run_context_snapshot where workflow_run_id=? and context_kind='FEISHU' and (?='' or title like concat('%',?,'%')) order by title",(r,n)->Map.of("docId",r.getString(1),"docType",r.getString(2),"title",r.getString(3),"readable",true),runId,query==null?"":query,query==null?"":query);return ok(Map.of("query",query,"result",Map.of("items",items))); }
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
        return ok(projectControl.getDocument(agent.projectKey(), id, agent.userId()));
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
        run(ex, "skill.file.read"); String key = required(args,"skillKey"), path = required(args,"path"); if (path.startsWith("/") || path.contains("..")) throw new IllegalArgumentException("unsafe path");
        String content = readText(key, path, integer(args,"offset",0), bounded(args));
        return ok(Map.of("skillKey", key, "path", path, "content", content, "offset", integer(args,"offset",0), "hasMore", content.length() >= bounded(args)));
    }
    private McpSchema.CallToolResult submit(io.modelcontextprotocol.server.McpSyncServerExchange ex, Map<String,Object> args) {
        var run = run(ex, "recommendation.submit"); Object items = args.get("items"); if (!(items instanceof List<?> list) || list.size() > 20) throw new IllegalArgumentException("items must contain 0..20 recommendations");
        String runPlatform=com.company.skillplatform.agent.application.AgentRequestTarget.optionalPlatform(run.platform());String runOsType=com.company.skillplatform.agent.application.AgentRequestTarget.optionalOsType(run.osType());
        List<Map<String,Object>> accepted = new ArrayList<>(); for (Object item : list) { if (!(item instanceof Map<?,?> m)) throw new IllegalArgumentException("invalid item"); String key = String.valueOf(m.get("skillKey")); var view = skills.get(key); if (view.status() != com.company.skillplatform.skill.domain.SkillStatus.ACTIVE || view.latestPublishedVersion() == null) continue;var trustedVersion=versions.findFirstBySkillIdAndLifecycleStatusInOrderByPublishedAtDesc(view.id(),List.of(LifecycleStatus.PUBLISHED,LifecycleStatus.DEPRECATED)).orElse(null);if(trustedVersion==null||!view.latestPublishedVersion().equals(trustedVersion.getVersion()))continue; Object priority=m.get("priority"), reason=m.get("reason"); String p=priority==null?"RECOMMENDED":String.valueOf(priority).toUpperCase(); if (!Set.of("REQUIRED","RECOMMENDED","OPTIONAL").contains(p)) throw new IllegalArgumentException("invalid priority"); Map<String,Object>trusted=new LinkedHashMap<>();trusted.put("skillKey",key);trusted.put("displayName",view.displayName());trusted.put("description",view.description());trusted.put("version",trustedVersion.getVersion());trusted.put("versionId",trustedVersion.getId());trusted.put("developmentStage",view.developmentStage());trusted.put("priority",p);trusted.put("reason",reason==null?"":String.valueOf(reason));if(runPlatform!=null)trusted.put("platform",runPlatform);if(runOsType!=null)trusted.put("osType",runOsType);trusted.put("detailPath",detailPath(key,runPlatform,runOsType));accepted.add(trusted); }
        String summary = args.get("summary") == null ? "" : String.valueOf(args.get("summary"));
        String status=accepted.size()==list.size()?"VALID":accepted.isEmpty()?"EMPTY":"PARTIALLY_VALID";
        Map<String,Object> result = Map.of("accepted", true, "runRef", run.runRef(), "summary", summary, "status",status,"items", accepted);
        String payload=write(result);var existing=recommendations.findByRunRef(run.runRef());
        if(existing.isPresent()&&!existing.get().getPayload().equals(payload))throw new IllegalArgumentException("recommendation already submitted");
        if(existing.isEmpty()){var persistent=persistentRuns.findByRunKey(run.runRef()).orElse(null);recommendations.save(new AgentRecommendationEntity(run.runRef(),persistent,summary,status,payload));events.publish(run.runRef(),"recommendation.completed",new LinkedHashMap<>(result));}
        return ok(result);
    }
    private com.company.skillplatform.agent.domain.AgentRun run(io.modelcontextprotocol.server.McpSyncServerExchange ex, String capability) { HttpServletRequest req=(HttpServletRequest)ex.transportContext().get("request"); String h=req==null?null:req.getHeader("Authorization"); if(h==null||!h.startsWith("Bearer ")) throw new IllegalArgumentException("agent token required"); var run=runs.require(h.substring(7)); runs.requireCapability(run, capability); var authorities=new java.util.ArrayList<org.springframework.security.core.authority.SimpleGrantedAuthority>(); authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("skill:browse")); authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("agent:mcp")); if("PLATFORM_PUBLIC_ONLY".equals(run.knowledgeScope())) authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("agent:platform-public")); var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(run.userId(), null, authorities); org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth); return run; }
    private void requireUserKnowledge(com.company.skillplatform.agent.domain.AgentRun run) { if("PLATFORM_PUBLIC_ONLY".equals(run.knowledgeScope())) throw new org.springframework.security.access.AccessDeniedException("This Agent session is restricted to public platform knowledge"); }
    private String readText(String key,String path,int offset,int max) { SkillVersionEntity v=versions.findFirstBySkillIdAndLifecycleStatusInOrderByPublishedAtDesc(skills.get(key).id(), List.of(LifecycleStatus.PUBLISHED)).orElseThrow(); try(InputStream in=storage.get(v.getSourceObjectKey()); ZipInputStream zip=new ZipInputStream(in, StandardCharsets.UTF_8)){ ZipEntry e; while((e=zip.getNextEntry())!=null){ if(e.isDirectory()||!e.getName().equalsIgnoreCase(path)) continue; byte[] b=zip.readNBytes(MAX_BYTES+1); if(b.length>MAX_BYTES) throw new IllegalArgumentException("file too large"); String s=new String(b, StandardCharsets.UTF_8); return offset>=s.length()?"":s.substring(offset, Math.min(s.length(), offset+max)); } throw new IllegalArgumentException("SKILL_FILE_NOT_FOUND"); } catch(Exception e){ throw new IllegalArgumentException(e.getMessage(), e); } }
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
    private String normalizePlatform(String value) { return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT); }
    private String normalizeOs(String value) { return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT); }
    private long longRequired(Map<String,Object> a,String k){Object v=a.get(k);if(!(v instanceof Number n)||n.longValue()<1)throw new IllegalArgumentException(k+" is required");return n.longValue();}
    private Long optionalLong(Map<String,Object> a,String k){Object v=a.get(k);if(v==null)return null;if(!(v instanceof Number n)||n.longValue()<1)throw new IllegalArgumentException(k+" must be a positive integer");return n.longValue();}
    private String str(Map<String,Object> a,String k){Object v=a.get(k);return v==null?null:String.valueOf(v);} private int integer(Map<String,Object>a,String k,int d){Object v=a.get(k);return v instanceof Number n?n.intValue():d;} private int bounded(Map<String,Object>a){return Math.min(integer(a,"maxBytes",32768),MAX_BYTES);} private String required(Map<String,Object>a,String k){String v=str(a,k);if(v==null||v.isBlank())throw new IllegalArgumentException(k+" is required");return v;}
}
