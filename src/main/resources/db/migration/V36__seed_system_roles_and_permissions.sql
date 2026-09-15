INSERT INTO iam_permission (permission_key, permission_name, description, time_created, time_updated)
VALUES
 ('skill:browse','浏览 Skill','浏览可见 Skill',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
 ('skill:download','下载 Skill','下载可见 Skill',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
 ('skill:upload','上传 Skill','创建 Skill 和版本',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
 ('skill:edit','维护 Skill','编辑负责的 Skill',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
 ('skill:review','审核 Skill','审核 Skill 发布申请',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
 ('skill:publish','发布 Skill','发布已通过审核的 Skill',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
 ('skill:offline','下线 Skill','下线已发布 Skill',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
 ('admin:identity','身份权限管理','管理用户、角色和权限',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
 ('admin:audit','审计查询','查询平台审计信息',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name), description=VALUES(description), time_updated=CURRENT_TIMESTAMP(6);

INSERT INTO iam_role (role_key, role_name, description, status, version_no, time_created, time_updated)
VALUES
 ('ADMIN','超级管理员','平台全部管理权限','ACTIVE',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
 ('MAINTAINER','Skill 维护员','上传并维护 Skill','ACTIVE',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
 ('REVIEWER','审核员','审核 Skill','ACTIVE',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
 ('PUBLISHER','发布员','发布及下线 Skill','ACTIVE',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
 ('CONSUMER','普通用户','浏览及下载 Skill','ACTIVE',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE role_name=VALUES(role_name), description=VALUES(description), time_updated=CURRENT_TIMESTAMP(6);

INSERT IGNORE INTO iam_role_permission (role_id, permission_id, created_by, time_created, time_updated)
SELECT r.id, p.id, NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM iam_role r CROSS JOIN iam_permission p
WHERE (r.role_key='ADMIN')
   OR (r.role_key='MAINTAINER' AND p.permission_key IN ('skill:browse','skill:download','skill:upload','skill:edit'))
   OR (r.role_key='REVIEWER' AND p.permission_key IN ('skill:browse','skill:review'))
   OR (r.role_key='PUBLISHER' AND p.permission_key IN ('skill:browse','skill:publish','skill:offline'))
   OR (r.role_key='CONSUMER' AND p.permission_key IN ('skill:browse','skill:download'));
