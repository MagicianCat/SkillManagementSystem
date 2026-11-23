# 外部资源导入的父 Wiki

普通文档导入只能选择当前活跃外部角色所关联资源生成的、已正式发布到全平台的 Wiki。候选包括 `DOCUMENT` 的每篇 Markdown 和 `ASDM-TOOL` 生成的 Wiki；依据 `wiki_document` 的外部来源字段与 `wiki_platform_snapshot` 判断，旧版本导入且仍有效的 Wiki 也可用。

网页端使用 `GET /api/v1/external-resources/roles/{roleId}/wiki-parent-options`。默认 `scope=PLATFORM`；团队范围使用 `scope=TEAM&teamId=<id>`。支持 `keyword`、`page` 和 `size`（最大 50），返回 `items`、`page`、`size`、`total`。无候选时可选择根目录。

飞书卡片使用同一候选服务，最多显示当前角色的 80 个父 Wiki；超出时仍可选择根目录，指定父节点需到后台搜索。已发送卡片的静态选项不会自动更新，提交和异步执行时会再次检查父 Wiki 资格。失效或范围不匹配时返回 `EXTERNAL_WIKI_PARENT_NOT_ELIGIBLE`，导入任务记录失败原因。

资格查询从 `external_role_resource` 开始，按外部来源连接 Wiki；本地 `EXPLAIN` 显示命中角色资源主键和 `idx_wiki_external_source`，没有从全站 Wiki 扫描候选。
