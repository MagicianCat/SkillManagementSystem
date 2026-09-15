INSERT INTO skill_category(time_created,time_updated,category_key,category_name,parent_id,sort_order,status,version_no)
VALUES
 (UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'development','开发工具',NULL,10,'ACTIVE',0),
 (UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'productivity','效率工具',NULL,20,'ACTIVE',0),
 (UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'data','数据处理',NULL,30,'ACTIVE',0),
 (UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'content','内容创作',NULL,40,'ACTIVE',0),
 (UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'operations','运维管理',NULL,50,'ACTIVE',0),
 (UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'other','其他',NULL,100,'ACTIVE',0)
ON DUPLICATE KEY UPDATE
  time_updated=VALUES(time_updated),
  category_name=VALUES(category_name),
  sort_order=VALUES(sort_order),
  status='ACTIVE';
