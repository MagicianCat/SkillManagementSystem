# 后端日志事件目录与运维检索说明

> 维护基准：当前工程代码。日志统一使用 SLF4J 参数化输出，业务事件采用 `event=<domain>.<action>.<result>` 的 key-value 结构。
> 规范：INFO=关键业务/任务状态变化；WARN=认证失败、权限拒绝、校验失败、重试、降级；ERROR=未处理异常、存储/构建失败（必须带堆栈）。
> 脱敏红线：密码、JWT、Refresh Token、Authorization 头、文件内容、ZIP 内容一律不记录。

## 1. 通用字段

| 字段 | 说明 |
|---|---|
| `event` | 事件名，`<domain>.<action>.<result>` |
| `requestId` | 请求 ID，来自 `X-Request-Id` 头或 Servlet 容器生成；异步任务可能为 `-` |
| `actorId` | 操作用户 ID；未认证或系统任务为 `-` |
| `taskId` | 异步构建任务 ID（Bundle/发布任务）；同时写入 MDC |
| `attempt` | 任务第几次尝试，从 1 开始 |
| `durationMs` | 耗时毫秒 |
| `errorCode` | 稳定错误码，与 API 错误响应一致 |
| `skillKey` / `versionId` / `version` | Skill 稳定键、版本 ID、版本号 |
| `developmentStage` | Skill 本体开发阶段（REQUIREMENT/DESIGN/FRONTEND_CODING/BACKEND_CODING/TESTING/RELEASED/OTHER） |
| `platform` / `osType` / `adapterVersion` | 平台、操作系统、Adapter 版本 |
| `bundleId` / `bundleKey` / `lockHash` | Bundle ID、确定性键、依赖锁哈希 |

MDC 键：`requestId`、`taskId`、`attempt`（异步任务期间）。HTTP 响应头始终回写 `X-Request-Id`。

## 2. 事件目录

### 2.1 请求链路（阶段一）

| 事件 | 级别 | 关键字段 | 说明 |
|---|---|---|---|
| `http.request.completed` | INFO（5xx 为 ERROR） | requestId, actorId, method, path, status, durationMs, clientIp | 每个请求结束一条；不读请求体 |
| `request.business.failed` | WARN | requestId, actorId, method, path, errorCode, status | 业务异常统一出口 |
| `request.validation.failed` | WARN | requestId, errorCode=VALIDATION_FAILED | 参数校验失败 |
| `request.optimistic_lock.conflict` | WARN | requestId, errorCode | 乐观锁冲突 |
| `request.unhandled.exception` | ERROR | requestId, errorCode=INTERNAL_ERROR + 堆栈 | 未处理异常兜底 |

### 2.2 认证（阶段一）

| 事件 | 级别 | 关键字段 |
|---|---|---|
| `auth.login.success` | INFO | requestId, actorId, username, provider, durationMs |
| `auth.login.failure` | WARN | requestId, username, provider, errorCode, durationMs |
| `auth.refresh.success` | INFO | requestId, actorId, durationMs |
| `auth.refresh.failure` | WARN | requestId, errorCode, durationMs |
| `auth.logout.success` | INFO | requestId, actorId |
| `auth.jwt.accepted` | INFO | requestId, actorId |
| `auth.jwt.rejected` | WARN | requestId, errorCode（TOKEN_EXPIRED/TOKEN_MALFORMED/TOKEN_INVALID/业务码） |
| `auth.authentication.required` | WARN | requestId, method, path, clientIp |
| `auth.access.denied` | WARN | requestId, actorId, method, path, errorCode |

### 2.3 Skill 生命周期（阶段二）

