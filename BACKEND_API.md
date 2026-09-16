# Skill Management System 后端 API 对接文档

> 维护基准：当前工程代码。前端联调时以本文档和实际响应为准；接口变更时应同步更新本文档。

## 1. 通用约定

- Base URL：`/api/v1`
- 默认数据格式：`application/json`
- 文件上传：`multipart/form-data`
- 鉴权：除登录、会话恢复、刷新 Token 和健康检查外，请求需携带 `Authorization: Bearer <accessToken>`；浏览器请求通过 HttpOnly Cookie 恢复会话。
- 分页参数：`page` 从 0 开始，`size` 为每页数量，支持 Spring Data `sort=field,asc|desc`。
- 乐观锁：修改请求中的 `versionNo` 必须使用最近一次查询返回的值；过期时返回 `409 OPTIMISTIC_LOCK_CONFLICT`。
- 时间字段使用 ISO-8601 UTC 字符串。

分页响应：

```json
{"items":[],"page":0,"size":20,"totalElements":0,"totalPages":0}
```

错误响应：

```json
{
  "code": "VALIDATION_FAILED",
  "message": "Request validation failed",
  "requestId": "...",
  "details": {"field": "must not be blank"},
  "timestamp": "2026-09-02T10:00:00Z"
}
```

常见状态码：`200` 成功、`202` 已接受、`400` 参数错误、`401` 未认证、`403` 无权限/非 Owner、`404` 不存在、`409` 状态或版本冲突、`422` 依赖/兼容性无法解析。

## 2. 权限与内置角色

| 权限 | 含义 |
|---|---|
| `skill:browse` | 浏览 Skill、版本、文件索引、依赖、兼容性、制品 |
| `skill:download` | 下载 Skill Artifact 或 Bundle |
| `skill:upload` | 创建 Skill、上传 ZIP |
| `skill:edit` | 编辑本人拥有的 Skill、草稿、依赖和兼容性，提交审核 |
| `skill:review` | 查询和处理审核任务 |
| `skill:publish` | 发布、废弃及查询构建任务 |
| `skill:offline` | 下架影响分析和下架 |
| `admin:identity` | 用户、角色、权限管理；同时可覆盖 Skill Owner 编辑限制 |
| `admin:audit` | 查询完整审计日志 |

内置角色：`ADMIN`、`MAINTAINER`、`REVIEWER`、`PUBLISHER`、`CONSUMER`。

## 3. 认证与当前用户

平台仅支持飞书 OAuth 单点登录；用户由飞书登录或通讯录同步创建，不提供本地口令登录和手工创建用户接口。

### POST `/auth/refresh`

匿名可调用，使用 Refresh Token 轮换新 Token。

```json
{"refreshToken":"..."}
```

响应结构与登录一致；旧 Refresh Token 随即失效。

该接口继续供 CodeBuddy 等非浏览器客户端使用。浏览器不保存或提交 Refresh Token。

### GET `/auth/session`

匿名接口，从 HttpOnly Cookie `sms_browser_session` 恢复浏览器会话并返回：

```json
{"accessToken":"...","expiresIn":1800,"user":{"id":1,"username":"...","displayName":"...","roles":[],"permissions":[]}}
```

会话有效期默认 7 天，可通过 `AUTH_BROWSER_COOKIE_MAX_AGE_SECONDS` 配置；会话失效返回 `401`。

### POST `/auth/logout`

浏览器请求撤销 HttpOnly Cookie 对应的浏览器会话并清除 Cookie。携带 `refreshToken` 时仍兼容撤销 CodeBuddy 等客户端的 Refresh Token。

```json
{"refreshToken":"..."}
```

成功返回空响应。

### GET `/auth/oauth/feishu/authorize`

匿名接口。参数 `redirectPath` 为登录成功后的站内路径，默认 `/skills`。返回飞书授权地址，前端应使用浏览器跳转。

### POST `/auth/oauth/feishu/callback`

匿名接口。请求体为 `{"code":"...","state":"..."}`，由服务端完成飞书授权码换 Token 和用户信息查询，成功后设置 HttpOnly 浏览器会话 Cookie，响应不包含 Refresh Token。state 有效期默认 5 分钟且只能使用一次。

授权成功后，服务端会加密保存当前用户的 Feishu `user_access_token` 和 `refresh_token`，用于按当前用户权限读取飞书云文档。需要申请 `offline_access`、`drive:drive.search:readonly`、`docx:document:readonly`、`wiki:wiki:readonly`、`docs:doc:readonly`；未完成授权的用户仍可使用平台 Skill 和团队 Wiki。

### IDE 浏览器配对登录

`POST /auth/ide/authorizations` 为匿名接口，请求：

```json
{"clientName":"CodeBuddy IDE","codeChallenge":"<base64url-sha256>","codeChallengeMethod":"S256"}
```

返回 `deviceCode`、短格式 `userCode`、`verificationUri`、已附带短码的 `verificationUriComplete`、`expiresIn`（固定 300 秒）和 `pollInterval`（固定 5 秒）。服务端只保存 device/user code 的 SHA-256 哈希及 PKCE challenge，不保存原始 code。

用户在浏览器完成飞书登录后调用 `POST /auth/ide/authorizations/approve`，请求 `{"userCode":"ABCD-EFGH"}`。该接口要求登录态，并把当前用户绑定到配对事务。

浏览器登录页通过已认证的 `GET /auth/ide/authorizations?userCode=ABCD-EFGH` 查询待确认设备，返回规范化的 `userCode`、服务端保存的 `clientName`、`status` 和 `expiresAt`。响应不包含 device code 或 PKCE challenge。

IDE 使用 `POST /auth/ide/token` 匿名轮询：

```json
{"deviceCode":"...","codeVerifier":"..."}
```

待批准返回 `202 {"status":"authorization_pending"}`；批准后返回一次现有 `TokenResult`，随后相同 device code 返回 `409 IDE_AUTHORIZATION_REPLAYED`。过期返回 `410 IDE_AUTHORIZATION_EXPIRED`；拒绝返回 `403 IDE_AUTHORIZATION_DENIED`；PKCE 不匹配返回 `400 IDE_AUTHORIZATION_PKCE_MISMATCH`。仅支持 `S256`。

本地默认限流：同一 IP 每分钟最多创建 10 次、查询/批准 10 次；轮询绑定 IP 与 device code，最短间隔 5 秒且每分钟最多 30 次。超限返回 429；轮询过快返回 `IDE_AUTHORIZATION_SLOW_DOWN`。限流状态最多保留 10000 项。定时清理已过期活动记录及保留超过 1 天的 `CONSUMED/DENIED` 记录。

### 飞书机器人 Agent

启用 `FEISHU_EVENT_ENABLED=true` 后，服务端通过飞书 SDK 长连接订阅 `im.message.receive_v1`。机器人私聊会复用该用户当前 Agent 会话；群聊按发送者和话题线程隔离，并且仅检索平台公开 Skill。消息以 `message_id` 幂等落库，Agent 运行完成后由服务端通过 tenant token 回复机器人。

机器人新增配置：`FEISHU_BOT_ENABLED`、`FEISHU_BOT_WEB_BASE_URL`、`FEISHU_BOT_TASK_INTERVAL_MS`、`FEISHU_BOT_MAX_RETRIES`。未设置 `FEISHU_BOT_ENABLED` 时默认跟随 `FEISHU_EVENT_ENABLED`。机器人消息卡片会通过 `multi_url` 为 PC、iOS、Android 分发网页或移动 H5 Skill 详情页。

