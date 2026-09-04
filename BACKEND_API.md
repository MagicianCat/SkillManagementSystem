# Skill Management System 后端 API 对接文档

> 维护基准：当前工程代码。前端联调时以本文档和实际响应为准；接口变更时应同步更新本文档。

## 1. 通用约定

- Base URL：`/api/v1`
- 默认数据格式：`application/json`
- 文件上传：`multipart/form-data`
- 鉴权：除登录、刷新 Token 和健康检查外，请求需携带 `Authorization: Bearer <accessToken>`。
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

### Mock 测试账号

仅在 `skill-platform.auth.mock.enabled=true` 时自动幂等创建以下账号，密码均为 `Test@123456`：

| 用户名 | 用途 | 角色 | 主要权限 |
|---|---|---|---|
| `test-user` | 普通用户 | `CONSUMER` | `skill:browse`、`skill:download` |
| `test-maintainer` | Skill 管理者 | `MAINTAINER` | `skill:browse`、`skill:download`、`skill:upload`、`skill:edit` |
| `test-admin` | 管理员 | `ADMIN` | 全部 Skill、审核、发布、下架、身份和审计权限 |

管理员用户名可由 `MOCK_ADMIN_USERNAME` 覆盖；管理员密码仍必须通过 `MOCK_ADMIN_PASSWORD` 配置。测试账号只应在开发/测试环境使用。

### POST `/auth/login`

匿名登录。请求：

```json
{"username":"maintainer","password":"******","provider":"MOCK"}
```

字段均必填。响应：

```json
{
  "accessToken":"...",
  "refreshToken":"...",
  "expiresIn":1800,
  "user":{"id":2,"username":"maintainer","displayName":"Maintainer","roles":["MAINTAINER"],"permissions":["skill:browse","skill:download","skill:upload","skill:edit"]}
}
```

### POST `/auth/refresh`

匿名可调用，使用 Refresh Token 轮换新 Token。

```json
{"refreshToken":"..."}
```

响应结构与登录一致；旧 Refresh Token 随即失效。

### POST `/auth/logout`

使 Refresh Token 失效。

```json
{"refreshToken":"..."}
```

成功返回空响应。

### GET `/users/me`

权限：已登录。返回当前用户的 `id`、`username`、`displayName`、`roles`、`permissions`。

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

查询参数：`keyword`、`categoryId`、`tag`、`platform`、`osType`、`lifecycleStatus`、`developmentStage`、`ownerId`、`page`、`size`、`sort`。

`platform`、`osType` 会按最新已发布版本的兼容性声明过滤，枚举值大小写不敏感。

`developmentStage` 按 Skill 本体的开发阶段过滤（与版本 `lifecycleStatus` 无关），大小写不敏感，取值：`REQUIREMENT`（需求）、`DESIGN`（设计）、`FRONTEND_CODING`（前端编码）、`BACKEND_CODING`（后端编码）、`TESTING`（测试）、`RELEASED`（已发布）、`OTHER`（历史数据/未标记）。非法值返回 `400 INVALID_DEVELOPMENT_STAGE`。

排序字段使用实体属性名，如 `sort=timeUpdated,desc`（不要使用 `updatedAt` 等不存在的属性，否则会返回 500）。

返回 `PageResponse<SkillView>`。`SkillView` 字段：

```json
{"id":1,"skillKey":"springboot-tdd","displayName":"Spring Boot TDD","description":"...","categoryId":10,"category":{"id":10,"key":"development","name":"Development","parentId":null,"sortOrder":1},"tags":[{"id":21,"key":"java","name":"Java"}],"owners":[{"userId":2,"username":"maintainer","displayName":"Maintainer","ownerType":"PRIMARY"}],"status":"ACTIVE","developmentStage":"BACKEND_CODING","versionNo":0,"latestPublishedVersion":"1.0.0","activeDraftVersionId":2}
```

### POST `/skills`

权限：`skill:upload`。

```json
{"skillKey":"springboot-tdd","displayName":"Spring Boot TDD","description":"...","categoryId":10,"ownerUserIds":[],"tagIds":[21,22],"developmentStage":"REQUIREMENT"}
```

