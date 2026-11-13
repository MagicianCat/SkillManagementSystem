-- 第十个一级研发阶段“其他（OTHER）”。V37 曾清理过旧版 other 根节点，这里重新以正式 Key 引入，
-- 不迁移任何历史 Skill 的分类归属；新建 Skill 必须落在叶子 other.general。

INSERT INTO skill_category
(category_key, category_name, parent_id, sort_order, status, version_no, time_created, time_updated)
VALUES ('other', '其他', NULL, 1000, 'ACTIVE', 0, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE
  category_name=VALUES(category_name),
  parent_id=NULL,
  sort_order=VALUES(sort_order),
  status='ACTIVE',
  time_updated=NOW(6);

INSERT INTO skill_category
(category_key, category_name, parent_id, sort_order, status, version_no, time_created, time_updated)
SELECT 'other.general', '未分类 / 综合其他', p.id, 1001, 'ACTIVE', 0, NOW(6), NOW(6)
FROM skill_category p
WHERE p.category_key='other'
ON DUPLICATE KEY UPDATE
  category_name=VALUES(category_name),
  parent_id=VALUES(parent_id),
  sort_order=VALUES(sort_order),
  status='ACTIVE',
  time_updated=NOW(6);