# 面试讲法

## 30 秒项目定位

> 我基于自研 Java Agent Runtime 做了异常订单诊断与受控恢复项目 OrderCare。Agent 从自然语言定位 FlowOrder 案例并解释权威事实和 SOP；恢复通过不可变 Proposal、版本化人工审批和 actionRequestId 领域幂等执行。写响应丢失或进程在 EXECUTING_TOOL 崩溃时，Java 协调器查询原 Action，并结合 FlowOrder 执行租约和业务回查恢复，不生成第二个副作用命令。项目已通过真实 PostgreSQL、MySQL、RabbitMQ 故障 E2E 和 20/20 真实模型 Eval，达到 Interview Strong，但不夸大为生产级。

不要说“对标或复刻 Claude Code”。更准确的说法是：参考成熟 Agent 的 Runtime 不变量，在有限业务场景中自行实现并理解取舍。

## 为什么这里需要 Agent，而不是固定工作流

- 已知目标后的 `preview -> approval -> execute -> verify` 应由确定性流程负责。
- Agent 的必要性来自入口不确定：运营人员可能只给 requestId、orderNo、deductNo、deadLetterId 或一段现象描述。
- 诊断证据分散在订单、扣减、库存、死信和 SOP 中，模型适合做标识提取、工具选择和解释。
- FlowOrder 返回的 `diagnosisCode`、硬风险和候选动作是权威结论，模型不能自己改交易规则。
- 因此边界是：Agent 负责理解、诊断解释和建议；程序负责校验、执行和验证。

M3 证据：7 类确定性诊断、Proposal 过期/漂移门禁、审批原参数恢复、业务幂等、Action 租约、UNKNOWN 对账、确定性收敛、响应丢失和崩溃恢复，以及 20/20 真实模型 Eval。

## 与目标实习岗位的能力映射

这份项目最适合证明四类能力：

- Java / Spring 工程：接口边界、JDBC 持久化、有界线程池、超时取消、租约、幂等和失败分类；
- Agent 核心：模型驱动循环、Context Engineering、Tool Calling、审批恢复和 Sub-Agent 隔离；
- RAG / Memory：Embedding、pgvector、混合召回、语义重排、引用、短长期记忆分层；
- 可靠性与可观测性：事件时间线、Trace、Eval、Replay、预算、熔断和明确终止原因。

不要为了覆盖所有 Java JD，把 Redis、MQ、微服务注册中心或高并发交易能力硬加到这个项目里。面试时只讲当前源码和验证证据；传统分布式能力应由其他真正使用这些组件的项目证明。

## 故事一：为什么删除固定 Route

问题：为什么 `IntentRouter -> RAG/Tool -> LLM` 不是真正 Agent Loop？

回答要点：

- 固定路由只在开始时决定一次分支，工具结果无法自然回到模型继续规划。
- 新 Runtime 每轮都从同一时间线构造 Context，模型可以调用工具、收到失败/拒绝结果后再规划。
- Route 可以作为提示或能力，但不能成为控制模型与工具交互的唯一状态机。

证据类：`DefaultAgentRuntime.executeLoop`、`JsonAgentModelGateway`。

## 故事二：同步和 SSE 为什么必须统一

问题：以前两套执行器有什么风险？

回答要点：

- 如果 SSE 自己实现简化 RAG/Tool 流程，可能缺少审批、幂等、Eval 或恢复，产生不同安全语义。
- 当前同步适配器收集 Runtime 结果，SSE 适配器只转发 Runtime 事件。
- 客户端断开时 SSE 请求取消 Run，取消信号落 PostgreSQL。

边界：当前 SSE 是事件级，不是逐 Token 结构化输出。

## 故事三：上下文压缩如何保证工具语义

问题：为什么不能简单截断历史字符串？

回答要点：

- ToolCall 没有 ToolResult，模型会误以为工具仍未执行；反过来也缺少来源。
- Timeline Store 强制工具对关系，Context Manager 以完整 MessageUnit 裁剪。
- 摘要记录覆盖到的 message sequence，完整历史仍保留在数据库。
- Provider 真正返回 context overflow 后，再缩小预算压缩一次，避免无限重试。

## 故事四：审批恢复与副作用幂等

问题：批准后为什么不是重新请求一次？

回答要点：

