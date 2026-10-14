# 外部角色资源接入（V1.1）

启动时设置 `INTEGRATION_EXTERNAL_API_KEYS_JSON`，值为来源平台 ID 到 API Key 的 JSON 映射，例如：

```sh
export INTEGRATION_EXTERNAL_API_KEYS_JSON='{"platform-a":"请替换为独立生成的高强度密钥"}'
```

生产环境必须只在 HTTPS 后开放 `POST /api/integration/v1/resources/push`，不要把密钥写入 Git、前端或日志。一个密钥只对应一个 `sourceSystem`。可通过 `INTEGRATION_EXTERNAL_MAX_FILE_BYTES` 调整单 ZIP 大小上限（默认 50 MiB），同时检查 Spring `spring.servlet.multipart` 的文件/请求上限；单批最多 20 个资源。默认每来源每实例每分钟最多 60 次请求，超限返回 HTTP 429 / 业务码 4290；多实例部署时还需在入口网关配置全局限流。

本实现遵循较新的《外部角色资源接入详细设计 V1.1》：`SKILL` 和 `DOCUMENT` 均推送原始 ZIP。早期《外部资源推送 API 接口文档》中的 DOCUMENT PDF 示例不再适用，需要联调时同步告知对接方。

外部请求仅归档 ZIP 和元数据，不解压、不导入 Skill/Wiki。超级管理员可从“外部资源接入”选择版本、下载原件、解析 ZIP，然后明确导入 Skill 草稿或 Markdown 文档。文档导入时后端强制关联该角色全部当前版本已导入的 Skill，并复用 Wiki 创建后的异步索引事件。
