CREATE TABLE wiki_document (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    title VARCHAR(255) NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    team_id BIGINT NULL,
    platform_visible BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    current_revision_id BIGINT NULL,
    version_no INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_wiki_document_team FOREIGN KEY (team_id) REFERENCES org_team(id) ON DELETE RESTRICT,
    CONSTRAINT fk_wiki_document_created_by FOREIGN KEY (created_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_wiki_document_updated_by FOREIGN KEY (updated_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_wiki_document_scope (team_id, platform_visible, status),
    INDEX idx_wiki_document_type (document_type, status)
);

CREATE TABLE wiki_document_revision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    document_id BIGINT NOT NULL,
    revision_no INT NOT NULL,
    markdown_content MEDIUMTEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    created_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_wiki_document_revision UNIQUE (document_id, revision_no),
    CONSTRAINT fk_wiki_revision_document FOREIGN KEY (document_id) REFERENCES wiki_document(id) ON DELETE CASCADE,
    CONSTRAINT fk_wiki_revision_created_by FOREIGN KEY (created_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_wiki_revision_document (document_id, revision_no)
);

ALTER TABLE wiki_document
    ADD CONSTRAINT fk_wiki_document_current_revision FOREIGN KEY (current_revision_id) REFERENCES wiki_document_revision(id) ON DELETE RESTRICT;

CREATE TABLE wiki_document_skill (
    document_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL DEFAULT 'RELATED',
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (document_id, skill_id),
    CONSTRAINT fk_wiki_document_skill_document FOREIGN KEY (document_id) REFERENCES wiki_document(id) ON DELETE CASCADE,
    CONSTRAINT fk_wiki_document_skill_skill FOREIGN KEY (skill_id) REFERENCES skill(id) ON DELETE RESTRICT,
    INDEX idx_wiki_document_skill_skill (skill_id, document_id)
);

CREATE TABLE skill_review_wiki_revision (
    review_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    revision_id BIGINT NOT NULL,
    PRIMARY KEY (review_id, document_id),
    CONSTRAINT fk_review_wiki_review FOREIGN KEY (review_id) REFERENCES skill_review(id) ON DELETE CASCADE,
    CONSTRAINT fk_review_wiki_document FOREIGN KEY (document_id) REFERENCES wiki_document(id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_wiki_revision FOREIGN KEY (revision_id) REFERENCES wiki_document_revision(id) ON DELETE RESTRICT
);