旧版 `/auth/feishu/authorize` 和 `/auth/feishu/callback` 保留作为兼容入口。

### GET `/users/me`

权限：已登录。返回当前用户的 `id`、`username`、`displayName`、`roles`、`permissions`。

### GET `/auth/feishu/document-access`

权限：已登录。返回当前用户的飞书云文档授权状态：`NOT_AVAILABLE`、`AUTHORIZED` 或 `REAUTH_REQUIRED`。DSH 仅在状态为 `AUTHORIZED` 且能取得有效用户令牌时挂载飞书文档 MCP。

### GET `/users/candidates`

权限：`skill:edit` 或 `admin:identity`。供 PRIMARY Owner 选择维护者。查询参数：`keyword`、`page`、`size`、`sort`；只返回 `ACTIVE` 用户，`keyword` 模糊匹配用户名和展示名，单页最多 50 条。响应为 `PageResponse`，元素字段：`id`、`username`、`displayName`、`status`。

## 4. 用户、角色与权限管理

本节接口统一要求 `admin:identity`。

### GET `/admin/users`

查询参数：`page`、`size`、`sort`。返回 `PageResponse<UserView>`。

`UserView`：`id`、`username`、`displayName`、`email`、`status`、`versionNo`。

### POST `/admin/users`

```json
{"username":"user01","password":"******","displayName":"User 01","email":"user01@example.com"}
```

`username/password/displayName` 必填，`email` 可空但必须符合邮箱格式。返回 `UserView`。

### PATCH `/admin/users/{id}/status`

```json
{"status":"ACTIVE","versionNo":0}
```

`status`：`ACTIVE` 或 `DISABLED`。返回更新后的 `UserView`。

### GET `/admin/roles`

返回角色数组。字段：`id`、`roleKey`、`roleName`、`description`、`versionNo`。

### GET `/admin/permissions`

返回所有可分配权限。字段：`id`、`permissionKey`、`permissionName`、`description`。

### GET `/admin/users/{id}/roles`

返回用户当前角色 ID：`{"ids":[1,2]}`。

### GET `/admin/roles/{id}/permissions`

返回角色当前权限 ID：`{"ids":[1,2,3]}`。

### POST `/admin/roles`

```json
{"roleKey":"CUSTOM_ROLE","roleName":"Custom Role","description":"..."}
```

### PUT `/admin/users/{id}/roles`

全量替换用户角色：

```json
{"ids":[2,5],"versionNo":0}
```

成功返回空响应。

### PUT `/admin/roles/{id}/permissions`

全量替换角色权限：

```json
{"ids":[1,2,3],"versionNo":0}
```

成功返回空响应。

## 5. Skill 目录与草稿

### GET `/skills`

权限：`skill:browse`。

查询参数：`keyword`、`categoryId`、`tag`、`platform`、`osType`、`lifecycleStatus`、`developmentStage`、`status`、`ownerId`、`page`、`size`、`sort`。

`platform`、`osType` 会按最新已发布版本的兼容性声明过滤，枚举值大小写不敏感。

`developmentStage` 由 Skill 所属分类的根节点派生，可传逗号分隔值筛选并行阶段。取值：`REQUIREMENT`、`PRODUCT`、`ARCHITECTURE_DESIGN`、`UI_DESIGN`、`BACKEND_CODING`、`FRONTEND_CODING`、`SECURITY_REVIEW`、`TESTING`、`DEPLOYMENT`。

`status` 按 Skill 有效状态过滤，大小写不敏感，取值：`ACTIVE`（有效）、`ARCHIVED`（已归档）。非法值返回 `400 INVALID_SKILL_STATUS`。Skill 市场固定传入 `status=ACTIVE`，不展示已归档 Skill。

排序字段使用实体属性名，如 `sort=timeUpdated,desc`（不要使用 `updatedAt` 等不存在的属性，否则会返回 500）。

返回 `PageResponse<SkillView>`。`SkillView` 字段：

```json
{"id":1,"skillKey":"springboot-tdd","displayName":"Spring Boot TDD","description":"...","categoryId":510,"category":{"id":510,"key":"backend.api","name":"API 与连接器开发","parentId":5,"sortOrder":510,"stage":"BACKEND_CODING","selectable":true},"tags":[],"owners":[],"status":"ACTIVE","developmentStage":"BACKEND_CODING","versionNo":0,"sourceUrl":null}
```

### POST `/skills`

权限：`skill:upload`。

```json
{"skillKey":"springboot-tdd","displayName":"Spring Boot TDD","description":"...","categoryId":510,"ownerUserIds":[],"tagIds":[21,22],"sourceUrl":"https://github.com/example/repository"}
```

- `skillKey` 只能使用小写字母、数字和单个连字符分段。
- 非管理员只能把自己设为 Owner；`ownerUserIds` 为空时自动使用当前用户。
- 管理员可以指定其他 Owner。
- `categoryId` 必填且必须指向启用的叶子分类；开发阶段由分类树自动派生。
- `sourceUrl` 可选，必须是长度不超过 2048 的绝对 HTTP/HTTPS 地址。

返回 `SkillView`。

### GET `/skills/{skillKey}`

权限：`skill:browse`。返回 `SkillView`。

### GET `/categories`

权限：`skill:browse`。返回启用的分类列表，字段：`id`、`key`、`name`、`parentId`、`sortOrder`、`stage`、`selectable`。

### GET `/tags?keyword={keyword}`

权限：`skill:browse`。查询标签，`keyword` 可选，最多返回 50 条。字段：`id`、`key`、`name`。

### PATCH `/skills/{skillKey}`

权限：`skill:edit`，且要求 Skill Owner 或管理员。

```json
{"displayName":"New name","description":"New description","categoryId":803,"tagIds":[21],"versionNo":0,"sourceUrl":"https://github.com/example/repository"}
```

- `categoryId` 必填且必须指向启用的叶子分类。
- `sourceUrl` 可选，传空字符串或 `null` 可清空来源网址。
- 来源网址仅对该 Skill 的 Owner、团队管理员、平台管理员和超级管理员返回；普通用户和 Agent 不会获得该字段内容。

返回更新后的 `SkillView`。

### PUT `/skills/{skillKey}/owners`

权限：`skill:edit` 或 `admin:identity`。非管理员必须是当前 PRIMARY Owner。

```json
{"ownerUserIds":[2,5],"primaryOwnerUserId":2,"versionNo":3}
```

至少保留一个有效用户；PRIMARY Owner 必须是列表成员。成功返回更新后的 `SkillView`。错误码包括 `SKILL_OWNER_REQUIRED`、`PRIMARY_OWNER_INVALID`、`OWNER_USER_INVALID`、`PRIMARY_OWNER_REQUIRED`。

### POST `/skills/{skillKey}:archive`

权限：当前 PRIMARY Owner 或管理员。请求：

```json
{"versionNo":3,"reason":"No longer maintained"}
```

存在活动草稿时返回 `409 ACTIVE_DRAFT_EXISTS`，需先取消草稿。归档后历史版本仍可浏览和下载，但不能打开或上传新草稿。成功返回状态为 `ARCHIVED` 的 `SkillView`，并写入审计。

### POST `/skills/{skillKey}:unarchive`

权限及请求体同归档接口。恢复后 Skill 状态为 `ACTIVE`，可重新维护草稿，并写入审计。

### POST `/skills/{skillKey}:promote-platform`

权限：`admin:identity` 或 `skill:review`。将团队级 Skill 提升为平台级，清除团队归属；仅支持管理员或审核人员操作，并要求携带当前 `versionNo`。

