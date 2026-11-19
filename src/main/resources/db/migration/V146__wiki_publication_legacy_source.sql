UPDATE wiki_platform_snapshot
SET publication_source='LEGACY_UNKNOWN'
WHERE approved_review_id IS NULL
  AND source_team_id IS NOT NULL
  AND publication_id IS NULL
  AND publication_source='NATIVE_PLATFORM';