| 事件 | 级别 | 关键字段 |
|---|---|---|
| `skill.created` / `skill.create.failed` | INFO / WARN | requestId, actorId, skillKey, skillId, developmentStage, durationMs / errorCode |
| `skill.updated` | INFO | requestId, actorId, skillKey, skillId, developmentStage |
| `skill.owners.replaced` | INFO | requestId, actorId, skillKey, ownerCount, primaryOwnerUserId |
| `skill.archived` / `skill.archive.failed` | INFO / WARN | requestId, actorId, skillKey / errorCode |
| `skill.unarchived` | INFO | requestId, actorId, skillKey |
| `skill.draft.opened` / `skill.draft.open.failed` | INFO / WARN | requestId, actorId, skillKey, versionId, changeType |
| `skill.draft.upload_registered` | INFO | requestId, actorId, skillKey, versionId, sourceRevision, changeType |
| `skill.draft.cancelled` / `skill.draft.cancel.failed` | INFO / WARN | requestId, actorId, skillKey, versionId |
| `skill.owner.denied` | WARN | requestId, actorId, skillKey, errorCode |
| `skill.upload.completed` / `skill.upload.failed` | INFO / WARN | requestId, actorId, skillKey, versionId, sourceRevision, fileCount, sizeBytes / errorCode |
| `skill.file.deleted` / `skill.file.delete.denied` | INFO / WARN | requestId, actorId, versionId, path / errorCode |
| `skill.file.uploaded` / `skill.file.upload.failed` | INFO / WARN | requestId, actorId, versionId, path, sizeBytes / errorCode |
| `skill.config.reset` | INFO | requestId, actorId, versionId |
| `skill.review.submitted` / `skill.review.submit.failed` | INFO / WARN | requestId, actorId, skillKey, versionId, candidateVersion, reviewId, fromStatus, toStatus |
| `skill.review.approved` / `skill.review.rejected` | INFO | requestId, actorId, skillKey, versionId, reviewId, fromStatus, toStatus |
| `skill.version.withdrawn` | INFO | requestId, actorId, skillKey, versionId, fromStatus, toStatus |
| `skill.version.published` | INFO | requestId, actorId, skillKey, versionId, version, taskId |
| `skill.publish.failed` | WARN | requestId, actorId, skillKey, versionId, errorCode |
| `skill.publish.idempotent_hit` | INFO | requestId, actorId, versionId, taskId, idempotencyKey |
| `skill.build.claimed` | INFO | requestId, actorId, skillKey, versionId, taskId |
| `skill.build.failed` | ERROR | requestId, actorId, skillKey, versionId, taskId, errorCode + 堆栈 |
| `skill.version.deprecated` | INFO | requestId, actorId, skillKey, versionId, version, replacementVersionId, fromStatus, toStatus |
| `skill.version.offline` | INFO | requestId, actorId, skillKey, versionId, force, publishedDependents, fromStatus, toStatus |
| `skill.offline.failed` | WARN | requestId, actorId, versionId, errorCode, publishedDependents |

### 2.4 制品与下载（阶段三）

| 事件 | 级别 | 关键字段 |
|---|---|---|
| `adapter.route` | INFO | requestId, versionId, skillKey, platform, adapterVersion, osTargets |
| `artifact.built` | INFO | requestId, versionId, skillKey, platform, osType, adapterVersion, sizeBytes |
| `artifact.download.succeeded` | INFO | requestId, actorId, versionId, skillKey, artifactId, platform, osType, artifactOsType, sizeBytes |
| `artifact.download.denied` | WARN | requestId, actorId, versionId, errorCode（VERSION_NOT_DOWNLOADABLE / ARTIFACT_NOT_FOUND） |
| `artifact.download.failed` | ERROR | requestId, actorId, versionId, artifactId, errorCode + 堆栈 |
| `bundle.create.failed` | WARN | requestId, actorId, platform, osType, errorCode |
| `bundle.cache.hit` | INFO | requestId, actorId, platform, osType, bundleId, bundleKey, lockHash |
| `bundle.build.succeeded` | INFO | requestId, actorId, platform, osType, bundleId, bundleKey, lockHash, itemCount, sizeBytes, durationMs |
| `bundle.build.failed` | ERROR | requestId, actorId, bundleId, bundleKey, errorCode + 堆栈 |
| `bundle.build.dispatch` | INFO | requestId, bundleId, bundleKey, attempt（Worker 抢占重试） |
| `bundle.build.retry` | INFO | requestId, bundleId, bundleKey, attempt, platform, osType |
| `bundle.build.retry_succeeded` | INFO | requestId, bundleId, bundleKey, attempt, sizeBytes |
| `bundle.build.retry_failed` | ERROR | requestId, bundleId, bundleKey, attempt, errorCode + 堆栈 |
| `bundle.build.lease_expired` | WARN | requestId, bundleId, bundleKey, errorCode=BUILD_LEASE_EXPIRED |
| `bundle.build.retries_exhausted` | ERROR | requestId, bundleId, bundleKey, attempt, errorCode |
| `bundle.download.succeeded` | INFO | requestId, actorId, bundleId, bundleKey, platform, osType, sizeBytes |
| `bundle.download.denied` | WARN | requestId, actorId, bundleId, errorCode（BUNDLE_NOT_FOUND / BUNDLE_NOT_AVAILABLE） |
| `bundle.download.failed` | ERROR | requestId, actorId, bundleId, errorCode + 堆栈 |
| `storage.put.completed` | INFO | bucket, key, sizeBytes |
| `storage.put.failed` | ERROR | bucket, key, errorCode + 堆栈 |
| `storage.get.failed` | ERROR | bucket, key, errorCode + 堆栈 |