```json
{"versionNo":3,"reason":"best-practice-skills 平台级导入"}
```

已是平台级时操作幂等；成功返回更新后的 `SkillView`，并写入 `SKILL_PROMOTED_TO_PLATFORM` 审计记录。

### GET `/skills/{skillKey}/versions`

权限：`skill:browse`。返回版本数组：

```json
[{"id":2,"skillKey":"springboot-tdd","changeType":"INITIAL","lifecycleStatus":"DRAFT","candidateVersion":null,"version":null,"sourceRevision":1,"versionNo":0,"baseVersion":null,"replacementVersionId":null,"replacementVersion":null}]
```

### GET `/skills/versions/{versionId}`

权限：`skill:browse`。根据版本 ID 返回完整 `VersionView`，用于编辑页保存后刷新乐观锁版本。

### POST `/skills/{skillKey}/draft:open`

权限：`skill:edit`，且要求 Owner/管理员。创建或返回唯一活动草稿。返回 `VersionView`。

### GET `/skills/{skillKey}/draft`

权限：`skill:browse`。返回当前活动草稿 `VersionView`；没有活动草稿时返回 404。

### POST `/skills/{skillKey}/draft:upload`

权限：`skill:upload`，且要求 Owner/管理员。Content-Type：`multipart/form-data`。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `file` | ZIP 文件 | 是 | 必须通过 ZIP 安全校验，且包含 `SKILL.md` |
| `changeLog` | string | 否 | 本次上传说明 |

上传包不得包含保留的标准配置路径；平台会生成 `skill.yaml` 和 Overlay。响应：

```json
{"versionId":2,"sourceRevision":1,"status":"DRAFT","changeType":"INITIAL","baseVersion":null,"suggestedVersion":null,"files":[{"path":"SKILL.md","source":"UPLOADED","editable":true,"sizeBytes":100}],"warnings":[]}
```

### GET `/skills/versions/{versionId}/files`

权限：`skill:browse`。返回文件树；与 `/skill-versions/{versionId}/files` 功能相同。

### DELETE `/skills/{skillKey}/draft?versionNo={versionNo}`

权限：`skill:edit`，且要求 Owner/管理员。仅可取消 `DRAFT`，成功为空响应；版本变为 `CANCELLED`，活动草稿指针清空。

## 6. 草稿文件浏览与编辑

### GET `/skill-versions/{versionId}/files`

权限：`skill:browse`。返回 `FileView[]`：`path`、`source`、`editable`、`sizeBytes`。

### GET `/skill-versions/{versionId}/files/content?path=SKILL.md`

权限：`skill:browse`。返回文本内容。仅允许平台配置的文本扩展名。

### PUT `/skill-versions/{versionId}/files/content`

权限：`skill:edit`，且要求 Owner/管理员；仅允许 `DRAFT`。

```json
{"path":"SKILL.md","content":"# Updated\n","versionNo":1}
```

成功返回更新后的 `FileView[]`，并创建新 Source Revision。

### POST `/skill-versions/{versionId}/files:upload`

权限：`skill:edit`，且要求 Owner/管理员。Content-Type：`multipart/form-data`。

参数：`path`（资源相对路径）、`versionNo`、`file`。成功返回 `FileView[]`。

### DELETE `/skill-versions/{versionId}/files`

权限：`skill:edit`，且要求 Owner/管理员。

查询参数：`path`、`versionNo`。`skill.yaml` 和 Overlay 不允许直接删除。返回 `FileView[]`。

### POST `/skill-versions/{versionId}/standard-config:reset?versionNo={versionNo}`

权限：`skill:edit`，且要求 Owner/管理员。恢复默认 `skill.yaml` 和 Overlay，返回 `FileView[]`。

### POST `/skill-versions/{versionId}:validate`

权限：`skill:edit`。返回告警字符串数组，例如 `SKILL_MD_REQUIRED`。

### GET `/skill-versions/{versionId}/diff`

权限：`skill:review` 或 `skill:edit`。比较当前源码和 `baseVersion`，返回变更文件：

```json
[{"path":"SKILL.md","changeType":"MODIFIED","baseContent":"old","currentContent":"new","baseSizeBytes":3,"currentSizeBytes":3}]
```

`changeType` 为 `ADDED`、`MODIFIED` 或 `DELETED`。文本文件返回前后内容，二进制文件只返回大小。

## 7. 依赖管理

### GET `/skill-versions/{versionId}/dependencies`

权限：`skill:browse`。返回：

```json
[{"skillKey":"java-coding-standards","versionConstraint":">=1.0.0 <2.0.0","dependencyType":"RUNTIME","required":true,"sortOrder":0}]
```

### PUT `/skill-versions/{versionId}/dependencies`

权限：`skill:edit`，且要求 Owner/管理员；仅允许 `DRAFT`。

```json
{"versionNo":1,"dependencies":[{"skillKey":"java-coding-standards","versionConstraint":">=1.0.0 <2.0.0","dependencyType":"RUNTIME","required":true}]}
```

`dependencyType` 使用后端 `DependencyType` 枚举。返回 `DependencyView[]`。

### POST `/skill-versions/{versionId}/dependencies:resolve`

权限：`skill:edit`。预览直接及传递依赖：

```json
{"items":[{"skillKey":"java-coding-standards","version":"1.3.0","depth":1,"requiredByPath":"root -> java-coding-standards"}],"conflicts":[]}
```

### GET `/skill-versions/{versionId}/dependents`

权限：`skill:browse`。返回反向依赖数组：`skillKey`、`versionId`、`versionConstraint`。

## 8. 兼容性、平台与 Adapter

### GET `/skill-versions/{versionId}/compatibilities`

权限：`skill:browse`。返回 `CompatibilityView[]`。

### PUT `/skill-versions/{versionId}/compatibilities`

权限：`skill:edit`，且要求 Owner/管理员；仅允许 `DRAFT`。

```json
{
  "versionNo":1,
  "compatibilities":[{
    "platform":"CODEBUDDY",
    "agent":"CODEBUDDY_DEFAULT",
    "osType":"WINDOWS",
    "required":true,
    "declaredStatus":"COMPATIBLE",
    "minPlatformVersion":null,
    "maxPlatformVersion":null,
    "overlayPath":"overlays/codebuddy.yaml"
  }]
}
```

`osType`：`ANY/WINDOWS/MACOS/LINUX`；兼容状态为 `COMPATIBLE/TRANSFORM_REQUIRED/UNSUPPORTED/VALIDATION_FAILED`。返回 `CompatibilityView[]`，字段另含 `validatedStatus`。

### POST `/skill-versions/{versionId}/compatibilities:validate`

权限：`skill:edit`，且要求 Owner/管理员。运行 Adapter 可用性校验，返回 `CompatibilityView[]`。

### GET `/platforms`

权限：`skill:browse`。返回平台及 Adapter：

```json
[{"id":1,"platformKey":"CODEBUDDY","platformName":"CodeBuddy","status":"ACTIVE","adapters":[{"adapterVersion":"1.0.0","implementationKey":"codebuddy-adapter"}]}]
```

### GET `/adapters`

权限：`skill:browse`。返回动态发现的 Adapter：`platformKey`、`implementationKey`、`adapterVersion`。

## 9. 审核、发布、废弃与下架

生命周期主路径：`DRAFT → REVIEWING → APPROVED → PUBLISHED → DEPRECATED/OFFLINE`。驳回回到 `DRAFT`，取消变为 `CANCELLED`。

