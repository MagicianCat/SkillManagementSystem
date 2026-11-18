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
        String normalized=query==null?"":query.trim();
        if(normalized.isBlank()||normalized.length()>1000)throw new IllegalArgumentException("query must contain 1..1000 characters");
        WikiKnowledgeCategory category=WikiKnowledgeCategory.parse(knowledgeCategory,null);
        CategoryMode mode=CategoryMode.parse(categoryMode);
        if(mode==CategoryMode.STRICT&&category==null)
            throw new BusinessException("KNOWLEDGE_CATEGORY_REQUIRED","STRICT mode requires a knowledge category",HttpStatus.BAD_REQUEST);
        int limit=Math.min(Math.max(requestedLimit,1),10);long started=System.nanoTime();
        try{
            if(!properties.isEnabled())return unavailable(normalized);
            boolean platformOnly="PLATFORM_PUBLIC_ONLY".equals(knowledgeScope);
            List<Long> teamIds=platformOnly?List.of():wiki.visibleTeams(userId).stream().map(WikiDocumentService.TeamView::id).toList();
            float[] queryVector=embedding.embedQuery(normalized);
            int topK=properties.getSearch().getChunkTopK();
            boolean routed=category!=null&&(properties.getSearch().isCategoryRoutingEnabled()||mode==CategoryMode.STRICT);
            List<VectorSearchHit> indexedHits=vectors.search(queryVector,new VectorStorePort.KnowledgeFilter(teamIds,platformOnly,routed?category.name():null),topK);
            List<VectorSearchHit> hits=freshHits(indexedHits,teamIds,platformOnly);
            boolean fallbackUsed=false;
            if(routed&&mode==CategoryMode.PREFER&&hits.stream().map(hit->number(hit.payload().get("documentId")).longValue()).distinct().count()<Math.min(limit,3)){
                List<VectorSearchHit> broad=vectors.search(queryVector,new VectorStorePort.KnowledgeFilter(teamIds,platformOnly),topK);
                indexedHits=new ArrayList<>(indexedHits);indexedHits.addAll(broad);
                Map<String,VectorSearchHit> merged=new LinkedHashMap<>();
                for(VectorSearchHit hit:hits)merged.put(hit.pointId(),hit);
                for(VectorSearchHit hit:freshHits(broad,teamIds,platformOnly))merged.putIfAbsent(hit.pointId(),hit);
                hits=new ArrayList<>(merged.values());fallbackUsed=true;
            }
            hits=preferTeamAudience(hits);
            if(!indexedHits.isEmpty()&&hits.isEmpty())return unavailable(normalized);
            boolean rerankApplied=false;Map<Integer,Double> rerankScores=new HashMap<>();
            if(properties.getRerank().isEnabled()&&!hits.isEmpty()){
                try{List<String> candidates=hits.stream().map(this::rerankText).toList();
                    for(var score:rerank.rerank(normalized,candidates,candidates.size()))rerankScores.put(score.index(),score.score());
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
            scored.sort(Comparator.comparingDouble(ScoredHit::rank).reversed().thenComparing(s->number(s.hit().payload().get("documentId")).longValue()));
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
                results.add(new KnowledgeSearchResult.DocumentResult(entry.getKey().documentId(),string(payload.get("title")),entry.getKey().audience(),
                        best.hit().vectorScore(),best.rerankScore(),snippets,linkedSkills(entry.getKey().documentId(),entry.getKey().audience(),teamIds),string(payload.get("knowledgeCategory"))));
            }
            metrics.counter("knowledge.search.total").increment();metrics.summary("knowledge.search.result.count").record(results.size());
            metrics.counter("knowledge.search.category", "mode",routed?mode.name():"NONE", "category",routed?category.name():"NONE", "fallback",String.valueOf(fallbackUsed)).increment();
            log.info("event=knowledge.search queryHash={} visibleTeamCount={} chunkHits={} documentHits={} linkedSkillCount={} category={} mode={} fallbackUsed={} rerankApplied={} totalMs={}",
                    hash(normalized),teamIds.size(),hits.size(),results.size(),results.stream().mapToInt(result->result.linkedSkills().size()).sum(),routed?category.name():"NONE",routed?mode.name():"NONE",fallbackUsed,rerankApplied,elapsed(started));
            return new KnowledgeSearchResult(normalized,false,rerankApplied,results,grouped.size()>results.size());
        }catch(RuntimeException failure){metrics.counter("knowledge.search.failure").increment();log.warn("[Yanshu][WIKI_INDEX] event=knowledge.search.unavailable queryHash={} errorType={} totalMs={}",hash(normalized),failure.getClass().getSimpleName(),elapsed(started),failure);if(ops!=null)ops.recordFailure(new OpsEventService.Event(null,"knowledge.search.request","WIKI_INDEX","search","DEGRADED",knowledgeScope,"WIKI_SEARCH_UNAVAILABLE",null,null,null,null,elapsed(started),null));return unavailable(normalized);}
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
        fresh=preferTeamAudience(fresh);
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
    private String rerankText(VectorSearchHit hit){Map<String,Object> p=hit.payload();return string(p.get("title"))+"\n"+String.join(" > ",strings(p.get("headingPath")))+"\n\n"+string(p.get("content"));}
    private KnowledgeSearchResult unavailable(String query){return new KnowledgeSearchResult(query,true,false,List.of(),false);}
    private String string(Object value){return value==null?"":String.valueOf(value);}private Number number(Object value){return value instanceof Number n?n:Long.parseLong(String.valueOf(value));}private List<String> strings(Object value){return value instanceof List<?> list?list.stream().map(String::valueOf).toList():List.of();}
    private String hash(String value){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0,16);}catch(Exception ignored){return "unavailable";}}
    private String hashContent(String value){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ignored){return "unavailable";}}
    private long elapsed(long started){return(System.nanoTime()-started)/1_000_000;}private record ScoredHit(VectorSearchHit hit,Double rerankScore,double rank){}
}
