package com.company.skillplatform.project.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "document_agent_session_wiki_context")
public class DocumentAgentSessionWikiContextEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "session_id", nullable = false) private DocumentAgentSessionEntity session;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "wiki_document_id", nullable = false) private WikiDocumentEntity wikiDocument;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "added_by", nullable = false) private IamUserEntity addedBy;
    @Column(name = "ordinal_no", nullable = false) private int ordinalNo;

    protected DocumentAgentSessionWikiContextEntity() {}
    public DocumentAgentSessionWikiContextEntity(DocumentAgentSessionEntity session, WikiDocumentEntity wikiDocument, IamUserEntity addedBy, int ordinalNo) {
        this.session = session; this.wikiDocument = wikiDocument; this.addedBy = addedBy; this.ordinalNo = ordinalNo;
    }
    public DocumentAgentSessionEntity getSession() { return session; }
    public WikiDocumentEntity getWikiDocument() { return wikiDocument; }
    public IamUserEntity getAddedBy() { return addedBy; }
    public int getOrdinalNo() { return ordinalNo; }
}
