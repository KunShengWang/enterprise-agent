# Phase 3 — Harness Core

起点：`ad563544fce6ae2bc238d555c6bf70abc1f38a4a` / `procurement-harness-b0`。
分支：`feat/harness-core`。本阶段不提交、不推送、不创建新 Tag。

## 编码前审计

| 职责 | B0 实际实现 | Phase 3 处理 |
|---|---|---|
| Loop owner | `DefaultAgentRuntime.executeLoop`，同步、SSE、审批恢复共用 | 保留唯一循环，现有 `AgentRuntime` 继承领域无关 `AgentRunner` |
| 模型请求/返回 | `NativeToolCallingAgentModelGateway` + `NativeChatModelClient` | 原样复用 |
| Tool dispatch | `DefaultAgentToolRuntime` → `DefaultAgentCapabilityExecutor` → `LocalToolExecutor` → Handler/MCP | 在已有授权/claim 之后统一增加有界执行 deadline |
| 终止 | `AgentStopReason` + `finish`/`finishBudgetStop` | 复用枚举；不可恢复 outcome 明确停止；兼容状态映射 |
| 计数/预算 | `AgentRunBudget`、`AgentRunLimits`、持久化 snapshot | 复用；注入 Clock 便于确定性 duration 测试 |
| 超时 | Run deadline、MCP request timeout；Local handler 无统一上限 | 本地/MCP capability 共用 deadline；MCP 原有 5 秒 request timeout 保留 |
| 错误 | `ToolCallResult.success/errorMessage/metadata`，策略状态分散 | 增加类型化 `ToolExecutionOutcome`，保留模型原有 envelope |
| 审批中断 | server-owned preparer、approval store、checkpoint、resume | 保留；WAITING_APPROVAL 在 Harness 视图为 PAUSED |
| 取消 | `AgentRunControlStore` + active budget + 安全检查点 | 原有持久化 cooperative cancellation；视图为 CANCELLED |
| Trace | 持久化 `AgentEvent`、ToolExecutionStore、listener | 增加结构化运行视图、source/outcome/latency/retry 字段 |

## 运行架构

```text
AgentRunner (AgentRuntime 的统一父接口)
  └─ DefaultAgentRuntime（原唯一 loop，run/resume/cancel/pause）
      ├─ AgentRunRecord + AgentRunState + AgentRunBudgetSnapshot
      ├─ AgentRunBudget / AgentRunLimits
      ├─ 原 ContextManager → 原 ModelGateway → Spring AI provider
      └─ DefaultAgentToolRuntime
          ├─ 原 policy / approval binding
          ├─ 原 ToolExecutionStore claim / idempotency
          └─ ToolExecutionDeadline → 原 CapabilityExecutor
              └─ 原 ToolRegistry / ToolDefinition / LocalToolExecutor
                  └─ 原 Handler 或 MCP Gateway → 原业务 Adapter
```

没有第二个 Loop、第二套 ToolRegistry 或新的业务数据存储。旧 Runtime 内已有其他场景的能力保持兼容；本阶段没有新增、启用或优化 Multi-Agent。

`AgentRunRecord.runtimeState()` 从现有权威记录派生 runId、agentId（profile name）、status、turnCount、toolCallCount、startedAt、lastUpdatedAt、elapsedDurationMillis、tokenUsage、stopReason 和 pendingInterruption。没有新增持久化副本。视图不包含 Case、supplier、quantity、budget 等业务事实。elapsedDuration 为已记录起止时间的墙钟差，包含人工等待；执行预算仍沿用暂停时冻结剩余时长的旧语义。

`AgentRuntimeResult.status()` / `RunStatus` 提供兼容映射：WAITING_APPROVAL、WAITING_INPUT、PAUSED → PAUSED；CANCELLED stop reason → CANCELLED；COMPLETED → COMPLETED；错误 → FAILED。数据库/UI 保留旧状态枚举，避免改写整个 HITL。

## Spring AI 的边界

