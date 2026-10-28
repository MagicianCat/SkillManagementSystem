UPDATE wiki_document d
JOIN (
    SELECT i.target_id, r.source_system, r.external_resource_id, i.entry_path,
           ROW_NUMBER() OVER (
               PARTITION BY r.source_system, r.external_resource_id, i.entry_path, wd.team_id
               ORDER BY i.id DESC
           ) AS source_rank
    FROM external_import_record i
    JOIN external_resource_version v ON v.id = i.resource_version_id
    JOIN external_resource r ON r.id = v.resource_id
    JOIN wiki_document wd ON wd.id = i.target_id AND wd.status = 'ACTIVE'
    WHERE i.target_type = 'WIKI' AND i.result = 'SUCCESS' AND i.entry_path IS NOT NULL
) source ON source.target_id = d.id AND source.source_rank = 1
SET d.source_system = source.source_system,
    d.source_resource_id = source.external_resource_id,
    d.source_entry_path = source.entry_path
WHERE d.status = 'ACTIVE' AND d.source_resource_id IS NULL;
