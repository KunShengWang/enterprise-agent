# Phase 4 Context Engineering

起点：`cfb29cdf31ca240802cc86c2b9f9642f7fbfde42` / `procurement-harness-core-v1`。仅 ERPNext Procurement profile 启用新策略；Legacy profile 保留原行为。

## 状态与来源

| 状态 | 来源与职责 |
|---|---|
| Conversation History | Timeline 中用户和 Agent 的原始消息；保留审计，不作为权威数据库 |
| Business State | 每轮从 CaseStore 重新读取的 ProcurementCaseContextRenderer 投影；ERP 事实由既有 Adapter 工具读取，保留来源/版本；不是 live ERP 全库快照 |
| Run State | 当前 AgentRunRecord 的预算、状态、StopReason、pending interruption；单独 runtime_state 数据包装 |
| Working Context | ContextAssembler 每次创建的 AgentContextView；短生命周期，不回写历史或业务库 |
| Long-term Memory | 既有模块不重构；启用策略的采购路径不调用 Memory 或 ConversationSummarizer，不把 Case 数字写入 Memory |

复用 `ProcurementCaseContextRenderer` 作为 BusinessStateProjection：caseId/version/status 和现有 ProcurementCaseState 的需求、数量、预算、币种、交期、约束、偏好、排除供应商及缺失项。不发送整个持久化对象。Recommendation、RFQ 和审批的详细事实保留在已有工具结果/执行记录中，pending interruption 来自 RunRecord。历史 ERP 结果代表带 provenance 的观察，不宣称每轮自动重新查询 ERP。RFQ 的服务器审批绑定/CAS/授权/幂等判断仍以服务器权威状态为准。

## ContextAssembler 与 ContextPolicy

唯一执行循环仍在 DefaultAgentRuntime。ContextManager 读取 fresh canonical state 和有限持久化 Timeline，Assembler 统一处理工具投影、完整调用对去重、会话窗口、RunState 和分区计量。System prompt、现有可用 tool definitions、当前任务均保留，模型工具集仍为 15 initial / 14 post-singleUse。

ContextPolicy 是 server-owned、每个 profile 可独立设置的简单 record：enabled、maxContextTokens、reservedOutputTokens、compactionThreshold、recentUserTurns。总预算沿用模型窗口、输出预留、安全 margin 和静态指令/schema 预留，取 profile 与模型配置的较小值。threshold 默认 0.75，保留最近 user turns 默认 2；可用 `AGENT_CONTEXT_COMPACTION_THRESHOLD`、`AGENT_CONTEXT_RECENT_USER_TURNS` 配置。没有向请求 metadata 开放 policy 覆盖。

必须保留：instructions、当前用户任务、canonical Business State、RunState、关键工具事实、审批数据、evidence、当前工具 schema。可裁剪：达到 token 阈值后、近期完整 user-turn 窗口之前且不属于当前 run 的用户/assistant prose。窗口按 user turn 边界，不机械保留最后 N 条消息；完整原始 Timeline 不删除。没有引入 LLM conversation summary，旧 summary 也不作为启用路径的事实来源。

## Tool Result Projection

原始 ToolExecutionStore/Trace/Evidence 不变。Working Context 只从同一 run、同一 tool、终态 SUCCEEDED/FAILED 且 success 语义一致的执行记录加载完整原文。RUNNING/MANUAL_REVIEW 保留原时间线安全 envelope，禁止用底层成功值覆盖不确定结果。投影仍标识 untrustedToolData，不作为指令。

| 结果 contract | 策略 |
|---|---|
| search/recommendation 的重复 offer / specifications | 只有完整字段相等、supplier/sourceDigest 匹配才引用已有 subtree；保留 evidenceId、唯一事实、资格判定与失败原因 |
| history/performance 等同构 records 分页 | recordsTable 的 common + columns + rows；确定性无损表达全部行，保留尾部和 nextOffset，来源与版本可还原；不是 Top-K |
| 非 JSON、未知形态、异构记录、错误 | FULL；不通过 substring 截掉关键内容 |

字段结构决定策略，无额外模型调用。没有重写 Tool Description 或 schema。`ToolResultProjector.project()` 的既有存储/Legacy 投影不动；新 `projectForContext()` 只用于启用策略的 Working Context。

去重只用于当前可用工具中显式 readOnly 且非 sideEffect 的成功结果；同 run、tool、全部参数、事实、evidence 和版本相同才删除较早完整 call/result 对，保留最新来源。只有 source 对象下 retrievedAt/fetchedAt/collectedAt 观察时刻不参与等价性；业务日期、参数日期与版本仍参与。写工具/未知工具不去重。原执行记录与调用 trace 完整保留。

投影总会去除可证明的结构冗余；旧 conversation 删除由 token threshold 触发。mandatory facts 无法装入剩余 message budget 时，Runtime 在下一次模型调用之前以 CONTEXT_OVERFLOW 安全结束，不伪造成功压缩。无法保证任意不可压缩数据必然适配窗口。Provider overflow 仍遵循原有有限重试，重新装配更小预算，绝不退回以摘要恢复业务数据。

## Token accounting 与审计

正式成本来源为 provider usage（input/output/total）。本地 ConservativeTokenEstimator 使用 ceil(ASCII/4) + 非 ASCII UTF-16 units，仅为稳定启发式估算，排除 provider framing，不能冒称精确 tokenizer。

Context events 记录 estimatedComposition 的 instructions/toolSchema/businessState/runState/conversation/toolResult/other、estimatedWorkingContextTokens、contextTokensBefore/After、conversationMessagesRemoved、duplicateToolPairsRemoved、raw/projectedToolResultBytes 与 estimated tokens。compactionPerformed 事件数作为投影/压缩次数；同一原始结果跨轮重复投影也重复计数，不是被永久删除的数据量。Before/After 是本轮来源消息投影前后，不等同于跨版本 provider token 差值。

评测使用冻结 Phase 3 Smoke 和 V2.2 Dev selection、seed/reset、driver、grader。`evals/context-engineering/run.py` 仅添加自身源码 freeze 和五项安全不变量停止门。没有 Holdout 或完整 benchmark；不同批次模型行为可能变化，单次 paired Dev 比较仅支持方向性观察。