Spring AI 负责 provider 配置、Prompt/Message、原生 tool schema/ToolCallback 描述、HTTP/SSE 模型调用与 usage。项目 Gateway 负责 provider 名称映射、消息/返回协议适配。`SchemaOnlyToolCallback.call` 拒绝实际执行；模型工具请求回到原 `DefaultAgentRuntime`。

项目 Harness 负责 run 生命周期、预算、安全检查点、审批中断、工具授权及幂等、执行结果分类和观测。未重新实现 Spring AI 的传输、模型 SDK、工具协议框架，也未开启另一个自动 Tool Calling loop。这是基于 Spring AI 的领域无关 Runtime 收敛，不能描述成“从零实现完整 Agent SDK”。

## Tool Runtime contract

保留 `ToolDefinition` 的五字段和 `ToolCallResult` 的五字段 JSON 契约。`runtimeContract()`、`outcome()` 是 Java 类型化访问方法，不增加模型 Tool schema。模型可见结果仍为既有 `toolName/success/content/errorMessage/metadata`，不会整体改包一层。

`ToolRuntimeContract` 提供 source（LOCAL/MCP）、effect（READ_ONLY/INTERNAL_WRITE/EXTERNAL_SIDE_EFFECT/DESTRUCTIVE）、timeoutMillis、outputContract、retry/approval metadata、allowedAgents/allowedStages。字段映射自原 metadata；effect 缺省按 readOnly/sideEffect/risk 推导，未知 MCP 的 effect 保守视为外部副作用。这些 metadata 不授权工具、不改变工具集合、不实现动态加载。

| Outcome | 语义 |
|---|---|
| SUCCESS | 工具成功 |
| VALIDATION_ERROR | schema/缺参/未知工具，可反馈给模型 |
| BUSINESS_ERROR | 正常业务否定反馈，不自动结束 Run |
| RETRYABLE_ERROR | 可重试类别，如传输超时；不等于本阶段新增自动 retry |
| POLICY_DENIED | 权限、profile/阶段拒绝 |
| APPROVAL_REQUIRED | HITL 暂停，不计 fatal |
| FATAL_ERROR | 不可安全继续；未知副作用执行结果进入原 MANUAL_REVIEW |

分类使用结构化 metadata/执行状态，不通过 Exception message 推断 Run stop reason。旧 retry 判定与退避仍兼容保留，未新增 aggressive retry；旧 provider 没有结构化错误码的业务失败仍按 BUSINESS_ERROR 返回。原 MCP 业务 `isError` 语义保持，传输/protocol 异常获得显式分类。

结构化 `*_TIMEOUT` / `*_UNAVAILABLE` 及 metadata 中 HTTP 429/5xx 归为 RETRYABLE_ERROR。MCP 只提取错误 envelope 的 `error.code` 到内部观测字段；不改原 content/errorMessage，也不因此设置 retryable 或增加重试。审批 pending 的预算计数沿用 B0：恢复执行时消耗工具预算；评测的请求次数另按唯一 TOOL_REQUESTED 事件统计。

执行池全进程最多 8 个 daemon worker、无积压队列；正常调用同步等待 Future。默认单次工具上限 60 秒，可由服务器目录 timeoutMillis 指定；单 Agent 主路径与 approval resume 进一步受当前 Run deadline 限制。MCP 内部更短的 timeout 保留。队列满时返回受控结果，不在调用线程运行无限阻塞工具。

Future cancel 只是协作中断，不能证明外部写被撤销。写超时标记 manualReview、不自动重试、不记成功；晚到结果不能把 claim 改为成功。无法强杀不响应 interrupt 的 Java handler；被占用的 worker 有全局数量上限。这是有限等待与副作用安全边界，不声称分布式强制取消/严格回滚。

## Budget、StopReason 和 Metrics

ERP 模式默认保持 B0：24 turns、20 model calls、40 tool calls、384000 input tokens、16000 output tokens、240000ms。未为了降低工具调用数或 token 收紧限制。Token/cost 仍使用原 provider usage 在调用间检查，不能保证一次生成内精确实时截断。Legacy profile 也保持原值。

