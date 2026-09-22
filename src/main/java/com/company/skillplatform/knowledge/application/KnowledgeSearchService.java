package com.company.skillplatform.knowledge.application;

import com.company.skillplatform.knowledge.domain.*;
import com.company.skillplatform.knowledge.domain.VectorStorePort.VectorSearchHit;
import com.company.skillplatform.knowledge.infrastructure.KnowledgeProperties;
import com.company.skillplatform.skill.domain.SkillStatus;
import com.company.skillplatform.version.domain.LifecycleStatus;
import com.company.skillplatform.wiki.application.WikiDocumentService;
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
    private final EmbeddingPort embedding;private final RerankPort rerank;private final VectorStorePort vectors;private final WikiDocumentService wiki;private final WikiDocumentSkillRepository links;private final KnowledgeProperties properties;private final MeterRegistry metrics;
    public KnowledgeSearchService(EmbeddingPort embedding,RerankPort rerank,VectorStorePort vectors,WikiDocumentService wiki,WikiDocumentSkillRepository links,KnowledgeProperties properties,MeterRegistry metrics){this.embedding=embedding;this.rerank=rerank;this.vectors=vectors;this.wiki=wiki;this.links=links;this.properties=properties;this.metrics=metrics;}
    @Transactional(readOnly=true)
    public KnowledgeSearchResult search(long userId,String knowledgeScope,String query,String developmentStage,int requestedLimit){
        String normalized=query==null?"":query.trim();if(normalized.isBlank()||normalized.length()>1000)throw new IllegalArgumentException("query must contain 1..1000 characters");int limit=Math.min(Math.max(requestedLimit,1),10);long started=System.nanoTime();
        try{if(!properties.isEnabled())return unavailable(normalized);boolean platformOnly="PLATFORM_PUBLIC_ONLY".equals(knowledgeScope);List<Long> teamIds=platformOnly?List.of():wiki.visibleTeams(userId).stream().map(WikiDocumentService.TeamView::id).toList();float[] queryVector=embedding.embedQuery(normalized);List<VectorSearchHit> hits=vectors.search(queryVector,new VectorStorePort.KnowledgeFilter(teamIds,platformOnly),properties.getSearch().getChunkTopK());
            boolean rerankApplied=false;Map<Integer,Double> rerankScores=new HashMap<>();if(properties.getRerank().isEnabled()&&!hits.isEmpty()){try{List<String> candidates=hits.stream().map(this::rerankText).toList();for(var score:rerank.rerank(normalized,candidates,candidates.size()))rerankScores.put(score.index(),score.score());rerankApplied=!rerankScores.isEmpty();}catch(RuntimeException failure){metrics.counter("knowledge.rerank.fallback").increment();log.warn("event=knowledge.rerank.fallback queryHash={} errorType={}",hash(normalized),failure.getClass().getSimpleName());}}
            List<ScoredHit> scored=new ArrayList<>();for(int i=0;i<hits.size();i++){VectorSearchHit hit=hits.get(i);Double rr=rerankScores.get(i);double stageBoost=developmentStage!=null&&strings(hit.payload().get("developmentStages")).contains(developmentStage)?0.02:0;double rank=(rr==null?hit.vectorScore():rr)+stageBoost;scored.add(new ScoredHit(hit,rr,rank));}
            scored.sort(Comparator.comparingDouble(ScoredHit::rank).reversed().thenComparing(s->number(s.hit().payload().get("documentId")).longValue()));LinkedHashMap<Long,List<ScoredHit>> grouped=new LinkedHashMap<>();for(ScoredHit hit:scored){long documentId=number(hit.hit().payload().get("documentId")).longValue();grouped.computeIfAbsent(documentId,ignored->new ArrayList<>()).add(hit);}
            List<KnowledgeSearchResult.DocumentResult> results=new ArrayList<>();for(var entry:grouped.entrySet()){if(results.size()>=Math.min(limit,properties.getSearch().getDocumentTopK()))break;List<ScoredHit> documentHits=entry.getValue();ScoredHit best=documentHits.get(0);Map<String,Object> p=best.hit().payload();List<KnowledgeSearchResult.Snippet> snippets=documentHits.stream().limit(properties.getSearch().getSnippetsPerDocument()).map(h->new KnowledgeSearchResult.Snippet(string(h.hit().payload().get("heading")),string(h.hit().payload().get("content")),h.hit().vectorScore(),h.rerankScore())).toList();results.add(new KnowledgeSearchResult.DocumentResult(entry.getKey(),string(p.get("title")),string(p.get("documentType")),p.get("teamId")==null?"PLATFORM":"TEAM",best.hit().vectorScore(),best.rerankScore(),snippets,linkedSkills(entry.getKey(),teamIds,platformOnly)));}
            metrics.counter("knowledge.search.total").increment();metrics.summary("knowledge.search.result.count").record(results.size());log.info("event=knowledge.search queryHash={} visibleTeamCount={} chunkHits={} documentHits={} linkedSkillCount={} rerankApplied={} totalMs={}",hash(normalized),teamIds.size(),hits.size(),results.size(),results.stream().mapToInt(r->r.linkedSkills().size()).sum(),rerankApplied,elapsed(started));return new KnowledgeSearchResult(normalized,false,rerankApplied,results,grouped.size()>results.size());
        }catch(RuntimeException failure){metrics.counter("knowledge.search.failure").increment();log.warn("event=knowledge.search.unavailable queryHash={} errorType={} totalMs={}",hash(normalized),failure.getClass().getSimpleName(),elapsed(started));return unavailable(normalized);}finally{metrics.timer("knowledge.search.duration").record(java.time.Duration.ofNanos(System.nanoTime()-started));}
    }
    private List<KnowledgeSearchResult.LinkedSkill> linkedSkills(long documentId,List<Long> visibleTeamIds,boolean platformOnly){return links.findByDocumentIdOrderBySortOrderAsc(documentId).stream().map(link->link.getSkill()).filter(skill->skill.getTeamId()==null||(!platformOnly&&visibleTeamIds.contains(skill.getTeamId()))).filter(skill->skill.getStatus()==SkillStatus.ACTIVE&&skill.getLatestPublishedVersion()!=null&&Set.of(LifecycleStatus.PUBLISHED,LifecycleStatus.DEPRECATED).contains(skill.getLatestPublishedVersion().getLifecycleStatus())).map(skill->new KnowledgeSearchResult.LinkedSkill(skill.getSkillKey(),skill.getDisplayName(),skill.getDescription(),skill.getDevelopmentStage()==null?null:skill.getDevelopmentStage().name(),skill.getLatestPublishedVersion().getVersion(),"/skills/"+skill.getSkillKey())).toList();}
    private String rerankText(VectorSearchHit hit){Map<String,Object> p=hit.payload();return string(p.get("title"))+"\n"+String.join(" > ",strings(p.get("headingPath")))+"\n\n"+string(p.get("content"));}
    private KnowledgeSearchResult unavailable(String query){return new KnowledgeSearchResult(query,true,false,List.of(),false);}
    private String string(Object value){return value==null?"":String.valueOf(value);}private Number number(Object value){return value instanceof Number n?n:Long.parseLong(String.valueOf(value));}private List<String> strings(Object value){return value instanceof List<?> list?list.stream().map(String::valueOf).toList():List.of();}
    private String hash(String value){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0,16);}catch(Exception ignored){return "unavailable";}}
    private long elapsed(long started){return(System.nanoTime()-started)/1_000_000;}private record ScoredHit(VectorSearchHit hit,Double rerankScore,double rank){}
}
