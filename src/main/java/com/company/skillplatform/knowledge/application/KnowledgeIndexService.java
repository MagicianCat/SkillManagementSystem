package com.company.skillplatform.knowledge.application;

import com.company.skillplatform.knowledge.domain.*;
import com.company.skillplatform.knowledge.infrastructure.KnowledgeProperties;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentEntity;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentRepository;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentSkillRepository;
import com.company.skillplatform.wiki.application.WikiPromotionStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class KnowledgeIndexService {
    private static final Logger log=LoggerFactory.getLogger(KnowledgeIndexService.class);
    private final WikiDocumentRepository documents;private final WikiDocumentSkillRepository links;private final MarkdownHeadingChunker chunker;
    private final EmbeddingPort embedding;private final VectorStorePort vectors;private final KnowledgeProperties properties;private final MeterRegistry metrics;
    private WikiPromotionStore promotionStore;
    @org.springframework.beans.factory.annotation.Autowired public void setPromotionStore(WikiPromotionStore value){promotionStore=value;}
    public KnowledgeIndexService(WikiDocumentRepository documents,WikiDocumentSkillRepository links,MarkdownHeadingChunker chunker,
            EmbeddingPort embedding,VectorStorePort vectors,KnowledgeProperties properties,MeterRegistry metrics){this.documents=documents;this.links=links;this.chunker=chunker;this.embedding=embedding;this.vectors=vectors;this.properties=properties;this.metrics=metrics;}
    @Transactional(readOnly=true, propagation=Propagation.REQUIRES_NEW)
    public synchronized void reindex(long documentId){
        if(!properties.isEnabled())return;long started=System.nanoTime();
        try{WikiDocumentEntity document=documents.findById(documentId).orElse(null);if(document==null||!"ACTIVE".equals(document.getStatus())||document.getCurrentRevision()==null){vectors.deleteDocument(documentId);return;}
            var skillLinks=links.findByDocumentIdOrderBySortOrderAsc(documentId);List<String> keys=skillLinks.stream().map(link->link.getSkill().getSkillKey()).distinct().toList();List<String> stages=skillLinks.stream().map(link->link.getSkill().getDevelopmentStage()).filter(java.util.Objects::nonNull).map(Enum::name).distinct().toList();var revision=document.getCurrentRevision();
            List<KnowledgeDocument> sources=new java.util.ArrayList<>();
            if(document.getTeamId()!=null) sources.add(new KnowledgeDocument(documentId,revision.getRevisionNo(),revision.getContentSha256(),document.getTitle(),document.getTeamId(),false,document.getStatus(),keys,stages,revision.getMarkdownContent()));
            WikiPromotionStore.PlatformSnapshot approved=promotionStore==null?null:promotionStore.platform(documentId);
            if(document.isPlatformVisible()&&approved!=null){
                List<String> approvedKeys=promotionStore.platformSkills(documentId).stream().map(WikiPromotionStore.PlatformSkillLink::skillKey).toList();
                sources.add(new KnowledgeDocument(documentId,approved.revisionNo(),sha256(approved.markdownContent()),approved.title(),document.getTeamId(),true,document.getStatus(),approvedKeys,List.of(),approved.markdownContent()));
            }
            List<KnowledgeChunk> chunks=sources.stream().flatMap(source->chunker.chunk(source).stream()).toList();
            List<float[]> embedded=chunks.isEmpty()?List.of():embedding.embedDocuments(chunks.stream().map(KnowledgeChunk::embeddingText).toList());if(embedded.size()!=chunks.size())throw new IllegalStateException("Embedding result count mismatch");
            List<KnowledgeVectorPoint> points=new java.util.ArrayList<>();for(int i=0;i<chunks.size();i++)points.add(KnowledgeVectorPoint.from(chunks.get(i),embedded.get(i)));vectors.replaceDocument(documentId,points);metrics.counter("knowledge.index.total").increment();metrics.summary("knowledge.index.chunk.count").record(chunks.size());
            log.info("event=knowledge.index documentId={} revisionNo={} chunkCount={} durationMs={}",documentId,revision.getRevisionNo(),chunks.size(),elapsed(started));
        }catch(RuntimeException failure){metrics.counter("knowledge.index.failure").increment();String errorCode=failure instanceof EmbeddingProviderException provider?provider.providerCode():failure.getClass().getSimpleName();log.warn("event=knowledge.index.failed documentId={} errorType={} errorCode={} durationMs={}",documentId,failure.getClass().getSimpleName(),errorCode,elapsed(started));throw failure;}
        finally{metrics.timer("knowledge.index.duration").record(java.time.Duration.ofNanos(System.nanoTime()-started));}
    }
    public synchronized void delete(long documentId){if(properties.isEnabled())vectors.deleteDocument(documentId);}
    private long elapsed(long started){return (System.nanoTime()-started)/1_000_000;}
    private String sha256(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception failure){throw new IllegalStateException(failure);}}
}
