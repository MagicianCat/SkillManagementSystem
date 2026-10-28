# 外部角色资源接入（V1.1）

启动时设置 `INTEGRATION_EXTERNAL_API_KEYS_JSON`，值为来源平台 ID 到 API Key 的 JSON 映射，例如：

```sh
export INTEGRATION_EXTERNAL_API_KEYS_JSON='{"platform-a":"请替换为独立生成的高强度密钥"}'
```

生产环境必须只在 HTTPS 后开放 `POST /api/integration/v1/resources/push`，不要把密钥写入 Git、前端或日志。一个密钥只对应一个 `sourceSystem`。可通过 `INTEGRATION_EXTERNAL_MAX_FILE_BYTES` 调整单 ZIP 大小上限（默认 50 MiB），同时检查 Spring `spring.servlet.multipart` 的文件/请求上限；单批最多 20 个资源。默认每来源每实例每分钟最多 60 次请求，超限返回 HTTP 429 / 业务码 4290；多实例部署时还需在入口网关配置全局限流。

本实现遵循较新的《外部角色资源接入详细设计 V1.1》：`SKILL` 和 `DOCUMENT` 均推送原始 ZIP。早期《外部资源推送 API 接口文档》中的 DOCUMENT PDF 示例不再适用，需要联调时同步告知对接方。

外部请求仅归档 ZIP 和元数据，不解压、不导入 Skill/Wiki。管理员在“外部资源接入”中按角色发起异步批量任务：

- `POST /api/v1/external-resources/roles/{id}/skills:import-all`：请求体为 `{ "resourceIds": [1, 2] }`，仅导入管理员选中的、属于当前角色且有当前版本的 Skill。使用当前版本的 ZIP，优先从 `SKILL.md` 的 `name` 获取 Skill Key；无合法 Key 的资源逐条失败。已有 Key 更新草稿和元数据，新 Key 需要推送有效 `subStageKey`。成功项自动提交 Skill 审核。
- `POST /api/v1/external-resources/roles/{id}/wikis:import-all`：请求体为 `{ "teamId": null, "parentWikiId": null, "linkRoleSkills": true, "selections": [{ "resourceId": 7, "paths": ["guide.md"] }] }`。`teamId` 为空表示平台级 Wiki，填写团队 ID 表示团队 Wiki。先选择一个或多个文档 ZIP，再逐个选择 ZIP 中的 Markdown；每个 ZIP 至少选一篇。`parentWikiId` 可为空；启用关联时，关联该角色当前版本中已成功导入平台的 Skill。每篇 Markdown 按 `sourceSystem + resourceId + ZIP 内路径 + 目标团队` 查找活动 Wiki；存在则更新，不存在或原 Wiki 已下架则新建。新建或仅团队可见的成功项自动提交 Wiki 审核；全平台可见的成功项直接覆盖。
- 两个接口均返回 HTTP 202 和 `jobId`。`GET /api/v1/external-import-jobs/{jobId}` 返回任务状态、逐条结果和失败原因。部分成功不会回滚其他条目；任务结束后向管理员发送站内信，通知可直达结果页。旧的同步 `documents:import` 接口不再开放。

已在全平台可见的 Wiki 被批量更新时直接覆盖当前内容，保持全平台可见。

团队 Wiki 可指定同团队的活动父 Wiki。下架父 Wiki 会递归下架全部子 Wiki，并触发每篇文档的向量删除事件；归档满 180 天后由每日清理任务永久删除。历史上经浏览器手动导入、但未记录来源 `resourceId` 的 Wiki 无法安全地按名称自动认领；首次批量导入会为其创建带来源标识的新 Wiki。