- `skillKey` 只能使用小写字母、数字和单个连字符分段。
- 非管理员只能把自己设为 Owner；`ownerUserIds` 为空时自动使用当前用户。
- 管理员可以指定其他 Owner。
- `developmentStage` 可选，缺省为 `REQUIREMENT`；非法值返回 `400 INVALID_DEVELOPMENT_STAGE`。

返回 `SkillView`。

### GET `/skills/{skillKey}`

权限：`skill:browse`。返回 `SkillView`。

### GET `/categories`

权限：`skill:browse`。返回启用的分类列表，字段：`id`、`key`、`name`、`parentId`、`sortOrder`。

### GET `/tags?keyword={keyword}`

权限：`skill:browse`。查询标签，`keyword` 可选，最多返回 50 条。字段：`id`、`key`、`name`。

### PATCH `/skills/{skillKey}`

权限：`skill:edit`，且要求 Skill Owner 或管理员。

```json
{"displayName":"New name","description":"New description","categoryId":10,"tagIds":[21],"developmentStage":"TESTING","versionNo":0}
```

- `developmentStage` 可选；传 `null`/缺省表示不修改当前阶段，非法值返回 `400 INVALID_DEVELOPMENT_STAGE`。

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

审核意见必填，返回 `ReviewView`。版本进入 `APPROVED`。

### POST `/reviews/{reviewId}:reject`

权限：`skill:review`。

```json
{"comment":"Please revise SKILL.md"}
```

版本退回 `DRAFT`。发起者可修改后再次提交，或取消草稿；每次重提创建新的 `reviewId/reviewNo`。

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
{"id":1,"bundleKey":"sha256...","status":"AVAILABLE","platform":"CODEBUDDY","osType":"WINDOWS","includeDependencies":true,"fileName":"bundle-....zip","sizeBytes":1024,"sha256":"...","errorCode":null,"errorMessage":null,"createdAt":"2026-09-03T01:00:00Z","items":[{"skillKey":"springboot-tdd","versionId":10,"version":"1.2.0","root":true,"depth":0,"requiredByPaths":["springboot-tdd"],"artifactId":31,"artifactOsType":"WINDOWS"}]}
```

请求约束：`platform` 非空且平台启用；`osType` 只能为 `ANY/WINDOWS/MACOS/LINUX`；`rootVersionIds` 为 1–50 个非空且不重复的 ID。只接受 `PUBLISHED/DEPRECATED` 根版本。

Artifact 选择优先使用请求的 OS，找不到时回退 `ANY`；两者均不存在返回 `422 BUNDLE_ARTIFACT_NOT_FOUND`，错误消息包含缺失的 Skill、平台和 OS。其他稳定错误码包括 `PLATFORM_NOT_AVAILABLE`、`BUNDLE_VERSION_NOT_DOWNLOADABLE`、`BUNDLE_DEPENDENCY_CONFLICT`、`BUNDLE_RESOLVED_VERSION_NOT_FOUND`。

当前创建过程同步完成，正常响应固定为 `AVAILABLE`；失败会直接返回错误。后台 Worker 重试的历史失败 Bundle 可能短暂处于 `BUILDING/FAILED`。

### GET `/bundles`

权限：`skill:download`。Bundle 是创建者私有资源。本接口查询当前用户创建的 Bundle 历史。参数：`page`、`size`、`sort`，返回 `PageResponse<BundleView>`。

### GET `/bundles/{bundleId}`

权限：`skill:download`。仅创建者可访问并返回 `BundleView`；其他用户统一返回 404。

### GET `/bundles/{bundleId}/items`

权限：`skill:download`。仅创建者可访问。返回 `BundleItemView[]`，字段：`skillKey`、`versionId`、`version`、`root`、真实 `depth`、全部 `requiredByPaths`、实际使用的 `artifactId` 和 `artifactOsType`。

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

审核发布流程：

```text
GET /reviews
→ POST /reviews/{id}:approve 或 :reject
→ 审核通过后 POST /skill-versions/{id}:publish
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

查询参数：`unreadOnly`（默认 `false`）、`page`、`size`、`sort`。返回 `PageResponse<NotificationView>`：

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

- 上传 ZIP 中的 `skill.yaml`、`overlays/**` 属于保留路径；平台会自动生成标准配置。前端如需调整，应调用文件编辑/重置接口。
- 发布接口实际构建可能同步完成，但仍使用 HTTP 202 和构建任务响应；前端应以任务状态和版本状态为准。