### 2.5 依赖 / 兼容性 / 通知 / 权限（阶段四）

| 事件 | 级别 | 关键字段 |
|---|---|---|
| `dependency.replaced` | INFO | requestId, actorId, versionId, skillKey, count |
| `dependency.cycle.detected` | WARN | requestId, actorId, versionId, skillKey, dependencySkillKey, errorCode |
| `dependency.resolve.succeeded` | INFO | requestId, versionId, skillKey, resolvedCount |
| `dependency.resolve.conflict` | WARN | requestId, versionId, skillKey, resolvedCount, conflictCount |
| `dependency.resolve.cycle` | WARN | requestId, versionId, skillKey, errorCode |
| `compatibility.validate.completed` | INFO | requestId, actorId, versionId, checkedCount, failedCount |
| `compatibility.validate.failed` | WARN | requestId, actorId, versionId, platform, osType, errorCode |
| `compatibility.publish.blocked` | WARN | requestId, versionId, platform, osType, declaredStatus, validatedStatus, errorCode |
| `notification.created` | INFO | requestId, type, targetType, targetId, skillKey, versionId, recipientCount |
| `notification.skipped` | INFO | requestId, type, reason=no_recipients |
| `notification.read` | INFO | requestId, actorId, notificationId, type |
| `identity.user.created` | INFO | requestId, actorId, targetUserId, username |
| `identity.user.status_changed` | INFO | requestId, actorId, targetUserId, fromStatus, toStatus |
| `identity.role.created` | INFO | requestId, actorId, roleId, roleKey |
| `identity.user.roles_replaced` | INFO | requestId, actorId, targetUserId, roleCount |
| `identity.role.permissions_replaced` | INFO | requestId, actorId, roleId, permissionCount |
| `skill.version.updated` | 预留 | 版本更新推荐事件，当前不自动触发 |

## 3. 运维检索指引

### 按请求排查（前端报错/接口异常）

```bash
grep "requestId=<X-Request-Id>" app.log
```

前端从响应头 `X-Request-Id` 或错误响应体 `requestId` 字段取值。一条请求通常产生：`http.request.completed` + 业务事件 + （异常时）`request.business.failed`/`request.unhandled.exception`。

### 按 Skill 排查

```bash
grep "skillKey=<skill-key>" app.log
# 或精确到版本
grep "versionId=<id>" app.log
```

覆盖：创建 → 上传 → 提交审核 → 审核 → 发布构建 → 废弃/下架全生命周期。

### 按异步构建任务排查

发布构建：`grep "taskId=<taskId>" app.log`（`skill.build.claimed` → `skill.version.published` / `skill.build.failed`）。
Bundle 构建：`grep "bundleId=<bundleId>" app.log` 或 `grep "bundleKey=<key>" app.log`（`bundle.build.succeeded` / `bundle.build.retry*` / `bundle.build.retries_exhausted`）。

### 按用户排查

```bash
grep "actorId=<userId>" app.log        # 业务操作
grep "username=<name>" app.log         # 登录成功/失败
```

### 关注告警（建议接入采集后配置）

| 场景 | 检索条件 | 建议 |
|---|---|---|
| 登录失败突增 | `event=auth.login.failure` | 短时间窗口计数告警，防爆破 |
| 发布构建失败 | `event=skill.build.failed` | 单次即告警，带堆栈 |
| Bundle 重试耗尽 | `event=bundle.build.retries_exhausted` | 单次即告警，需人工介入 |
| 存储错误 | `event=storage.put.failed` / `event=storage.get.failed` | MinIO 不可用告警 |
| 未处理异常 | `event=request.unhandled.exception` | 5xx 兜底，单次即告警 |
| JWT 拒绝异常增多 | `event=auth.jwt.rejected` | 可能存在过期 Token 滥用或配置错误 |

## 4. 日志配置

- 默认（`application.yaml`）：控制台输出，格式 `时间 级别 [线程] logger - 消息`，root=INFO。
- 生产（`application-prod.yaml`，`--spring.profiles.active=prod`）：root=WARN，业务包=INFO。
- 接入 ELK/Loki 等结构化采集时，建议引入 `logstash-logback-encoder` 并将 `logging.pattern.console` 替换为 JSON encoder；事件字段已经以 key=value 固定在消息体内，无需改动业务代码即可被 grok/正则解析。
