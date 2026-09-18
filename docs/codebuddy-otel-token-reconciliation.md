# CodeBuddy 遥测埋点对账报告（最小验证）

**日期**：2026-09-18　**验证人**：后端（本地 debug 接收器）
**目标**：确认 CodeBuddy 通过 OTel 上报了哪些字段，能否拿到 token 消耗量做对账。

---

## 一、验证方法

在本机起一个零依赖 OTLP/HTTP 接收器（`http://127.0.0.1:18099/v1/traces`），手写
protobuf 解码，把每个请求的 Resource / Scope / Span / Attribute 完整打日志。
通过 `export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://127.0.0.1:18099/v1/traces`
注入 CodeBuddy CLI，跑一次真实任务，抓真实 span。

> 关键坑：CodeBuddy 的 `~/.codebuddy/settings.json` 里 `env` 块**只对 Bash 工具子进程
> 生效，不进入主进程的 OTel 导出器**。必须在 shell 里 `export` OTEL 变量后再启动 CLI 才生效。

---

## 二、OTel 真实上报的埋点（已抓包确认）

CodeBuddy CLI（`@tencent-ai/codebuddy-code` v2.154.0，OTel SDK 1.30.1）一次任务产生
3 类 span 及若干 event，**全部 attribute key 枚举如下**：

**Resource（资源属性，含身份/环境）**
- `service.name="codebuddy-code"`、`service.version`
- `uid` / `userId`（用户 UUID）、`userNickname` / `gen_ai.user.id`（用户昵称）
- `extName`、`extVersion`、`ideType="CLI"`、`platform`、`platformVersion`
- `workspacePath`（工作目录绝对路径）
- `telemetry.sdk.*`、`yantu.client.installation_id`、`yantu.schema.version`

**Span 1：`codebuddy_code.model_request`（每次模型调用一条，CLIENT）**
- `model.id`（如 `custom-local:qwen3.8-max`）
- `http.method/url/target/status_code`、`net.peer.name`
- `request.id`、`conversation.request.id`、`workbuddy.{session_id,message_id,request_id,prompt_request_id}`
- `model.stream=true`、`retry_count`、`agent.name`

**Span 2：`codebuddy_code.model_stream`（流式响应）**
- `first_token_ms`（首 token 延迟，如 1530ms）
- event `first_token_received`：`ttft_ms`、`chunk_count`、`bytes`
- `stream.status`、`total_bytes`

**Span 3：agent 编排 / 工具调用相关 event**
- event `agent.tool.start_detail` / `agent.tool.success_detail`：
  `tool_name`（如 `Bash`）、`call_id`、`duration_ms`、`current_turn`（第几轮）、
  `capability_type`、`agent_name`、`agent_type`、`model_name`
- event `agent.message.success_detail` / `agent.request.success_detail`：
  `finish_reason`（如 `tool_calls`）、`current_turn`、`model`

**长度/规模类"代理指标"**
- `user_prompt_length`（用户输入**字符数**，本次 =13）
- `assistant_output_length`（助手输出**字符数**，本次 =74）
- `chunk_count`、`bytes`、`total_bytes`（流式块数与**字节数**）

---

## 三、关键结论：OTel 里没有 token 用量

把 #6–#8 三个真实请求的全部 attribute 枚举后，**不存在任何 `*.token*` 或
`gen_ai.usage.*` 字段**。OTel 只提供字符数 / 字节数 / 首 token 延迟等**代理指标**，
**无法直接得到 token 消耗量**。

---

## 四、`[TelemetryDebug]` 证据（token 走的是另一条私有通道）

CodeBuddy 主日志（`~/.codebuddy/logs/2026-09-18/second-shelf__….log`）中，token 相关
数据由 `[Report Service] [TelemetryDebug]` 以**私有 JSON 埋点**形式上报，**不经 OTel**：

```
[Report Service] [TelemetryDebug] report code=chat_request_send payload={
  "eventCode":"chat_request_send",
  "requestModelId":"custom-local:qwen3.8-max",
  "requestModelName":"Qwen 3.8 Max（公司内部）",
  "inputLength":13817,            ← 输入规模（注意：是字符/长度，非 token）
  "conversationId":"5e201117-…",
  "traceId":"e200d6afbc8fe18ddab05908c7e62543",
  "agentName":"cli","agentType":"main",
  "userId":"c2cfec1c-…","userNickname":"晒猫的阳光",
  "vcsRepo":"","vcsBranchName":"main","vcsRevId":"6713786c…",
  …
}
```

其它证据：
- 启动即打印 `OTLP traces will be sent through proxy: http://127.0.0.1:7897`（走本机 Clash 代理）。
- `[TracingService] [primary] Exported N spans`：OTel 仅负责 span 导出，与上面的
  `[TelemetryDebug] report code=…` 是**两套独立上报**。
- 遥测插件 `yantu-assistant-telemetry@yantu-assistant-local` 已启用，但当前版本只发
  私有埋点，未把 token 注入 OTel span。

**截图中的 40,841（token 消耗量）来自这条私有埋点通道，OTel span 里对不上账。**

---

## 五、对后端采集的建议

1. **token 用量**：OTel 拿不到，需二选一——
   - 让 CodeBuddy 侧在 OTel span 注入 `gen_ai.usage.*`（需其插件/exporter 支持，属 CodeBuddy 能力）；
   - 或后端收到会话内容后**自算 token**（可控、可复算，推荐），OTel 仅作上下文补充。
2. **OTel 的价值**：稳定提供 `user/conversation/model/工具调用/首token延迟/轮数` 等
   上下文，适合做调用链审计与按用户/会话归集，可落库到
   `/api/v1/telemetry/otel/v1/traces`（配 `X-Yantu-Telemetry-Token` 校验）。
3. **配置要点**：OTEL 变量必须在 shell `export` 后启动 CLI；`settings.json` 的 `env`
   块对主进程遥测无效。
