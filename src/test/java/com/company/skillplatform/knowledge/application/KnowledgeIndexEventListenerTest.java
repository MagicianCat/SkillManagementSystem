package com.company.skillplatform.knowledge.application;

import static org.mockito.Mockito.*;

import com.company.skillplatform.notification.application.NotificationService;
import com.company.skillplatform.knowledge.domain.EmbeddingProviderException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class KnowledgeIndexEventListenerTest {
    @Test void retriesFailedWikiIndexAfterTransientError() {
        var index=mock(KnowledgeIndexService.class);
        doThrow(new IllegalStateException("embedding unavailable")).doNothing().when(index).reindex(12L);
        var notifications=mock(NotificationService.class);
        var listener=new KnowledgeIndexEventListener(index,notifications,Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.UTC));

        listener.handle(new WikiKnowledgeChangedEvent(12L,WikiKnowledgeChangedEvent.Operation.UPSERT));
        listener.retryFailed();

        verify(index).reindex(12L);
        verifyNoInteractions(notifications);
    }

    @Test void archiveEventUsesCurrentDocumentStateToRemoveIndex() {
        var index=mock(KnowledgeIndexService.class);var listener=new KnowledgeIndexEventListener(index,mock(NotificationService.class));
        listener.handle(new WikiKnowledgeChangedEvent(12L,WikiKnowledgeChangedEvent.Operation.DELETE));
        verify(index).reindex(12L);
    }

    @Test void stopsAfterTenRetriesAndNotifiesOnce() {
        var index=mock(KnowledgeIndexService.class);
        var notifications=mock(NotificationService.class);
        doThrow(new IllegalStateException("embedding unavailable")).when(index).reindex(12L);
        var clock=new MutableClock();
        var listener=new KnowledgeIndexEventListener(index,notifications,clock);
        listener.handle(new WikiKnowledgeChangedEvent(12L,WikiKnowledgeChangedEvent.Operation.UPSERT));
        for(int i=0;i<10;i++){clock.advanceSeconds(3600);listener.retryFailed();}
        clock.advanceSeconds(3600);listener.retryFailed();listener.scheduleRetry(12L);
        verify(index,times(11)).reindex(12L);
        verify(notifications).wikiIndexFailed(12L, "IllegalStateException");
        org.junit.jupiter.api.Assertions.assertTrue(listener.isExhausted(12L));
        listener.handle(new WikiKnowledgeChangedEvent(12L,WikiKnowledgeChangedEvent.Operation.UPSERT));
        org.junit.jupiter.api.Assertions.assertFalse(listener.isExhausted(12L));
    }

    @Test void quotaExhaustionStopsRetriesImmediatelyAndSendsSpecificNotification() {
        var index=mock(KnowledgeIndexService.class);
        var notifications=mock(NotificationService.class);
        var quota=new EmbeddingProviderException(400,"provider_quota_exceeded",null);
        doThrow(quota).when(index).reindex(12L);
        var clock=new MutableClock();
        var listener=new KnowledgeIndexEventListener(index,notifications,clock);

        listener.handle(new WikiKnowledgeChangedEvent(12L,WikiKnowledgeChangedEvent.Operation.UPSERT));
        listener.scheduleRetry(12L);
        clock.advanceSeconds(3600);
        listener.retryFailed();
        listener.handle(new WikiKnowledgeChangedEvent(13L,WikiKnowledgeChangedEvent.Operation.UPSERT));

        verify(index,times(1)).reindex(12L);
        verify(index,never()).reindex(13L);
        verify(notifications,times(1)).wikiIndexQuotaExhausted(12L,"provider_quota_exceeded");
        org.junit.jupiter.api.Assertions.assertTrue(listener.isProviderQuotaExhausted());
        org.junit.jupiter.api.Assertions.assertFalse(listener.isScheduled(12L));

        listener.resetForManualReconcile();
        org.junit.jupiter.api.Assertions.assertFalse(listener.isProviderQuotaExhausted());
        listener.handle(new WikiKnowledgeChangedEvent(13L,WikiKnowledgeChangedEvent.Operation.UPSERT));
        verify(index).reindex(13L);
    }

    private static final class MutableClock extends Clock {
        private Instant now=Instant.parse("2026-01-01T00:00:00Z");
        void advanceSeconds(long seconds){now=now.plusSeconds(seconds);}
        public ZoneOffset getZone(){return ZoneOffset.UTC;}
        public Clock withZone(java.time.ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
}