### POST `/skill-versions/{versionId}:submit-review`

权限：`skill:edit`，且要求 Owner/管理员。

```json
{"versionNo":2,"comment":"Ready for review"}
```

返回：`versionId`、`candidateVersion`、`changeType`、`baseVersion`、`lifecycleStatus`、`reviewId`。

### GET `/reviews`

权限：`skill:review`。返回分页审核任务和历史。查询参数：`status`（`PENDING/APPROVED/REJECTED`）、`keyword`（Skill Key/名称）、`page`、`size`、`sort`。不传 `status` 时返回全部历史。

### GET `/reviews/{reviewId}`

权限：`skill:review`。允许读取所有审核状态。返回：`reviewId`、`versionId`、`skillKey`、`skillName`、`reviewNo`、`status`、`candidateVersion`、`submitterId`、`submitterName`、`submittedAt`、`reviewerId`、`reviewerName`、`reviewedAt`、`submitComment`、`reviewComment`。只有 approve/reject 要求记录仍为 `PENDING`。

### POST `/reviews/{reviewId}:approve`

权限：`skill:review`。

```json
{"comment":"Approved"}
```

审核意见必填，返回 `ReviewView`。审核通过后后端立即触发发布构建：构建成功时版本进入 `PUBLISHED` 并生成可下载制品；构建失败时审核仍保留为通过、版本保持 `APPROVED`，可通过构建任务/通知查看失败原因并重试发布。

### POST `/reviews/{reviewId}:reject`

权限：`skill:review`。

```json
{"comment":"Please revise SKILL.md"}
```

版本退回 `DRAFT`。发起者可修改后再次提交，或取消草稿；每次重提创建新的 `reviewId/reviewNo`。

### POST `/reviews:batch-approve`

权限：`skill:review`。管理员批量通过审核，单批最多 50 条。每条成功审核会立即触发发布构建；构建成功的版本进入 `PUBLISHED`，构建失败的版本保持 `APPROVED` 并在结果/构建任务中体现失败。批次采用部分成功策略，已处理或不存在的记录在 `items` 中单独返回失败原因。

```json
{"reviewIds":[101,102,103],"comment":"批量审核通过"}
```

`comment` 可选，将应用于本批所有记录。返回 `total`、`successCount`、`failureCount` 以及逐条 `items`（`reviewId`、`success`、成功时的 `review`，失败时的 `errorCode`/`errorMessage`）。

### POST `/reviews:batch-reject`

权限：`skill:review`。请求格式和返回格式同批量通过；成功记录的审核状态为 `REJECTED`，对应 Skill 版本退回 `DRAFT`，维护者可修改后重新提交。

```json
{"reviewIds":[101,102],"comment":"请补充使用说明"}
```

`reviewIds` 必填且不可重复，最多 50 条；空列表、重复 ID 或超过上限返回 400。

### POST `/skill-versions/{versionId}:withdraw`

权限：`skill:edit`，且要求 Owner/管理员。当前实现用于将 `APPROVED` 版本撤回 `DRAFT`。

```json
{"versionNo":3,"comment":"Need more changes"}
```

### POST `/skill-versions/{versionId}:publish`

别名：`POST /skill-versions/{versionId}/publish`。权限：`skill:publish`。必须携带唯一请求头：

```http
Idempotency-Key: <UUID或全局唯一字符串>
```

仅允许 `APPROVED` 版本。成功返回 HTTP 202：

```json
{"taskId":1,"versionId":2,"status":"SUCCEEDED","idempotencyKey":"...","startedAt":"2026-09-03T01:00:00Z","finishedAt":"2026-09-03T01:00:02Z","errorCode":null,"errorMessage":null}
```

发布会按已注册 Adapter 和 OS 目标生成 Artifact，写入 MinIO，并把版本更新为 `PUBLISHED`。

### GET `/build-tasks/{taskId}`

权限：`skill:publish`。返回 `BuildTaskView`。

状态为 `PENDING`、`RUNNING`、`SUCCEEDED` 或 `FAILED`；失败时通过 `errorCode/errorMessage` 展示诊断信息。

### GET `/skill-versions/{versionId}/build-tasks`

权限：`skill:publish`。查询参数：`page`、`size`、`sort`；返回按创建时间倒序的 `PageResponse<BuildTaskView>`。

相同 `Idempotency-Key` 返回原任务；已有不同幂等键的 `PENDING/RUNNING` 任务时返回 `409 BUILD_TASK_ALREADY_RUNNING`；失败后以新幂等键重试会创建新任务并保留历史。

### POST `/skill-versions/{versionId}:deprecate`

权限：`skill:publish`。

```json
{"replacementVersionId":3}
```

请求体可省略；版本从 `PUBLISHED` 变为 `DEPRECATED`。

替代版本必须是同一 Skill 的其他 `PUBLISHED` 版本且不得形成循环。错误码：`INVALID_REPLACEMENT_VERSION`、`REPLACEMENT_VERSION_NOT_PUBLISHED`、`REPLACEMENT_VERSION_SKILL_MISMATCH`、`REPLACEMENT_VERSION_CYCLE`。替代版本下架不会自动清除已有引用。

### POST `/skill-versions/{versionId}:offline-impact`

权限：`skill:offline`。返回：

```json
{"publishedDependents":2,"blocked":true,"dependents":[{"skillKey":"consumer-skill","displayName":"Consumer Skill","versionId":12,"version":"2.1.0","lifecycleStatus":"PUBLISHED","versionConstraint":">=1.0.0"}]}
```

### POST `/skill-versions/{versionId}:offline`

权限：`skill:offline`。

```json
{"force":false,"reason":"Security issue"}
```

存在发布态依赖方且 `force=false` 时返回冲突；成功后版本为 `OFFLINE`，不可再下载。

## 10. Artifact 查询与下载

### GET `/skill-versions/{versionId}/artifacts`

权限：`skill:browse`。仅返回 `AVAILABLE` Artifact：

```json
[{"id":1,"platform":"CODEBUDDY","osType":"ANY","artifactType":"PLATFORM_SKILL","fileName":"skill-1.0.0-codebuddy-any.zip","sizeBytes":659,"sha256":"...","adapterVersion":"1.0.0"}]
```

### GET `/skill-versions/{versionId}/download`

权限：`skill:download`。

查询参数：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `platform` | `CODEBUDDY` | 例如 `CODEBUDDY`、`OPENCODE` |
| `osType` | `ANY` | `WINDOWS/MACOS/LINUX/ANY` |

仅允许下载 `PUBLISHED` 或 `DEPRECATED` 版本。优先查找精确 OS Artifact，缺失时回退到 `ANY`。响应为 ZIP 二进制，并记录下载审计。

## 11. Bundle

Bundle 用于把多个已发布 Skill 及其传递依赖组合为一个 ZIP。

### POST `/bundles`

权限：`skill:download`。

```json
{"platform":"CODEBUDDY","osType":"ANY","rootVersionIds":[1,2],"includeDependencies":true}
```

返回：

```json
{"id":1,"bundleKey":"sha256...","status":"AVAILABLE","platform":"CODEBUDDY","osType":"WINDOWS","includeDependencies":true,"fileName":"bundle-....zip","sizeBytes":1024,"sha256":"...","errorCode":null,"errorMessage":null,"createdAt":"2026-09-03T01:00:00Z","items":[{"skillKey":"springboot-tdd","versionId":10,"version":"1.2.0","root":true,"depth":0,"requiredByPaths":["springboot-tdd"],"artifactId":31,"artifactOsType":"WINDOWS","artifactFileName":"springboot-tdd.zip","artifactSha256":"..."}]}
```

