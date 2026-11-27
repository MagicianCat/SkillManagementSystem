package com.company.skillplatform.agent.application;

import com.company.skillplatform.agent.domain.AgentRun;
import com.company.skillplatform.knowledge.application.ExternalRoleDirectoryService;
import com.company.skillplatform.knowledge.application.RoleNameMatcher;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRetrievalDiscovery {
    private final JdbcTemplate db;
    private final RetrievalPlanStore store;
    private final ExternalRoleDirectoryService roles;
    private final ObjectMapper json;
    private final com.company.skillplatform.knowledge.application.StructuredCatalogService catalogs;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AgentRetrievalDiscovery.class);
    public AgentRetrievalDiscovery(JdbcTemplate db,RetrievalPlanStore store,ExternalRoleDirectoryService roles,ObjectMapper json,
            com.company.skillplatform.knowledge.application.StructuredCatalogService catalogs,
            io.micrometer.core.instrument.MeterRegistry metrics){
        this.db=db;this.store=store;this.roles=roles;this.json=json;this.catalogs=catalogs;this.metrics=metrics;
    }

    static final Set<String> DISCOVERY_TOOLS=Set.of("user.context.read","role.resolve","retrieval.plan.read",
            "retrieval.task.propose","knowledge.search","wiki.search","wiki.read","skill.search","skill.detail",
            "skill.file.read","feishu.search","feishu.read");

    public Map<String,Object> begin(String runKey,String question,boolean exactRoleMention){
        String text=RoleNameMatcher.normalize(question);
        boolean context=text.matches("(?s).*(?:上下文|项目背景|项目资料).*");
        boolean followUp=isFollowUp(text);
        boolean roleRequested=exactRoleMention||context||(followUp&&!store.priorReferences(runKey).isEmpty())||text.matches("(?s).*(?:项目组|外部角色|该角色|这个角色|开发角色).*");
        boolean exclude=text.matches("(?s).*(?:不要|禁止|无需|不用|不需要|请勿|别)(?:给我|再|额外|主动){0,2}推荐.{0,12}(?:skill|技能).*");
        var plan=new LinkedHashMap<String,Object>();
        plan.put("schemaVersion",3);plan.put("status","EXPLORE");plan.put("retrievalState","DISCOVERY");
        plan.put("revision",1);plan.put("intents",List.of());plan.put("policyKeys",List.of());
        plan.put("allowedArtifacts",List.of("ANSWER"));plan.put("recommendSkills",false);
        plan.put("constraints",exclude?List.of("EXCLUDE_SKILL_OUTPUT"):List.of());
        plan.put("allowedCapabilities",DISCOVERY_TOOLS.stream().sorted().toList());
        plan.put("hints",Map.of("roleRequested",roleRequested,"projectContext",context,"followUp",followUp));
        plan.put("tasks",List.of());plan.put("modelAdvice",Map.of("status","SKIPPED"));
        store.insert(runKey,plan,3);
        return plan;
    }

    private boolean isFollowUp(String text) {
        return text.matches("(?s).*(?:刚才|上面|上一轮|这篇|那篇|这个\\s*skill|那个\\s*skill|这些\\s*skill|该角色|这个角色|那个角色|第[一二三四五六七八九十0-9]+[个项篇]|换成|改查).*");
    }

    public List<RetrievalPlanStore.PriorReference> history(AgentRun run) {
        var current=db.query("""
                SELECT m.content FROM agent_message m JOIN agent_run r ON r.id=m.run_id
                WHERE r.run_key=? AND m.role='USER'
                """,(rs,row)->rs.getString(1),run.runRef());
        if(current.size()!=1||!isFollowUp(RoleNameMatcher.normalize(current.get(0))))return List.of();
        return store.priorReferences(run.runRef()).stream().limit(30).filter(ref->live(run,ref)).toList();
    }

    private boolean live(AgentRun run,RetrievalPlanStore.PriorReference ref) {
        return catalogs.hasVersion(run.userId(),run.knowledgeScope(),ref.roleId(),ref.kind(),ref.category(),
                ref.objectId(),ref.versionId(),ref.audience());
    }

    public Optional<String> bareRoleOptions(String runKey,long user,String scope,String question) {
        String name=bareName(question);
        if(name.length()<2||name.length()>255)return Optional.empty();
        var snapshots=db.query("""
                SELECT normalized_query,requested_source,result_json FROM agent_run_role_resolution
                WHERE run_key=? AND actor_id=? AND knowledge_scope=? AND expires_at>NOW(3) ORDER BY id DESC
                """,(rs,row)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3)},runKey,user,scope);
        for(var snapshot:snapshots){
            if(snapshot[1]!=null||!bareName(snapshot[0]).equals(name))continue;
            var match=read(snapshot[2],RoleNameMatcher.Result.class);
            var selected=match.candidates().stream().filter(RoleNameMatcher.Candidate::canAutoConfirm).findFirst();
            if(selected.isEmpty())continue;
            var current=roles.match(user,scope,null,snapshot[0],5,false).candidates().stream()
                    .filter(c->c.canAutoConfirm()&&c.id()==selected.get().id()).findFirst();
            if(current.isEmpty())continue;
            var role=current.get();
            var options=new ArrayList<String>();
            for(String kind:List.of("DOCUMENT","ASDM-TOOL","SKILL")){
                if(catalogs.list(user,scope,role.id(),kind,null,0,1).total()>0)options.add(kind);
            }
            String display=role.name().replaceAll("[\\r\\n\\[\\]]"," ");
            if(options.isEmpty())return Optional.of("已找到「"+display+"」，当前没有可查看的资料。");
            var offer=new RoleCatalogOffer(snapshot[0],role.id(),options);
            db.update("""
                    UPDATE agent_run_intent_plan SET retrieval_plan_json=JSON_SET(retrieval_plan_json,'$.roleCatalogOffer',CAST(? AS JSON))
                    WHERE run_key=? AND schema_version=3 AND status='EXPLORE'
                    """,store.encode(offer),runKey);
            var text=new StringBuilder("已找到「").append(display).append("」。你想查看哪类资料？\n\n");
            for(int index=0;index<options.size();index++)
                text.append(index+1).append(". ").append(optionLabel(options.get(index))).append('\n');
            return Optional.of(text.append("\n你也可以回复「全给我」查看全部可见资料。").toString());
        }
        return Optional.empty();
    }

    public Optional<RetrievalPlanner.Plan> selectRoleOption(String runKey,long user,String scope,String question) {
        String choice=bareName(question);
        String kind=switch(choice){
            case "文档","wiki","wiki 文档","wiki文档","上下文","项目上下文" -> "DOCUMENT";
            case "工具","工具包","asdm","asdm 工具","asdm工具","asdm 工具包","asdm工具包" -> "ASDM-TOOL";
            case "skill","skills","技能","已发布 skill" -> "SKILL";
            default -> null;
        };
        boolean all=Set.of("全给我","全部","都给我","全都给我","全部给我","以上全部").contains(choice);
        if(!all&&kind==null&&!choice.matches("[1-3]"))return Optional.empty();
        var offers=db.query("""
                SELECT JSON_EXTRACT(p.retrieval_plan_json,'$.roleCatalogOffer')
                FROM agent_run current_run
                JOIN agent_run prev ON prev.session_id=current_run.session_id AND prev.run_no=current_run.run_no-1
                JOIN agent_run_intent_plan p ON p.run_key=prev.run_key
                WHERE current_run.run_key=? AND prev.status='SUCCEEDED'
                  AND JSON_EXTRACT(p.retrieval_plan_json,'$.roleCatalogOffer') IS NOT NULL
                """,(rs,row)->rs.getString(1),runKey);
        if(offers.isEmpty())return Optional.empty();
        var offer=read(offers.get(0),RoleCatalogOffer.class);
        if(!all&&kind==null){
            int index=Integer.parseInt(choice)-1;
            if(index>=offer.options().size())return Optional.empty();
            kind=offer.options().get(index);
        }
        if(!all&&!offer.options().contains(kind))return Optional.empty();
        var current=roles.match(user,scope,null,offer.query(),5,false).candidates().stream()
                .filter(c->c.canAutoConfirm()&&c.id()==offer.roleId()).findFirst();
        if(current.isEmpty())return Optional.empty();
        var role=current.get();
        String category=choice.contains("上下文")?"PROJECT_CONTEXT":null;
        var tasks=new ArrayList<RetrievalPlanner.Task>();
        if(all){
            for(String available:List.of("DOCUMENT","ASDM-TOOL","SKILL")){
                if(catalogs.list(user,scope,role.id(),available,null,0,1).total()>0)
                    tasks.add(new RetrievalPlanner.Task("t"+(tasks.size()+1),role.id(),available,null));
            }
        }else tasks.add(new RetrievalPlanner.Task("t1",role.id(),kind,category));
        if(tasks.isEmpty())return Optional.empty();
        return Optional.of(new RetrievalPlanner.Plan("CATALOG",tasks.get(0),
                List.of(new QueryEntityResolver.Mention(role.id(),role.name(),role.sourceSystem(),0,0)),tasks));
    }

    private String bareName(String question){
        return RoleNameMatcher.normalize(question).replaceFirst("[。.!?]+$","").strip();
    }

    private String optionLabel(String kind){
        return switch(kind){
            case "DOCUMENT" -> "Wiki 文档";
            case "ASDM-TOOL" -> "ASDM 工具包";
            default -> "已发布 Skill";
        };
    }

    private record RoleCatalogOffer(String query,long roleId,List<String> options){}

    public boolean discovering(String runKey){
        return runKey!=null&&Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM agent_run_intent_plan WHERE run_key=? AND schema_version=3 AND status='EXPLORE')
                """,Boolean.class,runKey));
    }

    public boolean roleRequired(String runKey){
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM agent_run_intent_plan p WHERE p.run_key=? AND
                  (JSON_EXTRACT(p.retrieval_plan_json,'$.hints.roleRequested')=true OR
                   EXISTS(SELECT 1 FROM agent_run_role_resolution c WHERE c.run_key=p.run_key)))
                """,Boolean.class,runKey));
    }

    public Map<String,Object> search(AgentRun run,String name,String source,String mode,int limit){
        if(db.queryForObject("SELECT COUNT(*) FROM agent_run_role_resolution WHERE run_key=?",Integer.class,run.runRef())>=3)
            throw new BusinessException("ROLE_LOOKUP_LIMIT","请缩小角色查询范围",HttpStatus.TOO_MANY_REQUESTS);
        boolean exact="EXACT_ONLY".equals(mode);
        if(mode!=null&&!exact&&!"EXACT_OR_FUZZY".equals(mode))throw new IllegalArgumentException("Invalid role match mode");
        long started=System.nanoTime();
        var found=roles.match(run.userId(),run.knowledgeScope(),source,name,limit,exact);
        long elapsed=System.nanoTime()-started;
        metrics.timer("agent.retrieval.role.lookup","result",found.matchStatus()).record(elapsed,java.util.concurrent.TimeUnit.NANOSECONDS);
        log.info("[Yanshu][ROLE_LOOKUP] runKey={} matchStatus={} candidateCount={} truncated={} elapsedMs={}",
                run.runRef(),found.matchStatus(),found.candidates().size(),found.truncated(),elapsed/1_000_000);
        String key=UUID.randomUUID().toString();
        db.update("""
                INSERT INTO agent_run_role_resolution(resolution_key,run_key,actor_id,knowledge_scope,normalized_query,
                    requested_source,result_json,expires_at) VALUES (?,?,?,?,?,?,?,DATE_ADD(NOW(3),INTERVAL 5 MINUTE))
                """,key,run.runRef(),run.userId(),run.knowledgeScope(),name.strip(),source,store.encode(found));
        return Map.of("status","EXACT".equals(found.matchStatus())?"EXACT":found.candidates().isEmpty()?"NOT_FOUND_OR_NOT_VISIBLE":"AMBIGUOUS",
                "matchStatus",found.matchStatus(),"candidates",found.candidates(),"truncated",found.truncated(),"resolutionId",key);
    }

    @Transactional
    public Map<String,Object> confirm(AgentRun run,int revision,List<Proposal> proposals){
        if(proposals==null||proposals.isEmpty()||proposals.size()>4)throw RetrievalScopeGuard.denied();
        var rows=db.query("""
                SELECT p.plan_revision,p.retrieval_plan_json,m.content FROM agent_run_intent_plan p
                JOIN agent_run r ON r.run_key=p.run_key JOIN agent_message m ON m.run_id=r.id AND m.role='USER'
                WHERE p.run_key=? AND p.schema_version=3 AND r.status='RUNNING' FOR UPDATE
                """,(rs,n)->new Object[]{rs.getInt(1),rs.getString(2),rs.getString(3)},run.runRef());
        if(rows.size()!=1)throw stale();
        var state=read((String)rows.get(0)[1],Map.class);
        String identity=store.encode(proposals);
        if("CONFIRMED".equals(state.get("retrievalState"))&&identity.equals(state.get("proposalIdentity")))return state;
        if(!"DISCOVERY".equals(state.get("retrievalState"))||!Objects.equals(rows.get(0)[0],revision))throw stale();
        String question=(String)rows.get(0)[2];
        var tasks=new ArrayList<RetrievalPlanner.Task>();
        var mentions=new ArrayList<QueryEntityResolver.Mention>();
        Set<String> ids=new HashSet<>();
        boolean catalog=true;
        for(var p:proposals){
            if(p==null||p.resourceKind()==null||p.strategy()==null||p.taskId()==null||!p.taskId().matches("t[1-4]")||!ids.add(p.taskId())||
                    !Set.of("DOCUMENT","SKILL","ASDM-TOOL","RECOMMEND","CATEGORY","CATEGORY_PREFER","SKILL_READ","ROLE_CATALOG").contains(p.resourceKind())||
                    !Set.of("CATALOG","SEMANTIC").contains(p.strategy())||!literal(question,p.actionEvidence()))
                throw RetrievalScopeGuard.denied();
            if("ROLE_CATALOG".equals(p.resourceKind())){
                if(proposals.size()!=1||p.roleId()<=0||p.stage()!=null||p.category()!=null||p.referenceId()!=null
                        ||!"CATALOG".equals(p.strategy()))throw RetrievalScopeGuard.denied();
                var role=candidate(run,p,question);
                int start=question.indexOf(p.roleEvidence());
                String masked=question.substring(0,start)+"@"+question.substring(start+p.roleEvidence().length());
                tasks.addAll(RetrievalPlanner.roleCatalogTasks(role.id(),masked));
                if(tasks.isEmpty())throw RetrievalScopeGuard.denied();
                mentions.add(new QueryEntityResolver.Mention(role.id(),role.name(),role.sourceSystem(),0,0));
                break;
            }
            catalog &= "CATALOG".equals(p.strategy());
            String category=p.category();
            if(category!=null&&(!Set.of("PROJECT_CONTEXT","FDE_WIKI","INTERNAL_KNOWLEDGE","SKILL_TOOL","OTHER").contains(category)
                    ||!literal(question,p.categoryEvidence())))throw RetrievalScopeGuard.denied();
            var prior=p.referenceId()==null?null:prior(run,p,question);
            if("SKILL_READ".equals(p.resourceKind())&&(prior==null||!"SKILL".equals(prior.kind())))
                throw RetrievalScopeGuard.denied();
            boolean scopedKind=Set.of("DOCUMENT","SKILL","ASDM-TOOL").contains(p.resourceKind());
            if(scopedKind&&p.roleId()<=0||p.roleId()<0||
                    !scopedKind&&!Set.of("RECOMMEND","SKILL_READ").contains(p.resourceKind())&&p.roleId()!=0)
                throw RetrievalScopeGuard.denied();
            if(!Set.of("DOCUMENT","CATEGORY","CATEGORY_PREFER").contains(p.resourceKind())&&category!=null)
                throw RetrievalScopeGuard.denied();
            if("CATALOG".equals(p.strategy())&&!scopedKind||
                    Set.of("SKILL","ASDM-TOOL").contains(p.resourceKind())&&!"CATALOG".equals(p.strategy())||
                    Set.of("CATEGORY","CATEGORY_PREFER").contains(p.resourceKind())&&category==null)
                throw RetrievalScopeGuard.denied();
            if(prior!=null){
                if(!Set.of("DOCUMENT","SKILL_READ","SKILL","ASDM-TOOL").contains(p.resourceKind()))
                    throw RetrievalScopeGuard.denied();
                mentions.add(new QueryEntityResolver.Mention(prior.roleId(),prior.roleName(),"",0,0));
                category="DOCUMENT".equals(p.resourceKind())?prior.category():null;
            }else if(p.roleId()>0){
                var selected=candidate(run,p,question);
                if("RECOMMEND".equals(p.resourceKind())&&Arrays.stream(question.split("[，,；;。！？?!]"))
                        .noneMatch(clause->clause.contains(p.roleEvidence())&&clause.contains(p.actionEvidence())))
                    throw new BusinessException("RETRIEVAL_SCOPE_CONFLICT","独立的推荐请求请使用未限定角色的推荐任务",HttpStatus.CONFLICT);
                mentions.add(new QueryEntityResolver.Mention(selected.id(),selected.name(),selected.sourceSystem(),0,0));
                int start=question.indexOf(p.roleEvidence());
                int end=start+p.roleEvidence().length();
                if(inside(question,p.actionEvidence(),start,end)||category!=null&&inside(question,p.categoryEvidence(),start,end))
                    throw RetrievalScopeGuard.denied();
                String outside=RoleNameMatcher.normalize(question.substring(0,start)+" "+question.substring(end));
                String taskEvidence=RoleNameMatcher.normalize(p.actionEvidence()+" "+Objects.toString(p.categoryEvidence(),""));
                boolean context=(proposals.size()==1?outside:taskEvidence).matches("(?s).*(?:项目上下文|上下文|项目背景).*");
                if(proposals.size()==1&&outside.matches("(?s).*(?:换成|改查).*")){
                    var previous=history(run);
                    var oldRoles=previous.stream().map(RetrievalPlanStore.PriorReference::roleId).distinct().toList();
                    if(oldRoles.size()==1&&oldRoles.get(0)!=p.roleId()){
                        var kinds=previous.stream().map(RetrievalPlanStore.PriorReference::kind).distinct().toList();
                        if(kinds.size()==1&&!kinds.get(0).equals(p.resourceKind())&&!namesRequestedKind(taskEvidence))
                            throw RetrievalScopeGuard.denied();
                        var categories=previous.stream().map(RetrievalPlanStore.PriorReference::category).distinct().toList();
                        if("DOCUMENT".equals(p.resourceKind())&&categories.size()==1&&"PROJECT_CONTEXT".equals(categories.get(0)))context=true;
                    }
                }
                if("DOCUMENT".equals(p.resourceKind())){
                    if(context)category="PROJECT_CONTEXT";
                    else if(category!=null&&!namesCategory(category,p.categoryEvidence()))category=null;
                }
                String kindEvidence=proposals.size()==1?outside:taskEvidence;
                boolean asdmTarget=kindEvidence.matches("(?s).*(?:asdm|工具包|安装包).*");
                boolean install=asdmTarget&&kindEvidence.matches("(?s).*(?:安装|下载).*");
                if("ASDM-TOOL".equals(p.resourceKind())&&!asdmTarget
                        ||install&&!context&&!"ASDM-TOOL".equals(p.resourceKind()))
                    throw RetrievalScopeGuard.denied();
            }
            if("RECOMMEND".equals(p.resourceKind())){
                if(((List<?>)state.get("constraints")).contains("EXCLUDE_SKILL_OUTPUT")||!p.actionEvidence().contains("推荐")||p.stage()==null)
                    throw RetrievalScopeGuard.denied();
                com.company.skillplatform.skill.domain.DevelopmentStage.valueOf(p.stage());
            }else if(p.stage()!=null)throw RetrievalScopeGuard.denied();
            if(roleRequired(run.runRef())&&p.roleId()==0&&!"RECOMMEND".equals(p.resourceKind()))throw RetrievalScopeGuard.denied();
            boolean pinned=prior!=null&&("SKILL_READ".equals(p.resourceKind())||
                    ("DOCUMENT".equals(p.resourceKind())&&"SEMANTIC".equals(p.strategy())&&"DOCUMENT".equals(prior.kind())
                        &&p.roleEvidence().matches("(?s).*(?:这篇|那篇|第.+篇).*")));
            tasks.add(new RetrievalPlanner.Task(p.taskId(),p.roleId(),p.resourceKind(),category,p.stage(),
                    pinned?prior.objectId():null,pinned?prior.versionId():null,pinned?prior.skillKey():null,p.strategy(),
                    pinned?prior.audience():null));
        }
        var plan=new RetrievalPlanner.Plan(catalog?"CATALOG":"SEMANTIC",tasks.get(0),mentions,tasks);
        var payload=store.payload(plan);
        payload.put("schemaVersion",3);payload.put("retrievalState","CONFIRMED");payload.put("revision",revision+1);
        payload.put("provenance","DSH_INTERPRETED");payload.put("proposalIdentity",identity);
        payload.put("constraints",state.get("constraints"));payload.put("modelAdvice",state.get("modelAdvice"));
        if(db.update("""
                UPDATE agent_run_intent_plan SET status='READY', retrieval_plan_json=?, plan_revision=plan_revision+1,
                    intents_json=?, allowed_capabilities_json=?,allowed_artifacts_json=? WHERE run_key=? AND plan_revision=?
                """,store.encode(payload),store.encode(payload.get("intents")),store.encode(payload.get("allowedCapabilities")),
                store.encode(payload.get("allowedArtifacts")),run.runRef(),revision)!=1)throw stale();
        for(var task:tasks)metrics.counter("agent.retrieval.confirmed","kind",task.resourceKind(),"strategy",task.strategy()).increment();
        log.info("[Yanshu][AGENT] runKey={} route=DSH_DISCOVERY planRevision={} tasks={} modelAdviceStatus=SKIPPED",
                run.runRef(),revision+1,tasks.stream().map(RetrievalPlanner.Task::resourceKind).toList());
        return payload;
    }

    private RetrievalPlanStore.PriorReference prior(AgentRun run,Proposal p,String question) {
        if(!literal(question,p.roleEvidence())||!isFollowUp(RoleNameMatcher.normalize(p.roleEvidence()))||
                question.matches("(?s).*(?:换成|改查|不要.{0,16}角色).*"))
            throw RetrievalScopeGuard.denied();
        var refs=store.priorReferences(run.runRef());
        var selected=refs.stream().filter(ref->ref.referenceId()==p.referenceId()&&ref.roleId()==p.roleId())
                .findFirst().orElseThrow(RetrievalScopeGuard::denied);
        if(!live(run,selected))
            throw new BusinessException("RETRIEVAL_VERSION_CHANGED","上轮资料已更新或不可访问，请重新查询",HttpStatus.CONFLICT);
        String reference=RoleNameMatcher.normalize(p.roleEvidence());
        String ordinal=reference.replaceFirst("(?s).*第([一二三四五六七八九十0-9]+)[个项篇].*","$1");
        if(!ordinal.equals(reference)){
            int n=switch(ordinal){
                case "一" -> 1; case "二" -> 2; case "三" -> 3; case "四" -> 4; case "五" -> 5;
                case "六" -> 6; case "七" -> 7; case "八" -> 8; case "九" -> 9; case "十" -> 10;
                default -> ordinal.matches("[0-9]{1,2}")?Integer.parseInt(ordinal):-1;
            };
            if(n!=selected.ordinal()||refs.stream().filter(ref->ref.ordinal()==n&&ref.kind().equals(selected.kind())).count()!=1)
                throw RetrievalScopeGuard.denied();
        }else if(("SKILL_READ".equals(p.resourceKind())||reference.contains("这篇")||reference.contains("那篇"))&&
                (!(selected.ordinal()>0||selected.accessed())||refs.stream().filter(ref->ref.kind().equals(selected.kind())
                        &&(ref.ordinal()>0||ref.accessed())).count()!=1)){
            throw RetrievalScopeGuard.denied();
        }else if(refs.stream().map(RetrievalPlanStore.PriorReference::roleId).distinct().count()!=1){
            throw RetrievalScopeGuard.denied();
        }
        return selected;
    }

    private RoleNameMatcher.Candidate candidate(AgentRun run,Proposal p,String question){
        if(!literal(question,p.roleEvidence())||question.substring(0,question.indexOf(p.roleEvidence())).matches("(?s).*(?:不要|排除|不查|不看|别查)(?:(?!换成|改查)[^，。；,;]){0,18}"))throw RetrievalScopeGuard.denied();
        var snapshots=db.query("""
                SELECT normalized_query,requested_source,result_json FROM agent_run_role_resolution WHERE resolution_key=?
                  AND run_key=? AND actor_id=? AND knowledge_scope=? AND expires_at>NOW(3)
                """,(rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3)},
                p.resolutionId(),run.runRef(),run.userId(),run.knowledgeScope());
        if(snapshots.size()!=1)throw RetrievalScopeGuard.denied();
        var data=snapshots.get(0);
        String evidence=RoleNameMatcher.normalize(p.roleEvidence()).replace(" ","");
        if(!evidence.contains(RoleNameMatcher.normalize(data[0]).replace(" ","")))throw RetrievalScopeGuard.denied();
        var saved=read(data[2],RoleNameMatcher.Result.class);
        var previous=saved.candidates().stream().filter(x->x.id()==p.roleId()&&x.canAutoConfirm()).findFirst()
                .orElseThrow(RetrievalScopeGuard::denied);
        if(data[1]!=null&&!question.contains(data[1]))throw RetrievalScopeGuard.denied();
        var current=RoleNameMatcher.search(roles.visibleNames(run.userId(),run.knowledgeScope(),data[1]),data[0],10,false);
        return current.candidates().stream().filter(x->x.id()==p.roleId()&&x.canAutoConfirm()
                &&x.name().equals(previous.name())&&x.matchedTerm().equals(previous.matchedTerm()))
                .findFirst().orElseThrow(RetrievalScopeGuard::denied);
    }

    private boolean namesRequestedKind(String evidence){
        return evidence.matches("(?s).*(?:文档|上下文|项目背景|skill|技能|asdm|工具).*");
    }

    private boolean namesCategory(String category,String evidence){
        String text=RoleNameMatcher.normalize(evidence);
        return switch(category){
            case "PROJECT_CONTEXT" -> text.contains("上下文")||text.contains("项目背景");
            case "INTERNAL_KNOWLEDGE" -> text.contains("内部知识")||text.contains("企业知识");
            case "FDE_WIKI" -> text.contains("fde");
            case "SKILL_TOOL" -> text.contains("skill 与工具")||text.contains("技能与工具");
            case "OTHER" -> text.contains("其他");
            default -> false;
        };
    }

    private boolean literal(String text,String fragment){return fragment!=null&&!fragment.isBlank()&&fragment.length()<=255&&text.contains(fragment);}
    private boolean inside(String text,String fragment,int start,int end){
        if(fragment==null)return false;
        for(int at=text.indexOf(fragment);at>=0;at=text.indexOf(fragment,at+fragment.length()))
            if(at<start||at+fragment.length()>end)return false;
        return true;
    }

    private <T>T read(String value,Class<T> type){
        try{return json.readValue(value,type);}
        catch(Exception e){throw new IllegalStateException("Invalid retrieval state",e);}
    }
    private BusinessException stale(){return new BusinessException("RETRIEVAL_PLAN_STALE","检索计划已变化",HttpStatus.CONFLICT);}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000)
    public void expire(){db.update("DELETE FROM agent_run_role_resolution WHERE expires_at<=NOW(3) LIMIT 1000");}

    public record Proposal(String taskId,long roleId,String resolutionId,String resourceKind,String category,
                           String strategy,String roleEvidence,String actionEvidence,String categoryEvidence,String stage,Long referenceId){}
}
