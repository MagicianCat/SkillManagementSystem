-- Direct platform Wikis created after V132 need a public snapshot for vector indexing and user visibility.
INSERT INTO wiki_platform_snapshot
  (document_id, approved_revision_id, approved_title, approved_parent_id,
   approved_review_id, source_team_id, published_at, published_by)
SELECT d.id, d.current_revision_id, d.title, d.parent_id,
       NULL, NULL, CURRENT_TIMESTAMP(6), d.updated_by
FROM wiki_document d
LEFT JOIN wiki_platform_snapshot p ON p.document_id = d.id
WHERE d.status = 'ACTIVE' AND d.platform_visible = 1 AND d.team_id IS NULL
  AND d.current_revision_id IS NOT NULL AND p.document_id IS NULL;