请求约束：`platform` 非空且平台启用；`osType` 只能为 `ANY/WINDOWS/MACOS/LINUX`；`rootVersionIds` 为 1–50 个非空且不重复的 ID。只接受 `PUBLISHED/DEPRECATED` 根版本。

Artifact 选择优先使用请求的 OS，找不到时回退 `ANY`；两者均不存在返回 `422 BUNDLE_ARTIFACT_NOT_FOUND`，错误消息包含缺失的 Skill、平台和 OS。其他稳定错误码包括 `PLATFORM_NOT_AVAILABLE`、`BUNDLE_VERSION_NOT_DOWNLOADABLE`、`BUNDLE_DEPENDENCY_CONFLICT`、`BUNDLE_RESOLVED_VERSION_NOT_FOUND`。

当前创建过程同步完成，正常响应固定为 `AVAILABLE`；失败会直接返回错误。后台 Worker 重试的历史失败 Bundle 可能短暂处于 `BUILDING/FAILED`。

### GET `/bundles`

权限：`skill:download`。Bundle 是创建者私有资源。本接口查询当前用户创建的 Bundle 历史。参数：`page`、`size`、`sort`，返回 `PageResponse<BundleView>`。

### GET `/bundles/{bundleId}`

权限：`skill:download`。仅创建者可访问并返回 `BundleView`；其他用户统一返回 404。

### GET `/bundles/{bundleId}/items`

权限：`skill:download`。仅创建者可访问。返回 `BundleItemView[]`，字段：`skillKey`、`versionId`、`version`、`root`、真实 `depth`、全部 `requiredByPaths`、实际使用的 `artifactId`、`artifactOsType`、`artifactFileName` 和 `artifactSha256`。

### GET `/bundles/{bundleId}/download`

权限：`skill:download`。仅创建者可以下载自己的 `AVAILABLE` Bundle，其他用户返回 404；响应为 ZIP，并记录下载审计。

## 12. 审计

### GET `/audit-logs`

权限：`admin:audit`。

必填查询参数：`targetType`、`targetId`。返回匹配的审计日志数组，按创建时间倒序。常见目标类型：`SKILL`、`SKILL_VERSION`、`USER`、`ROLE`。

审计实体包含 `eventType`、操作人、`targetType/targetId`、`requestId`、`result`、`reason`、`before/after/metadata` 及时间字段。当前接口直接序列化实体，前端不应依赖未声明的懒加载关联字段。

## 13. 推荐前端调用顺序

维护者流程：

```text
POST /skills
→ POST /skills/{key}/draft:upload
→ GET/PUT /skill-versions/{id}/files/content
→ POST /skill-versions/{id}:validate
→ POST /skill-versions/{id}:submit-review
→ GET /skills/{key}/versions 或 GET /skills/{key}/draft 查看结果
```

审核发布流程（审核通过自动触发发布，无需再次点击发布）：

```text
GET /reviews
支持 `scope=ALL|PLATFORM|TEAM` 和 `teamId` 筛选；团队选择可使用 `GET /wiki/teams/search?keyword=&page=&size=` 服务端搜索。
→ POST /reviews/{id}:approve 或 :reject
→（通过时后端自动创建并执行发布构建）
→ GET /build-tasks/{taskId}
```

普通用户流程：

```text
GET /skills
→ GET /skills/{key}/versions
→ GET /skill-versions/{id}/artifacts
→ GET /skill-versions/{id}/download?platform=CODEBUDDY&osType=ANY
```

## 14. 站内通知

通知接口要求已登录。用户只能查询和修改自己的通知。

### GET `/notifications`

查询参数：

- `unreadOnly`：可选布尔值，默认 `false`；`true` 仅返回未读通知（`readAt IS NULL`），`false` 返回当前用户的全部通知。
- `page`：可选页码，从 `0` 开始，默认由 Spring Pageable 处理。
- `size`：可选每页条数，默认由 Spring Pageable 处理。
- `sort`：可重复传递的排序参数，格式为 `实体属性,方向`，方向为 `asc` 或 `desc`。通知创建时间必须使用 JPA 实体属性 `timeCreated`，例如 `sort=timeCreated,desc`；不能使用返回字段名 `createdAt`。

返回 `PageResponse<NotificationView>`。响应中的创建时间字段仍为 `createdAt`：

```json
{"items":[{"id":1,"type":"REVIEW_SUBMITTED","title":"新的 Skill 审核任务","content":"Demo 已提交审核","targetType":"REVIEW","targetId":10,"skillKey":"demo","versionId":20,"readAt":null,"createdAt":"2026-09-03T01:00:00Z"}],"page":0,"size":20,"totalElements":1,"totalPages":1}
```

通知类型及跳转目标：

- `REVIEW_SUBMITTED`：通知审核人，`targetType=REVIEW`、`targetId=reviewId`。
- `REVIEW_APPROVED/REVIEW_REJECTED`：通知提交者，`targetType=SKILL_VERSION`、`targetId=versionId`。
- `PUBLISH_SUCCEEDED/PUBLISH_FAILED`：发布发起人的目标为 `BUILD_TASK/taskId`；其他 Skill Owner 的目标为 `SKILL_VERSION/versionId`，确保普通 Owner 有权访问。发布人与 Owner 重合时只生成发布人通知。
- `SKILL_DEPRECATED/SKILL_OFFLINE`：通知 Skill Owner，`targetType=SKILL_VERSION`。
- `SKILL_VERSION_UPDATED`：为历史下载用户推荐新版本的预留类型，本期不会自动触发。

### GET `/notifications/unread-count`

返回当前用户未读数量：`{"count":3}`。

### PATCH `/notifications/{id}/read`

将当前用户拥有的通知标记为已读，操作幂等；返回更新后的 `NotificationView`。访问其他用户的通知按不存在处理并返回 404。

## 15. 当前已知对接注意事项

## 16. 平台与操作系统选择

Skill 市场的 `GET /skills` 和 Agent 会话均支持 `platform`、`osType`。支持的平台为 `CODEBUDDY`、`OPENCODE`，操作系统为 `ANY`、`WINDOWS`、`MACOS`、`LINUX`。

前端应将用户显式选择的值通过详情页查询参数继续传递：`/skills/{skillKey}?platform=OPENCODE&osType=LINUX`。未传值时使用 `CODEBUDDY` 和 `ANY`。下载接口优先返回精确匹配的 Artifact，没有精确匹配时回退到同平台 `ANY` Artifact。

## 17. 评价、评论与下载统计

### GET `/skills/{skillKey}/feedback`

权限：`skill:browse`。查询参数使用标准分页参数（`page`、`size`）。返回平均评分、评分数量、下载数量、当前用户最新评分和分页的全部用户评论。评分与评论相互独立：

```json
{"averageRating":4.5,"ratingCount":2,"downloadCount":18,"myRating":{"rating":5},"comments":{"content":[],"number":0,"totalElements":2,"totalPages":1,"last":true}}
```

### PUT `/skills/{skillKey}/feedback/rating`

权限：`skill:browse`。评分必须为 1 至 5；同一用户对同一 Skill 可重复评分，但系统只保留并更新最新一条评分：

```json
{"rating":5}
```

### POST `/skills/{skillKey}/feedback/comments`

权限：`skill:browse`。新增一条独立评论；同一用户可以发表多条评论：

```json
{"comment":"很好用"}
```