- Run 在 `WAITING_APPROVAL` 时保存 pending ToolCall、已完成结果和使用工具。
- 模型只能把 `proposalId` 交给高风险工具；审批前扩展点重新从 FlowOrder 读取 Proposal，把版本、指纹、effects/warnings digest、preview digest 和有效期替换进 ApprovalRecord，不能审批模型自己复述的影响。
- `proposalId` 表示被审批的不可变预演；`actionRequestId` 表示一次有副作用命令的领域幂等键，二者不能混成同一个概念。
- 审批后 claim 同一个 Run，执行或写入拒绝 ToolResult，再继续同一个 Agent Loop。
- 审批决定通过数据库同时检查 `status=REQUESTED` 和 `expiresAt>decisionTime`；批准、拒绝和过期只有一个状态迁移能成功，失败方读取胜出结果，既不能后写覆盖，也不能在 CAS 时刻批准已过期请求。
- Profile、能力白名单、累计 Token/成本与剩余执行时长都随 Run 持久化；审批等待不消耗执行预算，Approval 则有独立有效期，恢复不会重新获得预算或默认权限。
- 同一个 Run 的每次执行使用唯一 leaseOwnerId；claim 失败者不能继续工具执行。
- 模型提供的 ToolCall ID 只用于追踪；Runtime 生成全局执行 ID 作为消息配对和工具幂等键，存储层额外拒绝跨 Run 复用；已完成调用返回持久化结果。
- 写请求超时明确不进入通用重试；`RecoveryOutcomeReconciler` 先查原 `actionRequestId`，只有权威状态 `NOT_STARTED` 才按原审批参数补发一次，其余状态只对账。
- FlowOrder 的 `EXECUTING` 动作携带 owner 和 lease；租约过期且死信仍 PENDING 时，使用原 `actionRequestId` CAS 接管，死信 REPLAYING 时只等待，无法证明时转 `MANUAL_REVIEW`。
- execute 返回 `SUBMITTED` 后，由 `RecoveryConvergenceChecker` 固定次数回查 FlowOrder；只有 action、扣减、库存不变量和相关死信同时满足条件才返回 `RESOLVED`。

## 故事五：UNKNOWN 为什么不能直接重试

问题：execute 调用超时后，为什么不再发一次？

回答要点：

- HTTP 超时只表示调用方没收到结果，FlowOrder 可能已经提交 RabbitMQ，直接换 ID 重试会制造第二个业务动作。
- enterprise-agent 保存原 ToolCall、Approval、Proposal 与 actionRequestId；先查 Action，`SUBMITTED/EXECUTING` 都不补发。
- 只有 FlowOrder 明确返回 `NOT_STARTED` 才允许按原参数补发一次，响应再次丢失仍回到同一 Action 查询。
- Runtime 重启从 `EXECUTING_TOOL` 检查点恢复，经通用 `UncertainToolExecutionResolver` 对账，再把确定 ToolResult 追加回原时间线。
- 真实 PostgreSQL 证据中，响应丢失与重复 resume 的 executeCount 始终为 1；崩溃恢复场景 executeCount 为 0。

## 故事六：为什么规则没有全部删除

问题：既然有模型，为什么还保留正则和确定性规则？

回答要点：

- 手机号、身份证、JWT、私钥等格式是可验证事实，确定性 DLP 更便宜、更稳定，也避免把敏感原文发送给分类模型。
- Prompt Injection 的短语命中不能直接证明恶意意图，所以规则只产生高召回信号，再由模型语义确认。
- 语义分类不可用且已有高风险信号时安全失败；没有信号时不因分类器异常阻断全部请求。
- Memory 提取不同：写入长期状态的误判代价高，所以模型/协议失败时宁可不写，也不回退正则猜测。

## 故事七：Memory 为什么拆成两层

问题：为什么不把近期消息、摘要、画像和长期记忆全塞一个对象？

回答要点：

- 短期事实必须严格有序，属于 Runtime Timeline。
- Context Summary 是时间线投影，不是替代历史的另一份事实。
- 长期记忆是有损提取，需要类别、置信度、脱敏、去重和 pgvector 召回。
- 用户画像是显式键值事实，按上限加载；不需要假装成聊天消息。

## 故事八：Sub-Agent 的隔离是什么

问题：和几个 Service 并行调用有什么区别？

回答要点：

- 每个子 Agent 使用独立 Session、Run、System Prompt、能力白名单和预算。
- Planner 无工具；RAG Specialist 只有 `knowledge_search`；工单 Specialist 只有只读 `ticket_status`；Reviewer 无工具。
- 子 Agent 禁止写长期记忆，主协调者只收到摘要与 childRunId。
- Specialist 使用有界线程池并行，但不是跨节点调度。

## 面试时主动承认的边界

- 没有 OS 级 Sandbox；文件根目录和网络 Host Policy 不是容器隔离。
- 没有内建身份认证和租户管理后台，管理 API 需要外部网关保护。
- 模型工具协议目前是 JSON Gateway，不是各 Provider 原生 Tool Calling Adapter。
- SSE 是 Runtime 事件级，未实现结构化 JSON 的逐 Token 增量解析。
- 默认测试共 64 条，另有真实 PostgreSQL 故障 E2E、FlowOrder 真实 MQ E2E 和 20 条模型 Eval；仍不应声称覆盖所有多实例、容量与安全场景。
- PDF/DOCX 等二进制文档解析尚未实现。

主动说清边界通常比堆叠“大厂级、生产级、全链路”更可信。