Run deadline / cancellation 在模型调用前后及工具边界的安全检查点生效；工具等待还受剩余 Run deadline 限制。模型请求继续复用原 `SpringAiLlmService` 的网络/流超时，不新增模型流的强制抢占调度；因此不能声称对正在返回的模型流提供毫秒级硬截止。

复用 StopReason：COMPLETED、MAX_TURNS、TOOL_BUDGET_EXHAUSTED、MODEL_BUDGET_EXHAUSTED、TIMEOUT、CANCELLED、WAITING_APPROVAL、WAITING_INPUT、PAUSED、GUARDRAIL_BLOCKED、TOOL_ERROR、MODEL_ERROR、INTERNAL_ERROR、CONTEXT_OVERFLOW、IN_PROGRESS。TOOL_ERROR 对应不可恢复执行或待人工核对，TIMEOUT 对应运行预算截止。

事件增加 runId、agent、turn；工具完成增加 source、outcome、durationMs、retryCount；完成/暂停事件包含 runtimeState。新增观测只写标量/状态，不额外复制参数或完整结果。原安全审计及 Eval 原始证据继续由既有路径保存。`_harness*` 内部 metadata 在模型投影入口剔除，原截断策略、摘要策略、token projection 均未更改。

Eval artifact 的 `runtime_metrics` 包含 outcome counts、tool latency、retry count、turn/tool-call count、run duration、stop reason、budget exhaustion 与 runtimeState snapshots。结果聚合可以得到 stop reason 分布和 budget exhaustion 数。审批等待单独计数；同一调用等待后执行完成可以产生两个生命周期观测，不能将 outcome 事件数当成独立工具请求数。未实际 dispatch 的拒绝/未知工具 latency 为 0；精确 provider 内部 retry 次数仍不可得。

## 验证与成本边界

`evals/harness-core/run.py` 只接受 smoke/dev。Smoke 固定 9 个 Dev：REQ-01、REQ-03、ANOM-01、SRC-01、RFQ-01、RFQ-02、GOV-01、ROB-01、E2E-01，覆盖申请、预算/约束、寻源、审批等待/执行、治理、鲁棒和 E2E。Dev 固定原 28 个 Dev，各一次有效 trial。没有 Holdout 或 40×3 入口。

复用冻结 V2.1 Java Driver、task、seed、reset/setup、原 metric definitions，与冻结 V2.2 canonical projection/grader。新 container wrapper 用 V2.2 默认 world hash 做 reset preflight，不把旧字节 hash 伪装成规范 hash。ERP reset 独占原 world.lock。

每批次 freeze 绑定生产/runner/评测源码；infra 失败立即停，保留证据并仅允许同源码显式 resume；有效失败不重跑。安全 invariant 立即停。Dev 要求同源码 Smoke 全部通过。开发比较使用冻结 B0 中对应 Dev 的 trial 1；只作方向性观察，不能作为最终简历 benchmark 或声称 Harness 提升。

实际测试、每次失败历史、Smoke/Dev 结果与比较记录见 [Phase 3 实施报告](phase3-report.md)。V2.1、V2.2 和原 B0 证据不覆盖。

Dev 完成后补充了 fatal 终止保护：正常/审批执行遇到 fatal 结果时，不写可恢复的规划 checkpoint；从已持久化的 fatal 结果恢复时直接结束，不重执行或重新规划。初次交付时该补丁仅经过确定性验证；后续人工授权的最终源码 Smoke 见实施报告的 Final-source verification。实际 Dev 源码快照与最终差异单独保留，最终源码 Smoke 不等于重跑 Dev。

## Scope

未改 Procurement SoR/Adapter、RFQ binding/CAS/idempotency、inactive Item、requisition linkage、Prompt、工具集合、Context Engineering、Dynamic Tool Loading、Multi-Agent 或 Memory。用户 `ProcurementToolHandler.java` 的既有注释不修改、不暂存。