### DELETE `/skills/{skillKey}/feedback/comments/{commentId}`

权限：`skill:browse`。删除当前用户自己的指定评论；评分不支持删除，只能通过评分接口覆盖。

## 18. 飞书登录

启用 `FEISHU_ENABLED=true` 并配置 `FEISHU_APP_ID`、`FEISHU_APP_SECRET`、`FEISHU_REDIRECT_URI` 及随机生成的 32 字节 Base64 密钥 `FEISHU_TOKEN_ENCRYPTION_KEY` 后，访问 `GET /auth/feishu/authorize` 跳转飞书 OAuth 授权页；飞书回调 `GET /auth/feishu/callback?code=...&state=...` 后换取平台 Token，并保存当前用户的文档访问令牌。飞书个人文档访问由平台后端按当前用户代理固定公共 MCP 地址 `https://mcp.feishu.cn/mcp` 完成；飞书用户令牌不会传给 DSH，也不在 DSH 环境变量中出现。

飞书通讯录同步使用应用身份的 `tenant_access_token`，只同步飞书开放平台后台授予应用通讯录权限范围内的部门和成员。应用密钥只允许通过环境变量或密钥管理系统注入。

- 上传 ZIP 中的 `skill.yaml`、`overlays/**` 属于保留路径；平台会自动生成标准配置。前端如需调整，应调用文件编辑/重置接口。
- 发布接口实际构建可能同步完成，但仍使用 HTTP 202 和构建任务响应；前端应以任务状态和版本状态为准。

### 18.1 飞书通讯录同步与团队树

`POST /admin/feishu/sync` 要求 `admin:identity`，立即返回 `202 Accepted` 并在后台使用应用身份同步飞书部门与成员。同步成功或失败都会向发起人及平台管理员发送通知，结果请在通知中心查看；已有同步任务运行时不会重复启动。

`GET /admin/teams/tree` 和 `GET /admin/teams/{teamId}/members` 要求 `admin:identity` 或 `skill:review`。超级管理员可查看全部团队；团队管理员只能查看自己管理的团队。

成员接口返回 `userId`、`username`、`displayName`、`membershipType` 以及 `roles`。`roles` 中包含角色键、作用域类型、团队 ID 和版本号，前端可据此展示当前角色。

团队角色接口：

- `POST /admin/teams/{teamId}/members/{userId}/roles?roleKey=TEAM_ADMIN|TEAM_MAINTAINER`
- `DELETE /admin/teams/{teamId}/members/{userId}/roles/{roleKey}`

超级管理员可授予团队管理员、团队 Skill 维护员；团队管理员仅可在自己的团队内授予或撤销团队 Skill 维护员。角色授权要求目标用户属于团队，并写入审计日志。飞书未配置时同步返回 `503 FEISHU_NOT_CONFIGURED`。

### 18.2 Skill 可见范围与审核升级

创建 Skill 时可传 `teamId`。不传表示平台级 Skill；传入团队 ID 表示团队内部 Skill，仅该团队成员可在市场、详情、版本、下载和评价接口中访问。

- `POST /skill-versions/{versionId}:submit-review`：团队 Skill 产生团队审核任务，平台级 Skill 直接产生平台审核任务。
- `POST /skill-versions/{versionId}:push-to-company`：团队管理员将已发布的团队版本提交给平台二次审核。
- 平台审核通过团队升级任务后，Skill 变为平台级并对全员可见。
- `POST /admin/platform-maintainers/{userId}` / `DELETE /admin/platform-maintainers/{userId}`：超级管理员管理平台级 Skill 维护员。

### 18.3 平台、操作系统与包回退

市场查询支持 `platform=CODEBUDDY|OPENCODE` 和 `osType` 参数，前端会把用户选择带入详情页。下载接口优先匹配所选平台与系统的包；没有精确包时回退到同平台 `ANY` 系统包，没有选择时使用前端默认选项。

### 18.4 下载量、评分与评论

`GET /skills/{skillKey}/feedback` 返回 `downloadCount`、五分制 `averageRating`、`ratingCount`、当前用户 `myRating` 和分页的全部用户评论。`PUT /skills/{skillKey}/feedback/rating` 使用 1 至 5 分更新当前用户最新评分；`POST /skills/{skillKey}/feedback/comments` 新增评论；`DELETE /skills/{skillKey}/feedback/comments/{commentId}` 删除本人评论。

## 19. Wiki 文档

Wiki 文档只支持 UTF-8 Markdown，当前类型为 `SKILL_README` 和 `SKILL_GUIDE`。正文保存为文档修订，编辑和恢复都会生成新的修订；文档归档后不再出现在普通查询中。

- `GET /wiki/teams`：返回当前用户可访问的团队。
- `GET /wiki/teams/search?keyword=&page=&size=`：按团队名称服务端搜索当前用户可访问的团队，最多每页 50 条。
- `GET /wiki/documents?teamId=&skillKey=&documentType=&keyword=&page=&size=`：按权限分页查询文档。
- `GET /wiki/documents/{id}`：读取当前可见文档及其最新修订。
- `GET /wiki/documents/{id}/revisions`：读取文档修订历史。
- `POST /wiki/documents`：创建文档，请求字段为 `title`、`documentType`、`teamId`、`skillIds`、`markdownContent`。
- `PUT /wiki/documents/{id}`：编辑标题和 Markdown，必须携带当前 `versionNo`。
- `POST /wiki/documents/{id}:restore`：请求 `revisionId` 和当前 `versionNo`，从历史修订生成新的当前修订。
- `DELETE /wiki/documents/{id}`：归档文档。

平台级文档对所有已登录且拥有 `skill:browse` 的用户可见；团队文档对有效直接成员、团队管理员及超级管理员可见。团队管理员可维护本团队套组文档，Skill owner/maintainer 只能维护自己 Skill 的 README，超级管理员可维护全部文档。

README 必须且只能关联一个 Skill；`SKILL_GUIDE` 套组说明才允许关联多个 Skill。

团队级 `SKILL_GUIDE` 可申请推送到全平台。提交后文档锁定编辑，审核通过后保留原文档 ID、团队归属、修订历史和 Skill 关联，仅设置 `platform_visible=true`；后续团队编辑继续直接同步到全平台。

- `POST /wiki/documents/{id}:submit-platform-review`：提交平台推广审核，请求 `{"versionNo":0,"comment":"..."}`；需要团队管理员或超级管理员权限。
- `GET /wiki-reviews?status=&keyword=&teamId=&page=&size=&sort=`：查询 Wiki 平台推广审核任务，需要 `wiki:review`。
- `GET /wiki-reviews/{id}`：读取审核详情及提交时的 Markdown 快照。
- `POST /wiki-reviews/{id}:approve`、`POST /wiki-reviews/{id}:reject`：通过或拒绝，请求字段为必填 `comment`。
- `POST /wiki-reviews/{id}:withdraw`：提交人或文档维护者撤回待审核申请。
- `POST /wiki-reviews:batch-approve`、`POST /wiki-reviews:batch-reject`：批量处理，最多 50 条。

`wiki:review` 默认授予超级管理员和审核员角色；团队管理员仅负责提交自己团队的 Wiki，不自动获得平台审核权限。

团队 Skill 调用 `POST /skill-versions/{versionId}:push-to-company` 时，可在 `wikiDocumentIds` 中勾选套组文档；该 Skill 的 README 自动加入平台审核。平台审核通过后 Skill 转为平台级，所选 Wiki 文档设置为平台可见。文档不复制，团队后续编辑会立即同步到平台可见版本。

