package com.company.skillplatform.knowledge.application;

import com.company.skillplatform.knowledge.domain.*;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.wiki.domain.WikiKnowledgeCategory;
import org.springframework.http.HttpStatus;
import com.company.skillplatform.ops.OpsEventService;
import com.company.skillplatform.knowledge.domain.VectorStorePort.VectorSearchHit;
import com.company.skillplatform.knowledge.infrastructure.KnowledgeProperties;
import com.company.skillplatform.skill.domain.SkillStatus;
import com.company.skillplatform.version.domain.LifecycleStatus;
import com.company.skillplatform.wiki.application.WikiDocumentService;
import com.company.skillplatform.wiki.application.WikiPromotionStore;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentEntity;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentRepository;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentSkillRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeSearchService {
    private static final Logger log=LoggerFactory.getLogger(KnowledgeSearchService.class);
    private final OpsEventService ops;private final EmbeddingPort embedding;private final RerankPort rerank;private final VectorStorePort vectors;private final WikiDocumentService wiki;private final WikiDocumentRepository documents;private final WikiDocumentSkillRepository links;private final KnowledgeProperties properties;private final MeterRegistry metrics;
    private WikiPromotionStore promotionStore;
    @org.springframework.beans.factory.annotation.Autowired private ExternalRoleLookupService roleLookup;
    @org.springframework.beans.factory.annotation.Autowired private ExternalWikiRoleResolver roleResolver;
    @org.springframework.beans.factory.annotation.Autowired private KnowledgeRoleMetadataSync roleSync;
    @org.springframework.beans.factory.annotation.Autowired public void setPromotionStore(WikiPromotionStore value){promotionStore=value;}
    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeSearchService(EmbeddingPort embedding,RerankPort rerank,VectorStorePort vectors,WikiDocumentService wiki,WikiDocumentRepository documents,WikiDocumentSkillRepository links,KnowledgeProperties properties,MeterRegistry metrics,OpsEventService ops){this.embedding=embedding;this.rerank=rerank;this.vectors=vectors;this.wiki=wiki;this.documents=documents;this.links=links;this.properties=properties;this.metrics=metrics;this.ops=ops;}
    public KnowledgeSearchService(EmbeddingPort embedding,RerankPort rerank,VectorStorePort vectors,WikiDocumentService wiki,WikiDocumentRepository documents,WikiDocumentSkillRepository links,KnowledgeProperties properties,MeterRegistry metrics){this(embedding,rerank,vectors,wiki,documents,links,properties,metrics,null);}
    @Transactional(readOnly=true)
    public KnowledgeSearchResult search(long userId,String knowledgeScope,String query,String developmentStage,int requestedLimit){
        return search(userId,knowledgeScope,query,developmentStage,null,null,requestedLimit);
    }

    @Transactional(readOnly=true)
    public KnowledgeSearchResult search(long userId,String knowledgeScope,String query,String developmentStage,
            String knowledgeCategory,String categoryMode,int requestedLimit){
        return search(userId,knowledgeScope,query,developmentStage,knowledgeCategory,categoryMode,requestedLimit,null,null);
    }

    @Transactional(readOnly=true)
    public KnowledgeSearchResult search(long userId,String knowledgeScope,String query,String developmentStage,
            String knowledgeCategory,String categoryMode,int requestedLimit,List<Long> requestedRoles,String requestedMode){
        String roleMode=validateRoleOptions(requestedRoles,requestedMode);
        List<Long> roleIds=requestedRoles==null?List.of():requestedRoles;
        boolean roleRouted=roleMode!=null;
        String normalized=query==null?"":query.trim();
        if(normalized.isBlank()||normalized.length()>1000)throw new IllegalArgumentException("query must contain 1..1000 characters");
        WikiKnowledgeCategory category=WikiKnowledgeCategory.parse(knowledgeCategory,null);
        CategoryMode mode=CategoryMode.parse(categoryMode);
        if(mode==CategoryMode.STRICT&&category==null)
            throw new BusinessException("KNOWLEDGE_CATEGORY_REQUIRED","STRICT mode requires a knowledge category",HttpStatus.BAD_REQUEST);
        int limit=Math.min(Math.max(requestedLimit,1),10);long started=System.nanoTime();
        boolean platformOnly="PLATFORM_PUBLIC_ONLY".equals(knowledgeScope);
        if(roleRouted && !properties.isEnabled()) throw ExternalRoleLookupService.bad("ROLE_SEARCH_NOT_READY","角色检索尚未就绪");
        if(!properties.isEnabled())return unavailable(normalized);
        List<Long> teamIds=platformOnly?List.of():wiki.visibleTeams(userId).stream().map(WikiDocumentService.TeamView::id).toList();
        List<ExternalRoleLookupService.Role> selectedRoles=roleRouted?roleLookup.requireVisible(roleIds,teamIds):List.of();
        try{
            float[] queryVector=embedding.embedQuery(normalized);
            int topK=properties.getSearch().getChunkTopK();
            boolean routed=category!=null&&(roleRouted||properties.getSearch().isCategoryRoutingEnabled()||mode==CategoryMode.STRICT);
            Map<Long,ExternalRoleMetadata> metadata=new HashMap<>();
            String categoryFilter=routed?category.name():null;
            List<VectorSearchHit> indexedHits=vectors.search(queryVector,new VectorStorePort.KnowledgeFilter(teamIds,platformOnly,categoryFilter,roleIds,false),topK);
            List<VectorSearchHit> hits=roleHits(indexedHits,teamIds,platformOnly,roleIds,false,metadata);
            boolean fallbackUsed=false;
            int queryCount=1;
            if(routed&&mode==CategoryMode.PREFER&&insufficient(hits,limit)){
                queryCount++;
                var broad=vectors.search(queryVector,new VectorStorePort.KnowledgeFilter(teamIds,platformOnly,null,roleIds,false),topK);
                hits=merge(hits,roleHits(broad,teamIds,platformOnly,roleIds,false,metadata));
                fallbackUsed=true;
            }
            boolean roleIndexPending=roleRouted&&insufficient(hits,limit)&&roleLookup.pending(roleIds,teamIds);
            if("PREFER".equals(roleMode)&&insufficient(hits,limit)){
                queryCount++;
                var general=vectors.search(queryVector,new VectorStorePort.KnowledgeFilter(teamIds,platformOnly,
                        mode==CategoryMode.STRICT?categoryFilter:null,List.of(),true),topK);
                hits=merge(hits,roleHits(general,teamIds,platformOnly,List.of(),true,metadata));
            }
            hits=preferTeamAudience(hits);
            if(roleRouted) hits=hits.stream().limit(36).toList();
            if(!roleRouted&&!indexedHits.isEmpty()&&hits.isEmpty())return unavailable(normalized);
            boolean rerankApplied=false;Map<Integer,Double> rerankScores=new HashMap<>();
            if(properties.getRerank().isEnabled()&&!hits.isEmpty()){
                try{List<String> candidates=hits.stream().map(hit->rerankText(hit,roleRouted?metadata.get(documentId(hit)):null)).toList();
                    for(var score:rerank.rerank(roleRouted?roleLine(selectedRoles.stream().map(ExternalRoleLookupService.Role::name).toList())+normalized:normalized,candidates,candidates.size()))rerankScores.put(score.index(),score.score());
                    rerankApplied=!rerankScores.isEmpty();
                }catch(RuntimeException failure){metrics.counter("knowledge.rerank.fallback").increment();log.warn("[Yanshu][RERANK] event=knowledge.rerank.fallback queryHash={} errorType={}",hash(normalized),failure.getClass().getSimpleName());}
            }
            List<ScoredHit> scored=new ArrayList<>();
            for(int i=0;i<hits.size();i++){
                VectorSearchHit hit=hits.get(i);Double rr=rerankScores.get(i);
                double stageBoost=developmentStage!=null&&strings(hit.payload().get("developmentStages")).contains(developmentStage)?0.02:0;
                double categoryBoost=routed&&category.name().equals(hit.payload().get("knowledgeCategory"))?properties.getSearch().getCategoryBoost():0;
                scored.add(new ScoredHit(hit,rr,(rr==null?hit.vectorScore():rr)+stageBoost+categoryBoost));
            }
            scored.sort(Comparator.<ScoredHit,Boolean>comparing(hit -> roleRouted && !matches(metadata.get(documentId(hit.hit())),roleIds))
                    .thenComparing(Comparator.comparingDouble(ScoredHit::rank).reversed()).thenComparing(s->number(s.hit().payload().get("documentId")).longValue()));
            LinkedHashMap<DocumentAudience,List<ScoredHit>> grouped=new LinkedHashMap<>();
            for(ScoredHit hit:scored){Map<String,Object> payload=hit.hit().payload();
                grouped.computeIfAbsent(new DocumentAudience(number(payload.get("documentId")).longValue(),string(payload.get("audience"))),ignored->new ArrayList<>()).add(hit);
            }
            List<KnowledgeSearchResult.DocumentResult> results=new ArrayList<>();Set<Long> returned=new HashSet<>();
            for(var entry:grouped.entrySet()){
                if(results.size()>=Math.min(limit,properties.getSearch().getDocumentTopK()))break;
                if(!returned.add(entry.getKey().documentId()))continue;
                List<ScoredHit> documentHits=entry.getValue();ScoredHit best=documentHits.get(0);Map<String,Object> payload=best.hit().payload();
                List<KnowledgeSearchResult.Snippet> snippets=documentHits.stream().limit(properties.getSearch().getSnippetsPerDocument())
                        .map(hit->new KnowledgeSearchResult.Snippet(string(hit.hit().payload().get("heading")),string(hit.hit().payload().get("content")),hit.hit().vectorScore(),hit.rerankScore())).toList();
                var roles=metadata.getOrDefault(entry.getKey().documentId(),ExternalRoleMetadata.LOCAL);
                var ids=roles.roles().stream().map(ExternalRoleMetadata.Role::id).toList();
                var matched=ids.stream().filter(roleIds::contains).toList();
                results.add(new KnowledgeSearchResult.DocumentResult(entry.getKey().documentId(),string(payload.get("title")),entry.getKey().audience(),
                        best.hit().vectorScore(),best.rerankScore(),snippets,linkedSkills(entry.getKey().documentId(),entry.getKey().audience(),teamIds),string(payload.get("knowledgeCategory")),
                        roles.sourceSystem()==null?"LOCAL_WIKI":"EXTERNAL_RESOURCE",roles.sourceSystem(),ids,
                        roles.roles().stream().map(ExternalRoleMetadata.Role::name).toList(),roles.roles(),
                        roleRouted?(matched.isEmpty()?"GENERAL_FALLBACK":"MATCHED"):"NOT_REQUESTED",matched));
            }
            metrics.counter("knowledge.search.total").increment();metrics.summary("knowledge.search.result.count").record(results.size());
            metrics.counter("knowledge.search.category", "mode",routed?mode.name():"NONE", "category",routed?category.name():"NONE", "fallback",String.valueOf(fallbackUsed)).increment();
            log.info("event=knowledge.search queryHash={} visibleTeamCount={} chunkHits={} documentHits={} linkedSkillCount={} category={} mode={} fallbackUsed={} rerankApplied={} totalMs={}",
                    hash(normalized),teamIds.size(),hits.size(),results.size(),results.stream().mapToInt(result->result.linkedSkills().size()).sum(),routed?category.name():"NONE",routed?mode.name():"NONE",fallbackUsed,rerankApplied,elapsed(started));
            boolean roleFallback=results.stream().anyMatch(result->"GENERAL_FALLBACK".equals(result.roleMatch()));
            if(roleRouted){
                metrics.counter("knowledge.search.role_route","mode",roleMode,"fallback",String.valueOf(roleFallback)).increment();
                metrics.timer("knowledge.search.role.duration","mode",roleMode)
                        .record(java.time.Duration.ofNanos(System.nanoTime()-started));
                long matchedCount=results.stream().filter(result->"MATCHED".equals(result.roleMatch())).count();
                if(matchedCount==0)metrics.counter("knowledge.search.role.empty","mode",roleMode).increment();
                log.info("event=knowledge.search.role_route roleMode={} categoryMode={} roleCount={} matchedDocs={} fallbackDocs={} queryCount={} indexPending={} rerankApplied={} totalMs={}",
                        roleMode,mode,roleIds.size(),matchedCount,results.size()-matchedCount,queryCount,roleIndexPending,rerankApplied,elapsed(started));
            }
            return new KnowledgeSearchResult(normalized,false,rerankApplied,results,grouped.size()>results.size(),roleMode,selectedRoles,roleFallback,roleIndexPending);
        }catch(RuntimeException failure){metrics.counter("knowledge.search.failure").increment();log.warn("[Yanshu][WIKI_INDEX] event=knowledge.search.unavailable queryHash={} errorType={} totalMs={}",hash(normalized),failure.getClass().getSimpleName(),elapsed(started),failure);if(ops!=null)ops.recordFailure(new OpsEventService.Event(null,"knowledge.search.request","WIKI_INDEX","search","DEGRADED",knowledgeScope,"WIKI_SEARCH_UNAVAILABLE",null,null,null,null,elapsed(started),null));return new KnowledgeSearchResult(normalized,true,false,List.of(),false,roleMode,selectedRoles,false,false);}
        finally{metrics.timer("knowledge.search.duration").record(java.time.Duration.ofNanos(System.nanoTime()-started));}
    }
    private enum CategoryMode { PREFER, STRICT;
        static CategoryMode parse(String value){
            if(value==null)return PREFER;
            try{return valueOf(value);}catch(IllegalArgumentException failure){throw new BusinessException("KNOWLEDGE_CATEGORY_MODE_INVALID","Invalid knowledge category mode",HttpStatus.BAD_REQUEST);}
        }
    }
    private record DocumentAudience(long documentId,String audience) {}
    private List<VectorSearchHit> freshHits(List<VectorSearchHit> hits,List<Long> teamIds,boolean platformOnly){
        if(hits.isEmpty())return hits;
        Set<Long> ids=new HashSet<>();for(VectorSearchHit hit:hits)try{ids.add(number(hit.payload().get("documentId")).longValue());}catch(RuntimeException ignored){/* invalid vector payload */}
        Map<Long,WikiDocumentEntity> current=new HashMap<>();documents.findAllById(ids).forEach(document->current.put(document.getId(),document));
        List<VectorSearchHit> fresh=new ArrayList<>();
        for(VectorSearchHit hit:hits){
            try{
                Map<String,Object> payload=hit.payload();WikiDocumentEntity document=current.get(number(payload.get("documentId")).longValue());
                if(document==null||!"ACTIVE".equals(document.getStatus())||document.getCurrentRevision()==null)continue;
                boolean platform=Boolean.TRUE.equals(payload.get("platformVisible"));
                if(!Objects.equals(platform?"PLATFORM":"TEAM",payload.get("audience")))continue;
                if(platform){
                    if(!document.isPlatformVisible()||promotionStore==null)continue;
                    WikiPromotionStore.PlatformSnapshot approved=promotionStore.platform(document.getId());
                    if(approved==null||approved.revisionNo()!=number(payload.get("revisionNo")).intValue()||!Objects.equals(approved.title(),payload.get("title"))||!Objects.equals(hashContent(approved.markdownContent()),payload.get("contentSha256"))||!Objects.equals(approved.knowledgeCategory(),payload.get("knowledgeCategory")))continue;
                }else{
                    if(platformOnly||document.getTeamId()==null||!teamIds.contains(document.getTeamId()))continue;
                    if(document.getCurrentRevision().getRevisionNo()!=number(payload.get("revisionNo")).intValue())continue;
                    if(!Objects.equals(document.getCurrentRevision().getContentSha256(),payload.get("contentSha256")))continue;
                    if(document.getKnowledgeCategory()==null||!Objects.equals(document.getKnowledgeCategory().name(),payload.get("knowledgeCategory")))continue;
                }
                fresh.add(hit);
            }catch(RuntimeException ignored){/* malformed or stale vector payload */}
        }
        if(fresh.size()!=hits.size()){metrics.counter("knowledge.search.stale_hit").increment(hits.size()-fresh.size());log.info("event=knowledge.search.stale_hits dropped={}",hits.size()-fresh.size());}
        return fresh;
    }
    private List<VectorSearchHit> preferTeamAudience(List<VectorSearchHit> hits){
        Set<Long> teamDocuments=new HashSet<>();
        for(VectorSearchHit hit:hits)if("TEAM".equals(hit.payload().get("audience")))teamDocuments.add(number(hit.payload().get("documentId")).longValue());
        return hits.stream().filter(hit->!"PLATFORM".equals(hit.payload().get("audience"))||!teamDocuments.contains(number(hit.payload().get("documentId")).longValue())).toList();
    }
    private List<KnowledgeSearchResult.LinkedSkill> linkedSkills(long documentId,String audience,List<Long> visibleTeamIds){
        WikiDocumentEntity document=documents.findById(documentId).orElse(null);
        boolean teamAudience="TEAM".equals(audience)&&document!=null&&document.getTeamId()!=null&&visibleTeamIds.contains(document.getTeamId());
        if(!teamAudience)return promotionStore==null?List.of():promotionStore.platformSkills(documentId).stream().map(link->new KnowledgeSearchResult.LinkedSkill(link.skillKey(),link.displayName(),"",null,"","/skills/"+link.skillKey())).toList();
        return links.findByDocumentIdOrderBySortOrderAsc(documentId).stream().map(link->link.getSkill()).filter(skill->"PLATFORM".equals(skill.getScopeType())||visibleTeamIds.contains(skill.getTeamId())).filter(skill->skill.getStatus()==SkillStatus.ACTIVE&&skill.getLatestPublishedVersion()!=null&&Set.of(LifecycleStatus.PUBLISHED,LifecycleStatus.DEPRECATED).contains(skill.getLatestPublishedVersion().getLifecycleStatus())).map(skill->new KnowledgeSearchResult.LinkedSkill(skill.getSkillKey(),skill.getDisplayName(),skill.getDescription(),skill.getDevelopmentStage()==null?null:skill.getDevelopmentStage().name(),skill.getLatestPublishedVersion().getVersion(),"/skills/"+skill.getSkillKey())).toList();}
    public static String validateRoleOptions(List<Long> ids,String mode) {
        if(ids==null&&mode==null)return null;
        if(ids==null||ids.isEmpty())throw ExternalRoleLookupService.bad("KNOWLEDGE_ROLE_REQUIRED","请选择外部角色");
        if(ids.size()>10||ids.stream().anyMatch(id->id==null||id<=0)||new HashSet<>(ids).size()!=ids.size())
            throw ExternalRoleLookupService.bad("KNOWLEDGE_ROLE_IDS_INVALID","角色 ID 必须是 1 至 10 个不同的正整数");
        if(mode!=null&&!Set.of("STRICT","PREFER").contains(mode))
            throw ExternalRoleLookupService.bad("KNOWLEDGE_ROLE_MODE_INVALID","角色模式必须为 STRICT 或 PREFER");
        return mode==null?"PREFER":mode;
    }
    private long documentId(VectorSearchHit hit){return number(hit.payload().get("documentId")).longValue();}
    private boolean insufficient(List<VectorSearchHit> hits,int limit){return hits.stream().map(this::documentId).distinct().count()<Math.min(limit,3);}
    private boolean matches(ExternalRoleMetadata metadata,List<Long> ids){
        return metadata!=null&&metadata.roles().stream().anyMatch(role->ids.contains(role.id()));
    }
    private List<VectorSearchHit> merge(List<VectorSearchHit> first,List<VectorSearchHit> second){
        Map<String,VectorSearchHit> merged=new LinkedHashMap<>();
        first.forEach(hit->merged.put(hit.pointId(),hit));
        second.forEach(hit->merged.putIfAbsent(hit.pointId(),hit));
        return new ArrayList<>(merged.values());
    }
    private List<VectorSearchHit> roleHits(List<VectorSearchHit> indexed,List<Long> teams,boolean platformOnly,
            List<Long> ids,boolean general,Map<Long,ExternalRoleMetadata> metadata){
        var fresh=freshHits(indexed,teams,platformOnly);
        if(roleResolver==null)return fresh;
        metadata.putAll(roleResolver.resolveAll(fresh.stream().map(this::documentId).filter(id->!metadata.containsKey(id)).toList()));
        fresh.stream().filter(hit->!Objects.equals(hit.payload().get("roleMetadataFingerprint"),
                metadata.get(documentId(hit)).toPayload().get("roleMetadataFingerprint")))
                .map(this::documentId).distinct().forEach(id->{
                    try { roleSync.requestRepair(id); }
                    catch(RuntimeException failure){log.warn("event=knowledge.role_metadata.search_repair_failed documentId={}",id,failure);}
                });
        return fresh.stream().filter(hit->{
            var value=metadata.get(documentId(hit));
            return general?value.sourceSystem()==null:ids.isEmpty()||matches(value,ids);
        }).toList();
    }
    private String roleLine(List<String> names){
        if(names.isEmpty())return "";
        String clean=String.join("；",names).replaceAll("[\\p{Cc}\\p{Cf}]"," ");
        return "所属外部角色："+(clean.length()>512?clean.substring(0,512)+"…":clean)+"\n";
    }
    private String rerankText(VectorSearchHit hit,ExternalRoleMetadata metadata){
        Map<String,Object> p=hit.payload();
        String context=metadata==null?"":roleLine(metadata.roles().stream().map(ExternalRoleMetadata.Role::name).toList())
                +"知识分类："+string(p.get("knowledgeCategory"))+"\n";
        return context+string(p.get("title"))+"\n"+String.join(" > ",strings(p.get("headingPath")))+"\n\n"+string(p.get("content"));
    }
    private KnowledgeSearchResult unavailable(String query){return new KnowledgeSearchResult(query,true,false,List.of(),false);}
    private String string(Object value){return value==null?"":String.valueOf(value);}private Number number(Object value){return value instanceof Number n?n:Long.parseLong(String.valueOf(value));}private List<String> strings(Object value){return value instanceof List<?> list?list.stream().map(String::valueOf).toList():List.of();}
    private String hash(String value){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0,16);}catch(Exception ignored){return "unavailable";}}
    private String hashContent(String value){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ignored){return "unavailable";}}
    private long elapsed(long started){return(System.nanoTime()-started)/1_000_000;}private record ScoredHit(VectorSearchHit hit,Double rerankScore,double rank){}
}
