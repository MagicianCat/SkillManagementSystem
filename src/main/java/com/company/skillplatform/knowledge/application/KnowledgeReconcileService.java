package com.company.skillplatform.knowledge.application;

import com.company.skillplatform.knowledge.domain.VectorStorePort;
import com.company.skillplatform.knowledge.infrastructure.KnowledgeProperties;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentRepository;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentSkillRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeReconcileService {
    private static final Logger log=LoggerFactory.getLogger(KnowledgeReconcileService.class);private final WikiDocumentRepository documents;private final WikiDocumentSkillRepository links;private final VectorStorePort vectors;private final KnowledgeIndexService index;private final KnowledgeProperties properties;private final AtomicBoolean running=new AtomicBoolean();
    public KnowledgeReconcileService(WikiDocumentRepository documents,WikiDocumentSkillRepository links,VectorStorePort vectors,KnowledgeIndexService index,KnowledgeProperties properties){this.documents=documents;this.links=links;this.vectors=vectors;this.index=index;this.properties=properties;}
    @Scheduled(initialDelayString="${knowledge.reconcile.initial-delay-ms:30000}",fixedDelayString="${knowledge.reconcile.fixed-delay-ms:600000}")
    @Transactional(readOnly=true)
    public void scheduled(){if(!properties.isEnabled()||!properties.getReconcile().isEnabled()||!running.compareAndSet(false,true))return;try{reconcile();}catch(RuntimeException failure){log.warn("event=knowledge.reconcile.failed errorType={}",failure.getClass().getSimpleName());}finally{running.set(false);}}
    public synchronized ReconcileResult reconcile(){vectors.ensureCollection();var active=documents.findByStatusOrderByIdAsc("ACTIVE");Set<Long> activeIds=new HashSet<>();int rebuilt=0,deleted=0;
        for(var document:active){activeIds.add(document.getId());if(document.getCurrentRevision()==null)continue;var skillLinks=links.findByDocumentIdOrderBySortOrderAsc(document.getId());List<String> keys=skillLinks.stream().map(link->link.getSkill().getSkillKey()).distinct().toList();List<String> stages=skillLinks.stream().map(link->link.getSkill().getDevelopmentStage()).filter(Objects::nonNull).map(Enum::name).distinct().toList();var state=vectors.documentState(document.getId());if(state==null||state.revisionNo()!=document.getCurrentRevision().getRevisionNo()||!Objects.equals(state.contentSha256(),document.getCurrentRevision().getContentSha256())||!Objects.equals(state.teamId(),document.getTeamId())||state.platformVisible()!=document.isPlatformVisible()||!new HashSet<>(state.skillKeys()).equals(new HashSet<>(keys))||!new HashSet<>(state.developmentStages()).equals(new HashSet<>(stages))){index.reindex(document.getId());rebuilt++;}}
        for(Long indexed:vectors.indexedDocumentIds())if(!activeIds.contains(indexed)){vectors.deleteDocument(indexed);deleted++;}log.info("event=knowledge.reconcile activeDocuments={} rebuilt={} deleted={}",active.size(),rebuilt,deleted);return new ReconcileResult(active.size(),rebuilt,deleted);}
    @Transactional(readOnly=true) public ReconcileStatus status(){Set<Long> active=documents.findByStatusOrderByIdAsc("ACTIVE").stream().map(document->document.getId()).collect(java.util.stream.Collectors.toSet());Set<Long> indexed=vectors.indexedDocumentIds();Set<Long> missing=new HashSet<>(active);missing.removeAll(indexed);Set<Long> orphaned=new HashSet<>(indexed);orphaned.removeAll(active);return new ReconcileStatus(active.size(),indexed.size(),missing.size(),orphaned.size());}
    public record ReconcileResult(int activeDocuments,int rebuilt,int deleted){}
    public record ReconcileStatus(int activeDocuments,int indexedDocuments,int missingDocuments,int orphanedDocuments){}
}