## 20. Agent MCP Wiki 工具

现有 `/internal/mcp` 提供 `get_current_user_context`、`search_wiki_documents`、`get_wiki_document`、`search_feishu_documents` 和 `get_feishu_document`。`get_current_user_context` 返回当前 Agent 用户有权访问的团队；Wiki 搜索可通过 `teamId` 限定团队。工具通过 Agent Token 的用户身份执行平台/团队 Wiki 可见性校验；飞书工具由后端代理当前用户的只读授权，DSH 不接收飞书 UAT。`get_skill_detail` 同时返回该 Skill 的可见 Wiki 文档列表。DSH 推荐 Skill 前应读取相关 README 或套组说明，最终推荐提交时再次校验 Skill 当前仍对该用户可见。

`search_feishu_documents` 会为每条结果返回 `docId`、`docType`、`readable`、`readMethod` 和 `unreadableReason`。当前仅 `DOCX`（新版文档，使用飞书 MCP）和 `DOC`（旧版文档，使用 `/open-apis/doc/v2/{docId}/raw_content`）可读取。Spreadsheet、Bitable、Slides、Mindnote、Wiki及未知类型标记为 `readable=false`，不得调用读取工具。`get_feishu_document` 请求必须携带搜索结果中的 `docId` 和 `docType`，并支持 `offset`、`maxBytes` 分页参数；不可读取类型返回 `UNSUPPORTED_DOCUMENT_TYPE`，不得重试。

`search_skills` 接收可选的 `platform` 和 `osType`。没有配置精确兼容项的通用平台 Skill 仍会返回。返回结果顶层包含本次查询的 `target`，每个 Skill 结果还包含 `targetPlatform`、`targetOsType` 和带查询参数的 `detailPath`；Agent 必须将这些目标字段复制到 `submit_skill_recommendation.items`。`get_skill_detail` 同样返回 `target` 和 `detailPath`。因此用户选择 `OPENCODE + WINDOWS` 时，推荐卡片链接会打开 `/skills/{skillKey}?platform=OPENCODE&osType=WINDOWS`，详情页直接使用该选择；未选择时详情页才使用默认值。

## 21. Agent MCP 调用审计

`GET /admin/agent/mcp-audits?page=0&size=20` 查询 DSH 实际调用过的 MCP 工具。接口需要 `admin:audit` 权限，仅超级管理员可访问。

支持按 `runKey`、`sessionKey`、`toolName`、`status`、`sourceChannel`、`from` 和 `to` 筛选。记录包含工具名称、调用 ID、Agent Run、会话渠道、知识范围、开始/结束时间、耗时和失败码；参数仅保存脱敏摘要，不保存 Token、完整问题、工具结果或飞书正文。

飞书云文档调用可通过 `toolName` 筛选：`search_feishu_documents`、`get_feishu_document`。

## 22. Agent 会话与批量下载

### 会话运行环境优先级

- `POST /api/v1/agent/sessions/{sessionKey}/messages` 请求为 `{"content":"...","context":{"platform":"CODEBUDDY","osType":"MACOS"}}`；`context` 可选，存在时是该轮及会话的权威环境并跳过文本识别；缺省时继续从当前轮消息识别明确的平台和操作系统，旧行为不变。
- 当前轮明确表述优先于已保存筛选；当前轮未提到的维度继续沿用会话值。
- 响应除 `runKey`、`status` 外还返回最终生效的 `platform`、`osType`，页面应立即同步选择器。
- 飞书机器人结果卡片包含平台和操作系统选择器，卡片回调通过长连接更新同一 Agent 会话，并从下一轮生效。

- `PATCH /agent/sessions/{sessionKey}/context`：更新当前会话下一轮使用的平台和操作系统；运行中的会话不可修改。
- `DELETE /agent/sessions/{sessionKey}`：软删除本人会话。会话立即从列表和详情中隐藏，后台最多保留 180 天后清理关联消息、Run、推荐和 MCP 审计。
- 推荐结果的每个 item 由服务端补全可信的精确 `versionId`。
- 推荐项的 `platform`、`osType` 始终取该 Agent run 的服务端会话上下文；模型提交的同名字段被忽略。平台只接受 `CODEBUDDY/OPENCODE`，操作系统只接受 `ANY/WINDOWS/MACOS/LINUX`。
- `POST /agent/runs/{runKey}/bundle`：根据该轮已提交的推荐创建批量下载包，请求字段为 `platform`、`osType` 和可选 `skillKeys`。未传或空数组选择全部推荐；传值时必须是该 run 推荐项的子集，否则返回 `422 AGENT_BUNDLE_SKILL_NOT_RECOMMENDED`。根版本严格使用推荐时保存的 `versionId`，并自动包含必需依赖。
- Bundle 请求的平台和操作系统必须与该 run 已保存的非空上下文一致；不一致返回 `422 AGENT_BUNDLE_CONTEXT_MISMATCH`，非法枚举返回 `422 AGENT_TARGET_INVALID`。历史网页会话某个上下文维度为空时，仍可由该请求补齐该维度。

批量下载返回 `resultStatus`：`COMPLETE` 表示全部成功，`PARTIAL` 表示部分 Skill 因平台或系统不兼容失败，`FAILED` 表示没有可下载项；`failures` 返回失败 Skill 及原因，成功项通过 `id` 调用 `GET /bundles/{id}/download` 下载。

### POST `/skill-updates:check`

IDE 新会话用于批量检查本地平台安装 Skill 是否存在可用新版本。权限：`skill:browse`。请求示例：

```json
{"platform":"CODEBUDDY","osType":"MACOS","skillKeys":["brainstorming","yantu-hook-probe"]}
```

`skillKeys` 最多 200 个。接口只返回当前可见、ACTIVE、最新版本为 `PUBLISHED` 且存在对应平台/操作系统可下载 Artifact 的 Skill；操作系统优先匹配精确值，也接受 `ANY` Artifact。响应为 `{platform, osType, items}`，每项包含 `skillKey`、`displayName`、`latestVersionId` 和 `latestVersion`。客户端使用本地 `.yantu-platform-skill.json` 的 `skillVersionId/version` 与该结果比较，再通过 `POST /bundles` 创建更新包。

## 23. 本地启动配置

后端会自动加载项目根目录的 `.env`，本地启动只需：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=agent
```

可从 `.env.example` 创建 `.env` 并填写数据库、JWT、MinIO 和飞书配置。`.env` 已加入 Git 忽略，不能提交真实密钥。默认飞书回调地址为：
`http://127.0.0.1:5173/oauth/callback`。

需要外网或飞书 H5 测试时，设置 `FEISHU_MOCK_HTTPS_ENABLED=true` 和
`FEISHU_MOCK_HTTPS_BASE_URL=https://your-tunnel.trycloudflare.com`，系统会自动使用
`https://your-tunnel.trycloudflare.com/oauth/callback`。Tunnel 地址变化后需同步修改飞书后台重定向 URL。

## 24. Skill 调用遥测

CodeBuddy Hook 仅对带 `.yantu-platform-skill.json` 来源标记的 Skill 产生调用事件。所有接口要求 IDE Bearer Token，用户身份从 Token 服务端解析，客户端不能提交飞书身份字段。

