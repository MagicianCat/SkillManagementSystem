package com.company.skillplatform.knowledge.application;

import com.company.skillplatform.knowledge.domain.VectorStorePort;
import com.company.skillplatform.knowledge.domain.EmbeddingProviderException;
import com.company.skillplatform.knowledge.infrastructure.KnowledgeProperties;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentRepository;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentSkillRepository;
import com.company.skillplatform.wiki.application.WikiPromotionStore;
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
    private static final Logger log=LoggerFactory.getLogger(KnowledgeReconcileService.class);private final WikiDocumentRepository documents;private final WikiDocumentSkillRepository links;private final VectorStorePort vectors;private final KnowledgeIndexService index;private final KnowledgeIndexEventListener retry;private final KnowledgeProperties properties;private final AtomicBoolean running=new AtomicBoolean();
    private WikiPromotionStore promotionStore;
    @org.springframework.beans.factory.annotation.Autowired public void setPromotionStore(WikiPromotionStore value){promotionStore=value;}
    public KnowledgeReconcileService(WikiDocumentRepository documents,WikiDocumentSkillRepository links,VectorStorePort vectors,KnowledgeIndexService index,KnowledgeIndexEventListener retry,KnowledgeProperties properties){this.documents=documents;this.links=links;this.vectors=vectors;this.index=index;this.retry=retry;this.properties=properties;}
    @Scheduled(initialDelayString="${knowledge.reconcile.initial-delay-ms:30000}",fixedDelayString="${knowledge.reconcile.fixed-delay-ms:600000}")
    @Transactional(readOnly=true)
    public void scheduled(){if(!properties.isEnabled()||!properties.getReconcile().isEnabled()||!running.compareAndSet(false,true))return;try{reconcile();}catch(RuntimeException failure){log.warn("event=knowledge.reconcile.failed errorType={}",failure.getClass().getSimpleName());}finally{running.set(false);}}
    @Transactional(readOnly=true)
    public synchronized ReconcileResult manualReconcile() {
        retry.resetForManualReconcile();
        return reconcile(true);
    }

    public boolean reserveManualReconcile() { return running.compareAndSet(false, true); }
    public void releaseManualReconcile() { running.set(false); }

    @Transactional(readOnly=true)
    public synchronized ReconcileResult reconcile(){ return reconcile(false); }

    private ReconcileResult reconcile(boolean force){
        vectors.ensureCollection();
        var active=documents.findByStatusOrderByIdAsc("ACTIVE");
        Set<Long> activeIds=new HashSet<>();
        for(var document:active) activeIds.add(document.getId());
        int rebuilt=0,deleted=0,failed=0;
        if(!retry.isProviderQuotaExhausted()) for(var document:active){
            if(document.getCurrentRevision()==null||retry.isExhausted(document.getId())||retry.isScheduled(document.getId())) continue;
            try{
                if(force){index.reindex(document.getId());rebuilt++;continue;}
                var skillLinks=links.findByDocumentIdOrderBySortOrderAsc(document.getId());
                List<String> keys=skillLinks.stream().map(link->link.getSkill().getSkillKey()).distinct().toList();
                List<String> stages=skillLinks.stream().map(link->link.getSkill().getDevelopmentStage()).filter(Objects::nonNull).map(Enum::name).distinct().toList();
                boolean teamExpected=document.getTeamId()!=null;
                var approved=document.isPlatformVisible()&&promotionStore!=null?promotionStore.platform(document.getId()):null;
                boolean platformExpected=approved!=null;
                var teamState=teamExpected?vectors.documentState(document.getId(),"TEAM"):null;
                var platformState=platformExpected?vectors.documentState(document.getId(),"PLATFORM"):null;
                List<String> approvedKeys=platformExpected?promotionStore.platformSkills(document.getId()).stream().map(WikiPromotionStore.PlatformSkillLink::skillKey).toList():List.of();
                boolean teamStale=teamExpected&&(teamState==null||teamState.revisionNo()!=document.getCurrentRevision().getRevisionNo()
                        ||!Objects.equals(teamState.contentSha256(),document.getCurrentRevision().getContentSha256())
                        ||!Objects.equals(teamState.teamId(),document.getTeamId())||teamState.platformVisible()
                        ||!new HashSet<>(teamState.skillKeys()).equals(new HashSet<>(keys))
                        ||!new HashSet<>(teamState.developmentStages()).equals(new HashSet<>(stages)));
                boolean platformStale=platformExpected&&(platformState==null||platformState.revisionNo()!=approved.revisionNo()
                        ||!Objects.equals(platformState.contentSha256(),sha256(approved.markdownContent()))
                        ||!platformState.platformVisible()||!new HashSet<>(platformState.skillKeys()).equals(new HashSet<>(approvedKeys)));
                boolean platformUnexpected=!platformExpected&&vectors.documentState(document.getId(),"PLATFORM")!=null;
                boolean obsolete=!teamExpected&&!platformExpected&&vectors.documentState(document.getId())!=null;
                if(teamStale||platformStale||platformUnexpected||obsolete){
                    index.reindex(document.getId());rebuilt++;
                }
            }catch(EmbeddingProviderException failure){
                failed++;
                if(failure.isQuotaExhausted()){
                    retry.stopForQuota(document.getId(),failure);
                    break;
                }
                retry.scheduleRetry(document.getId());
                log.warn("event=knowledge.reconcile.document_failed documentId={} errorCode={}",document.getId(),failure.providerCode());
            }catch(RuntimeException failure){
                failed++;retry.scheduleRetry(document.getId());
                log.warn("event=knowledge.reconcile.document_failed documentId={} errorType={}",document.getId(),failure.getClass().getSimpleName());
            }
        }
        for(Long indexed:vectors.indexedDocumentIds()) if(!activeIds.contains(indexed)){vectors.deleteDocument(indexed);deleted++;}
        log.info("event=knowledge.reconcile mode={} activeDocuments={} rebuilt={} deleted={} failed={} providerQuotaExhausted={}",force?"FORCED":"DIFFERENTIAL",active.size(),rebuilt,deleted,failed,retry.isProviderQuotaExhausted());
        return new ReconcileResult(active.size(),rebuilt,deleted,failed,retry.isProviderQuotaExhausted());
    }
    @Transactional(readOnly=true) public ReconcileStatus status(){Set<Long> active=documents.findByStatusOrderByIdAsc("ACTIVE").stream().map(document->document.getId()).collect(java.util.stream.Collectors.toSet());Set<Long> indexed=vectors.indexedDocumentIds();Set<Long> missing=new HashSet<>(active);missing.removeAll(indexed);Set<Long> orphaned=new HashSet<>(indexed);orphaned.removeAll(active);return new ReconcileStatus(active.size(),indexed.size(),missing.size(),orphaned.size());}
    public record ReconcileResult(int activeDocuments,int rebuilt,int deleted,int failed,boolean providerQuotaExhausted){}
    private String sha256(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception failure){throw new IllegalStateException(failure);}}
    public record ReconcileStatus(int activeDocuments,int indexedDocuments,int missingDocuments,int orphanedDocuments){}
}
