package com.company.skillplatform.wiki.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.skill.infrastructure.repository.SkillRepository;
import com.company.skillplatform.user.domain.UserStatus;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentEntity;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WikiDirectPublicationServiceTest {
    private static final String FINGERPRINT = "a".repeat(64);

    @Test void pendingReviewOnChildOrSkillBlocksEntireDirectPublication() {
        var fixture = new Fixture();
        when(fixture.store.conflictingReviewId(fixture.tree)).thenReturn(42L);

        assertThatThrownBy(() -> fixture.service.publish(10L, 8, FINGERPRINT, "发布", 9L, "request"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("WIKI_PUBLICATION_REVIEW_CONFLICT"));
        verifyNoInteractions(fixture.publication);
    }

    @Test void changedTreeFingerprintPreventsPublication() {
        var fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.publish(10L, 8, "b".repeat(64), "发布", 9L, "request"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("WIKI_PUBLICATION_STALE"));
        verifyNoInteractions(fixture.publication);
    }

    @Test void currentPreviewPublishesThroughSharedCore() {
        var fixture = new Fixture();
        when(fixture.publication.publish(eq(fixture.tree), eq("DIRECT"), isNull(), eq(fixture.actor),
                eq("发布"), eq("request"), any())).thenReturn(101L);

        var result = fixture.service.publish(10L, 8, FINGERPRINT, "发布", 9L, "request");

        assertThat(result.publicationId()).isEqualTo(101L);
        assertThat(result.indexStatus()).isEqualTo("PENDING");
        verify(fixture.store, times(2)).collect(fixture.root);
        verify(fixture.publication).publish(eq(fixture.tree), eq("DIRECT"), isNull(), eq(fixture.actor),
                eq("发布"), eq("request"), any());
    }

    @Test void repeatedRequestReturnsExistingPublicationWithoutWritingAgain() {
        var fixture = new Fixture();
        when(fixture.root.isPlatformVisible()).thenReturn(true);
        when(fixture.store.matchingDirectPublication(10L, 9L, FINGERPRINT, "发布"))
                .thenReturn(new WikiPromotionStore.DirectPublication(101L, 1, 0, 0, java.time.Instant.EPOCH));
        when(fixture.store.currentPublicationId(10L)).thenReturn(101L);
        when(fixture.store.publicationIndexStatus(101L)).thenReturn(new WikiPromotionStore.PublicationIndexStatus(10L, "READY"));

        var result = fixture.service.publish(10L, 8, FINGERPRINT, "发布", 9L, "retry");

        assertThat(result.publicationId()).isEqualTo(101L);
        assertThat(result.indexStatus()).isEqualTo("READY");
        verifyNoInteractions(fixture.publication);
    }

    private static class Fixture {
        final WikiDocumentRepository documents = mock(WikiDocumentRepository.class);
        final SkillRepository skills = mock(SkillRepository.class);
        final IamUserRepository users = mock(IamUserRepository.class);
        final WikiDocumentService wiki = mock(WikiDocumentService.class);
        final WikiPromotionStore store = mock(WikiPromotionStore.class);
        final WikiPublicationService publication = mock(WikiPublicationService.class);
        final IamUserEntity actor = mock(IamUserEntity.class);
        final WikiDocumentEntity root = mock(WikiDocumentEntity.class);
        final WikiPromotionStore.Tree tree = new WikiPromotionStore.Tree(10L, 7L,
                List.of(new WikiPromotionStore.Item(10L, null, "根", 11L, 1, "sha", List.of(), root)),
                List.of(), FINGERPRINT);
        final WikiDirectPublicationService service = new WikiDirectPublicationService(documents, skills, users, wiki, store, publication);

        Fixture() {
            when(users.findById(9L)).thenReturn(Optional.of(actor));
            when(actor.getStatus()).thenReturn(UserStatus.ACTIVE);
            when(root.getTeamId()).thenReturn(7L);
            when(root.getStatus()).thenReturn("ACTIVE");
            when(root.getVersionNo()).thenReturn(8);
            when(documents.findById(10L)).thenReturn(Optional.of(root));
            when(documents.findByIdForUpdate(10L)).thenReturn(Optional.of(root));
            when(wiki.canEdit(root, 9L)).thenReturn(true);
            when(store.collect(root)).thenReturn(tree);
            when(store.conflictingReviewId(tree)).thenReturn(null);
        }
    }
}