- `POST /telemetry/skill-usage-events`：同步保存 Skill 调用元数据。请求字段包括 `eventId`、`skillKey`、`skillVersionId`、`installationId`、`invokedAt`、`localDirectory`、`clientSessionId`、`generationId`、`client`、`clientVersion`、`agentType` 和 `model`。必须使用 `Idempotency-Key`，重复 `eventId` 不会重复计数。
- `PUT /telemetry/skill-usage-events/{eventId}/conversation`：上传 gzip 压缩的可见用户/助手对话快照，最大 20 MiB，返回 `202` 后由后台任务合并到 MinIO；对话正文不写入 MySQL。

MySQL 的 `skill_usage_event` 保存调用统计和对话状态；`skill_usage_conversation` 仅保存按用户和 CodeBuddy session 聚合的 MinIO 对象指针、版本及离线持久化版本字段。飞书 `user_id`、`open_id` 和 MinIO 对话对象使用遥测专用 AES-GCM 密钥加密。

## 25. Skill 使用管理看板

看板接口需要 `admin:telemetry`。超级管理员可查询全部团队；团队管理员的权限由 `TEAM_ADMIN` scoped assignment 动态授予，并且只能看到自己管理团队的有效成员。

- `GET /admin/skill-usage/access-scope`：返回当前管理员是全平台还是团队范围，以及可选团队列表。
- `GET /admin/skill-usage/overview?from=&to=&teamId=&skillKey=&userId=`：返回调用次数、活跃成员、使用 Skill 数、对话采集成功率、时间趋势、Skill/成员排行和采集状态。
- `GET /admin/skill-usage/events?page=0&size=20&from=&to=&teamId=&skillKey=&userId=`：分页返回调用明细，只返回元数据，不读取对话正文或暴露 MinIO 对象地址。
- `GET /admin/skill-usage/events/{eventId}/conversation?page=0&size=100`：仅在管理员点击明细后调用，读取该用户和 CodeBuddy session 的最新合并对话；对话正文保存在 MinIO，接口最多每页返回 100 条消息。

看板默认查询近 30 天，支持今天、近 7 天、近 90 天和自定义时间范围。对话查看会产生 `SKILL_USAGE_CONVERSATION_VIEWED` 审计记录。

## 26. 虚拟项目组与项目文档控制面（P2）

项目组使用不可变 UUID `projectKey` 对外标识。SMS 是项目和项目文档正文的唯一权威存储；本期不绑定 Git 仓库、不保存仓库地址或凭证。

项目成员角色为 `OWNER`、`MAINTAINER`、`MEMBER`：OWNER 管理项目和成员；MAINTAINER 可发布文档；MEMBER 可读取项目并创建、编辑草稿。非成员访问项目统一返回 `404`。

- `POST /projects`、`GET /projects`、`GET/PATCH /projects/{projectKey}`：创建、查询和更新项目。
- `POST /projects/{projectKey}:archive`：归档项目。
- `GET /projects/{projectKey}/members`、`PUT/DELETE /projects/{projectKey}/members/{userId}`：管理成员。
- `GET/POST /projects/{projectKey}/documents`：查询或创建 `REQUIREMENT`、`PRD`、`ARCHITECTURE`、`UI_DESIGN` 文档。
- `GET /projects/{projectKey}/documents/{documentId}`：读取文档的当前草稿和正式版本。
- `PUT /projects/{projectKey}/documents/{documentId}/draft`：保存新草稿修订。每次保存产生不可变 revision，必须携带当前 `versionNo`。
- `GET /projects/{projectKey}/documents/{documentId}/revisions`：查询修订历史。
- `POST /projects/{projectKey}/documents/{documentId}:publish`：由 OWNER/MAINTAINER 将指定修订发布为正式版本。

Agent 只能保存草稿，不能发布项目文档或管理成员。连续多轮对话通过同一 `documentId` 追加修订。文档从未发布且最后草稿活动超过 180 天时由后台任务归档清理；已发布过的文档不会自动清理。

`ProjectContext` 为后续 Git 集成预留 `externalResources` 扩展字段，P2 固定为空数组。下一期可增加 Git repository resource，不需要改动 `projectKey` 和文档会话主接口。

OpenHands 网关启动前由服务端调用 `POST /internal/document-agent/runs`（请求头 `X-SMS-Service-Token`）签发短期项目 Agent Token，请求字段为 `actorId`、`projectKey`、可选 `documentId` 和 `profileKey`。该 Token 仅允许项目上下文、文档读取、草稿保存和结构校验，不允许发布文档或管理成员。

项目 Agent 会话由 SMS 代理 Gateway，浏览器不直接调用 Gateway：

- `POST /projects/{projectKey}/document-agent/sessions`：请求 `documentId`、`profileKey`，创建 OpenHands 会话。
- `GET/DELETE /projects/{projectKey}/document-agent/sessions/{sessionId}`：查询或关闭会话。
- `POST /projects/{projectKey}/document-agent/sessions/{sessionId}/turns`：请求 `content`，提交一轮对话。
- `GET /projects/{projectKey}/document-agent/jobs/{jobId}`：查询任务状态。
- `GET /projects/{projectKey}/document-agent/jobs/{jobId}/events?after=`：读取 SSE 事件。

## 27. 独立文档 Agent 入口（P3）

文档 Agent 会话与 DSH 完全隔离，SMS 负责权限、项目归属、会话/任务持久化，Gateway 负责 OpenHands 运行时。浏览器只调用 SMS，不直接访问 Gateway。

- `GET /document-agent/sessions?projectKey=&limit=`：列出当前用户可恢复的文档会话。
- `POST /document-agent/sessions`：创建会话。请求字段为 `projectKey`、`profileKey`、`mode`（`NEW`/`EXISTING`）、可选 `documentId` 和新文档 `title`；必须携带 `Idempotency-Key`。
- `GET /document-agent/sessions/{sessionKey}`、`DELETE /document-agent/sessions/{sessionKey}`：查询或关闭会话。
- `POST /document-agent/sessions/{sessionKey}/turns`：提交一轮文档指令，字段为 `instruction`、可选 `sourceArtifactIds` 和 `feishuDocuments[{docId,docType,title}]`；必须携带 `Idempotency-Key`。
- `GET /document-agent/jobs/{jobKey}`、`POST /document-agent/jobs/{jobKey}:cancel`：查询或取消任务。
- `GET /document-agent/jobs/{jobKey}/events?after=`：读取任务 SSE 事件。
- `GET /document-agent/feishu-documents:search?query=`、`POST /document-agent/feishu-documents:resolve`：为本次生成搜索或解析飞书文档。SMS 只保存文档标识和标题，正文由 OpenHands 通过 MCP 分段读取。

会话默认保留 30 天无活动窗口，历史任务和事件保留 180 天。每一轮任务最多选择 10 份飞书文档；MCP token 仅允许读取当前任务选中的文档，Agent 通过 `save_artifact_draft` 保存草稿，不能发布或修改成员权限。

## 流程最佳实践视频

### `GET /api/v1/dev-pipeline/videos/{skillKey}`

按需流式读取流程最佳实践页的 Skill 演示视频。接口需要登录态，当前仅开放流程页中的 36 个 Skill；视频对象位于 MinIO 的 `dev-pipeline/videos/{skillKey}.mp4`。

- 支持 `Range: bytes=start-end` 和后缀范围请求，成功返回 `206 Partial Content`，并携带 `Content-Range`、`Accept-Ranges: bytes`。
- 不带范围时返回 `200 OK`；支持 `If-None-Match`，命中时返回 `304 Not Modified`。
- 响应携带私有缓存策略 `Cache-Control: private, max-age=86400` 和 MinIO ETag，前端仅在用户点击播放时发起请求。
- 未知 Skill 或未上传视频返回 `404`；不合法的范围返回 `416`。
