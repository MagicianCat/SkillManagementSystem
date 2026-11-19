INSERT INTO iam_permission(permission_key, permission_name, description, time_created, time_updated)
VALUES ('wiki:publish:direct', 'Wiki 免审核全平台发布', '对本人有权管理的全部知识分类 Wiki 免审核发布至全平台', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name), description=VALUES(description);

INSERT INTO iam_role(role_key, role_name, description, status, version_no, time_created, time_updated)
VALUES ('WIKI_DIRECT_PUBLISHER', 'Wiki 全平台直推员', '由超级管理员单独授予的 Wiki 免审核发布角色', 'ACTIVE', 0, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE role_name=VALUES(role_name), description=VALUES(description);

INSERT IGNORE INTO iam_role_permission(role_id, permission_id, created_by, time_created, time_updated)
SELECT r.id, p.id, MIN(admin_user.id), NOW(3), NOW(3)
FROM iam_role r JOIN iam_permission p ON p.permission_key='wiki:publish:direct'
JOIN iam_user_role admin_link ON admin_link.role_id=(SELECT id FROM iam_role WHERE role_key='ADMIN')
JOIN iam_user admin_user ON admin_user.id=admin_link.user_id AND admin_user.status='ACTIVE'
WHERE r.role_key IN ('WIKI_DIRECT_PUBLISHER','ADMIN')
GROUP BY r.id, p.id;

CREATE TABLE wiki_publication (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  source VARCHAR(24) NOT NULL,
  root_document_id BIGINT NOT NULL,
  source_team_id BIGINT NULL,
  review_id BIGINT NULL,
  actor_id BIGINT NOT NULL,
  tree_fingerprint CHAR(64) NULL,
  wiki_count INT NOT NULL,
  skill_count INT NOT NULL,
  promoted_team_skill_count INT NOT NULL DEFAULT 0,
  comment VARCHAR(2000) NULL,
  published_at DATETIME(6) NOT NULL,
  INDEX idx_wiki_publication_root (root_document_id, published_at),
  INDEX idx_wiki_publication_review (review_id)
);

ALTER TABLE wiki_platform_snapshot
  ADD COLUMN publication_id BIGINT NULL,
  ADD COLUMN publication_source VARCHAR(24) NULL;

UPDATE wiki_platform_snapshot
SET publication_source=CASE WHEN approved_review_id IS NOT NULL THEN 'REVIEW' ELSE 'NATIVE_PLATFORM' END;
