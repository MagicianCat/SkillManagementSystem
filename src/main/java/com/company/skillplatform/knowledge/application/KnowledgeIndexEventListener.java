package com.company.skillplatform.knowledge.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class KnowledgeIndexEventListener {
    private static final Logger log=LoggerFactory.getLogger(KnowledgeIndexEventListener.class);private final KnowledgeIndexService index;
    public KnowledgeIndexEventListener(KnowledgeIndexService index){this.index=index;}
    @Async("knowledgeIndexExecutor") @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void handle(WikiKnowledgeChangedEvent event){try{if(event.operation()==WikiKnowledgeChangedEvent.Operation.DELETE)index.delete(event.documentId());else index.reindex(event.documentId());}catch(RuntimeException failure){log.warn("event=knowledge.index.event_failed documentId={} operation={} errorType={}",event.documentId(),event.operation(),failure.getClass().getSimpleName());}}
}
