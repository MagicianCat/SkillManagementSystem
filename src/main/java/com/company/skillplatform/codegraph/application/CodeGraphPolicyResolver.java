package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves graph permissions from trusted workflow state and freezes them per run. */
@Service
public class CodeGraphPolicyResolver {
    public static final Set<String> TOOLS = Set.of("overview", "query", "context", "impact", "trace", "route-map");
    private static final int PLATFORM_MAX_DEPTH = 5;
    private static final int PLATFORM_MAX_NODES = 50;
    private static final int PLATFORM_MAX_PATHS = 5;
    private static final int PLATFORM_MAX_CHARS = 8_000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CodeGraphPolicyResolver(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public ResolvedPolicy resolveForAgent(long agentRunId, long actorId) {
        var rows = jdbc.queryForList("select w.id workflow_run_id,w.project_id,s.id stage_run_id,s.stage_key,ar.node_key,"
                + "ar.profile_version_id,ar.code_graph_binding_id,ar.status agent_status,coalesce(nd.workflow_role,ar.node_key) workflow_role "
                + "from agent_workflow_run ar join stage_run s on s.id=ar.stage_run_id join workflow_run w on w.id=s.workflow_run_id "
                + "join virtual_project_member m on m.project_id=w.project_id and m.user_id=? and m.status='ACTIVE' "
                + "left join workflow_template t on t.code=w.workflow_code left join workflow_template_version tv on tv.workflow_template_id=t.id and tv.version_no=w.workflow_version "
                + "left join workflow_stage_def sd on sd.workflow_version_id=tv.id and sd.stage_key=s.stage_key "
                + "left join stage_agent_node_def nd on nd.stage_def_id=sd.id and nd.node_key=ar.node_key where ar.id=?",
                actorId, agentRunId);
        if (rows.isEmpty()) throw new BusinessException("CODE_GRAPH_AGENT_FORBIDDEN", "Agent run is not visible", HttpStatus.FORBIDDEN);
        var row = rows.get(0);
        if (row.get("code_graph_binding_id") == null) throw new BusinessException("CODE_GRAPH_NOT_BOUND", "Agent run has no frozen code graph binding", HttpStatus.NOT_FOUND);
        List<String> active = List.of("QUEUED", "STARTING", "RUNNING", "WAITING_HUMAN", "PAUSED");
        if (!active.contains(String.valueOf(row.get("agent_status")))) throw new BusinessException("CODE_GRAPH_AGENT_NOT_ACTIVE", "Agent run is no longer active", HttpStatus.CONFLICT);
        long runId = number(row, "workflow_run_id");
        String stage = String.valueOf(row.get("stage_key"));
        String node = String.valueOf(row.get("node_key"));
        var snapshot = jdbc.queryForList("select policy_json,policy_hash from workflow_run_code_graph_policy_snapshot where workflow_run_id=? and stage_key=? and node_key=?", runId, stage, node);
        NodePolicy policy = snapshot.isEmpty() ? defaultPolicy(stage, node) : parse(snapshot.get(0).get("policy_json"));
        policy = applyProfileToolDeny(policy, number(row, "profile_version_id"));
        return new ResolvedPolicy(runId, number(row, "project_id"), number(row, "stage_run_id"), agentRunId,
                stage, node, String.valueOf(row.get("workflow_role")), number(row, "profile_version_id"),
                number(row, "code_graph_binding_id"), policy, snapshot.isEmpty() ? "LEGACY_DEFAULT" : "RUN_SNAPSHOT");
    }

    @Transactional
    public void freezeRun(long runId, long configurationId) {
        var nodes = jdbc.queryForList("select n.stage_key,n.node_key,n.agent_profile_version_id,p.policy_json "
                + "from project_agent_configuration_node n left join project_agent_code_graph_policy p on p.configuration_id=n.configuration_id "
                + "and p.stage_key=n.stage_key and p.node_key=n.node_key where n.configuration_id=?", configurationId);
        for (var node : nodes) {
            String stage = String.valueOf(node.get("stage_key"));
            String key = String.valueOf(node.get("node_key"));
            NodePolicy policy = node.get("policy_json") == null ? defaultPolicy(stage, key) : validate(parse(node.get("policy_json")), stage, key);
            String value = write(policy);
            jdbc.update("insert into workflow_run_code_graph_policy_snapshot(time_created,workflow_run_id,stage_key,node_key,profile_version_id,policy_json,policy_hash) "
                            + "values(now(3),?,?,?,?,?,?) on duplicate key update policy_json=values(policy_json),policy_hash=values(policy_hash)",
                    runId, stage, key, node.get("agent_profile_version_id"), value, sha(value));
        }
    }

    @Transactional(readOnly = true)
    public List<PolicyView> projectPolicies(long configurationId) {
        return jdbc.query("select n.stage_key,n.node_key,p.policy_json from project_agent_configuration_node n "
                        + "left join project_agent_code_graph_policy p on p.configuration_id=n.configuration_id and p.stage_key=n.stage_key and p.node_key=n.node_key "
                        + "where n.configuration_id=? order by n.stage_key,n.node_key",
                (rs, i) -> {
                    String stage = rs.getString(1), node = rs.getString(2);
                    boolean custom = rs.getString(3) != null;
                    NodePolicy policy = custom ? validate(parse(rs.getString(3)),stage,node) : defaultPolicy(stage, node);
                    return new PolicyView(stage, node, policy, custom ? "PROJECT_NODE" : "ROLE_DEFAULT", platformLimits());
                }, configurationId);
    }

    @Transactional
    public PolicyView saveProjectPolicy(long configurationId, String stage, String node, NodePolicy requested) {
        Integer exists = jdbc.queryForObject("select count(*) from project_agent_configuration_node where configuration_id=? and stage_key=? and node_key=?", Integer.class, configurationId, stage, node);
        if (exists == null || exists == 0) throw new BusinessException("AGENT_NODE_NOT_FOUND", "Agent node was not found", HttpStatus.NOT_FOUND);
        NodePolicy policy = validate(requested, stage, node);
        String value = write(policy);
        jdbc.update("insert into project_agent_code_graph_policy(time_created,time_updated,configuration_id,stage_key,node_key,policy_json,policy_hash) "
                        + "values(now(3),now(3),?,?,?,?,?) on duplicate key update policy_json=values(policy_json),policy_hash=values(policy_hash),time_updated=now(3)",
                configurationId, stage, node, value, sha(value));
        return new PolicyView(stage, node, policy, "PROJECT_NODE", platformLimits());
    }

    @Transactional
    public PolicyView resetProjectPolicy(long configurationId,String stage,String node){jdbc.update("delete from project_agent_code_graph_policy where configuration_id=? and stage_key=? and node_key=?",configurationId,stage,node);return new PolicyView(stage,node,defaultPolicy(stage,node),"ROLE_DEFAULT",platformLimits());}

    public NodePolicy validate(NodePolicy value, String stage, String node) {
        if (value == null) return defaultPolicy(stage, node);
        var tools = value.allowedTools() == null ? List.<String>of() : value.allowedTools().stream().map(this::normalizeTool).distinct().toList();
        if (!TOOLS.containsAll(tools)) throw new BusinessException("CODE_GRAPH_POLICY_INVALID", "Unknown code graph tool", HttpStatus.BAD_REQUEST);
        int stageMax = stageBudget(stage);
        return new NodePolicy(1, value.enabled(), tools,
                clamp(value.softToolCalls(), 0, stageMax), clamp(value.maxDepth(), 1, PLATFORM_MAX_DEPTH),
                clamp(value.maxNodes(), 1, PLATFORM_MAX_NODES), clamp(value.maxPaths(), 1, PLATFORM_MAX_PATHS),
                clamp(value.maxResultChars(), 500, PLATFORM_MAX_CHARS), clamp(value.maxPreloadQueries(), 0, 2),
                value.evidenceMode() == null ? "REUSE_FIRST" : value.evidenceMode(),
                value.stopInstruction() == null || value.stopInstruction().isBlank() ? stopInstruction(stage, node) : value.stopInstruction(),
                stageMax, stageEngineBudget(stage));
    }

    public NodePolicy defaultPolicy(String stage, String node) {
        String s = stage == null ? "" : stage.toUpperCase(Locale.ROOT);
        String n = node == null ? "" : node.toLowerCase(Locale.ROOT);
        List<String> tools; int calls; int depth; int nodes; int paths = 3; int chars; int preload;
        if ("REQUIREMENT".equals(s) && "clarifier".equals(n)) { tools=List.of("overview","route-map","query","context"); calls=3;depth=1;nodes=10;chars=3000;preload=1; }
        else if ("REQUIREMENT".equals(s) && "writer".equals(n)) { tools=List.of("query","context");calls=2;depth=1;nodes=12;chars=3000;preload=0; }
        else if ("REQUIREMENT".equals(s)) { tools=List.of("query");calls=1;depth=1;nodes=10;chars=2500;preload=0; }
        else if ("PRODUCT".equals(s) && "analyst".equals(n)) { tools=List.of("route-map","query","context");calls=3;depth=1;nodes=15;chars=3500;preload=1; }
        else if ("PRODUCT".equals(s)) { tools=List.of("query");calls=1;depth=1;nodes=10;chars=2500;preload=0; }
        else if ("ARCHITECTURE_DESIGN".equals(s) && "analyst".equals(n)) { tools=List.of("overview","route-map","query","context","impact","trace");calls=7;depth=3;nodes=30;chars=6500;preload=2; }
        else if ("ARCHITECTURE_DESIGN".equals(s) && "writer".equals(n)) { tools=List.of("query","context","impact","trace");calls=4;depth=3;nodes=30;chars=5500;preload=0; }
        else if ("ARCHITECTURE_DESIGN".equals(s)) { tools=List.of("query","context","impact","trace");calls=2;depth=2;nodes=20;chars=4500;preload=0; }
        else if ("UI_DESIGN".equals(s) && "analyst".equals(n)) { tools=List.of("route-map","query");calls=2;depth=1;nodes=10;chars=2500;preload=0; }
        else if ("UI_DESIGN".equals(s)) { tools=List.of("query");calls=1;depth=1;nodes=10;chars=2000;preload=0; }
        else { tools=List.of();calls=0;depth=1;nodes=10;chars=2000;preload=0; }
        return new NodePolicy(1, !tools.isEmpty(), tools, calls, depth, nodes, paths, chars, preload,
                "REUSE_FIRST", stopInstruction(s,n), stageBudget(s), stageEngineBudget(s));
    }

    private String stopInstruction(String stage, String node) {
        String role=(stage+"/"+node).toUpperCase(Locale.ROOT);String guidance=switch(role){
            case "REQUIREMENT/CLARIFIER" -> "只识别相关仓库、业务入口、主要跨仓 API 与阻塞需求定义的约束，不枚举全部实现。";
            case "REQUIREMENT/WRITER" -> "优先消费澄清结论和 Evidence，仅在关键业务规则无法落笔时补查一跳关系。";
            case "REQUIREMENT/REVIEWER" -> "先审文档与证据，只对阻塞验收的具体冲突补查；零次图谱查询也可通过。";
            case "PRODUCT/ANALYST" -> "只查询影响用户旅程、产品规则和 API 能力边界的关系，不追踪完整内部调用链。";
            case "PRODUCT/WRITER" -> "复用需求与产品分析证据，仅在接口能力不明确时定向查询。";
            case "PRODUCT/REVIEWER" -> "只有发现具体矛盾时补查代码，不得用全仓检索代替文档评审。";
            case "ARCHITECTURE_DESIGN/ANALYST" -> "围绕跨仓路径、上下游影响、数据一致性和故障传播验证关键决策，未解析关系必须标为待验证。";
            case "ARCHITECTURE_DESIGN/WRITER" -> "复用架构分析证据，只有关键调用路径缺失时补查，不重新从 Overview 开始。";
            case "ARCHITECTURE_DESIGN/REVIEWER" -> "只复核高风险结论及其证据，不重复架构分析者已完成的全部查询。";
            case "UI_DESIGN/ANALYST" -> "只关注页面、交互状态和前后端接口边界，不研究 DAO/Repository 调用链。";
            case "UI_DESIGN/WRITER" -> "默认使用产品与 UX 证据，只有接口状态确有缺口时定向查询。";
            case "UI_DESIGN/REVIEWER" -> "默认不查图谱，遇到具体接口能力争议时最多定向核验。";
            default -> "只读取完成当前职责所必需的代码证据。";};
        return guidance+" 每次查询前明确一个待验证问题；Evidence 已回答时禁止重复查询。连续两次查询没有新增决策证据时立即停止，并基于现有证据完成当前职责。";
    }
    private int stageBudget(String stage) { return switch (stage == null ? "" : stage.toUpperCase(Locale.ROOT)) { case "REQUIREMENT" -> 9; case "PRODUCT" -> 7; case "ARCHITECTURE_DESIGN" -> 17; case "UI_DESIGN" -> 5; default -> 0; }; }
    private int stageEngineBudget(String stage) { return switch (stage == null ? "" : stage.toUpperCase(Locale.ROOT)) { case "REQUIREMENT" -> 7; case "PRODUCT" -> 5; case "ARCHITECTURE_DESIGN" -> 12; case "UI_DESIGN" -> 3; default -> 0; }; }
    private Map<String,Integer> platformLimits() { return Map.of("maxDepth",PLATFORM_MAX_DEPTH,"maxNodes",PLATFORM_MAX_NODES,"maxPaths",PLATFORM_MAX_PATHS,"maxResultChars",PLATFORM_MAX_CHARS); }
    private String normalizeTool(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).replace("code_graph_", "").replace('_','-'); }
    private NodePolicy applyProfileToolDeny(NodePolicy policy, long profileVersionId) {
        List<String> denied = jdbc.query("select tool_code from agent_profile_version_tool where agent_profile_version_id=? "
                        + "and (enabled=false or upper(coalesce(permission_mode,''))='DENY')",
                (rs, index) -> normalizeToolCode(rs.getString(1)), profileVersionId);
        if (denied.isEmpty()) return policy;
        List<String> effective = policy.allowedTools().stream().filter(tool -> !denied.contains(tool)).toList();
        return new NodePolicy(policy.version(), policy.enabled() && !effective.isEmpty(), effective,
                policy.softToolCalls(), policy.maxDepth(), policy.maxNodes(), policy.maxPaths(),
                policy.maxResultChars(), policy.maxPreloadQueries(), policy.evidenceMode(),
                policy.stopInstruction(), policy.stageMaxToolCalls(), policy.stageMaxEngineQueries());
    }
    private String normalizeToolCode(String value) {
        if (value == null) return "";
        String normalized = value.toLowerCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        int marker = normalized.lastIndexOf("code_graph_");
        if (marker >= 0) normalized = normalized.substring(marker + "code_graph_".length());
        return normalizeTool(normalized);
    }
    private int clamp(Integer value, int min, int max) { return Math.max(min, Math.min(value == null ? min : value, max)); }
    private long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private NodePolicy parse(Object raw) { try { return json.readValue(String.valueOf(raw), NodePolicy.class); } catch (Exception e) { throw new BusinessException("CODE_GRAPH_POLICY_INVALID", "Code graph policy JSON is invalid", HttpStatus.BAD_REQUEST); } }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String sha(String value) { try { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b));return out.toString(); } catch(Exception e){throw new IllegalStateException(e);} }

    public record NodePolicy(Integer version, boolean enabled, List<String> allowedTools, Integer softToolCalls,
                             Integer maxDepth, Integer maxNodes, Integer maxPaths, Integer maxResultChars,
                             Integer maxPreloadQueries, String evidenceMode, String stopInstruction,
                             Integer stageMaxToolCalls, Integer stageMaxEngineQueries) {}
    public record PolicyView(String stageKey, String nodeKey, NodePolicy policy, String policySource, Map<String,Integer> platformLimits) {}
    public record ResolvedPolicy(long workflowRunId,long projectId,long stageRunId,long agentRunId,String stageKey,
                                 String nodeKey,String workflowRole,long profileVersionId,long graphBindingId,
                                 NodePolicy policy,String policySource) {}
}
