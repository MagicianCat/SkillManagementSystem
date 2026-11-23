# 外部角色资源飞书卡片

外部资源推送接口仍只接收 ZIP 和元数据。角色或资源有实际变化时，后端创建卡片事件；后台 Worker 从事件快照构建卡片并逐一发送给活跃 `ADMIN` 用户。重复推送且无变化时不建事件。停用角色只发送状态卡片。

## 本地灰度配置

```sh
export INTEGRATION_EXTERNAL_ROLE_CARD_ENABLED=true
export INTEGRATION_EXTERNAL_ROLE_CARD_SOURCE_SYSTEMS=external-platform-a
export INTEGRATION_EXTERNAL_ROLE_CARD_POLL_MS=5000
export INTEGRATION_EXTERNAL_ROLE_CARD_MAX_WIKI_OPTIONS=80
export INTEGRATION_EXTERNAL_ROLE_CARD_EXPIRY_DAYS=14
```

同时需要 `FEISHU_EVENT_ENABLED=true`、飞书应用凭据和 `card.action.trigger` 订阅。未启用长连接时，Worker 暂停发送并保留待发事件。默认 `INTEGRATION_EXTERNAL_ROLE_CARD_ENABLED=false`，可按来源平台逐步启用。

## 操作与追踪

- `SKILL_PUBLISH_ALL`：校验活跃超级管理员和直发权限，发布事件快照里的全部当前 Skill。
- `WIKI_IMPORT`：从事件内的短选项值还原跨 ZIP Markdown，复用 Wiki 批量导入；知识分类不允许 `FDE_WIKI`，关联 Skill 时要求全部当前 Skill 已全平台发布。
- `ASDM_PUBLISH`：每个当前版本单独入队。点击时记录 Wiki 版本号，Worker 按该版本号执行乐观锁校验。

卡片操作写入 `external_role_card_action`，异步任务写入 `external_import_job`。后台可用 `GET /api/v1/external-role-card-events` 查看事件，用 `POST /api/v1/external-role-card-events/{id}:retry` 重试未过期的失败或部分投递事件。操作结果可通过 `GET /api/v1/external-import-jobs/{jobId}` 查看。

本地联调创建的事件和任务可在数据库中按 `external_role_card_event.id`、`external_role_card_action.job_id` 关联。飞书卡片回调只校验并入队，最终结果以异步任务和站内信为准。
