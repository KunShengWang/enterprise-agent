# Unified Agent Workbench V1：M0 冻结决策表

> 对应蓝图：[unified-agent-workbench-v1-design.md](./unified-agent-workbench-v1-design.md)
> 蓝图版本：V0.2.3 / FINAL
> 更新时间：2026-07-19 CST
> 当前结论：PASSED，允许冻结 M0，允许后续开始 M1-A

## 1. 冻结规则

本表冻结编码前必须稳定的架构语义，不冻结需要通过 Eval 校准的运行参数。“通过”表示不存在待选择的架构分支；表中的实现验收约束仍必须在对应里程碑通过自动化测试证明。

## 2. 决策总表

| # | 决策主题 | V0.2.3 冻结结论 | 状态 | 实现验收约束 |
|---:|---|---|---|---|
| 1 | WorkItem、Input 与 Relation | `agent_work_input` 先落库；只有 `NormalGoalEnvelope` 创建 WorkItem；Recovery Plan 创建独立子 WorkItem | 通过 | RESUME、ABANDON、PAUSE、CANCEL、ADD_INPUT 不新建 WorkItem；Recovery Plan 使用 `RECOVERY_OF` |
| 2 | Router 审计身份 | Router 使用 `agent_routing_decision`，不是 Agent Run | 通过 | 独立统计模型、Prompt digest、Token、延迟、结果和失败，不计入业务 Agent Run 数 |
| 3 | Target 与 Command 边界 | Registry 只有四个 ExecutionTarget；WorkCommand 独立分类和处理 | 通过 | WorkCommand 不进入 Target Catalog；只有 `NormalGoalEnvelope` 进入 WorkItem/Router 链路 |
| 4 | WorkEvent 顺序语义 | M1 本地事件与 M2 跨源投影共享产品时间线；sequence 是数据库提交顺序 | 通过 | 保留 sourceSequence、双时间、correlationId、causationId；单事务锁定 WorkItem 分配 sequence |
| 5 | MODEL_DELTA | 在线透传与 Run timeline 回放双通道 | 通过 | 不逐 token 复制到 WorkEvent；断线可恢复且不混入子 Agent 正文 |
| 6 | 兼容入口 | M1 保留 `/api/agent/runs/**` 和 `/api/incidents/**` | 通过 | 新入口调用应用服务而非本机 HTTP 反调 Controller；旧入口用于回归和高级调试 |
| 7 | Incident 启动门禁 | 所有 Incident Investigation 固定 Preview → Explicit Confirmation → Start | 通过 | 未确认时 Commander/Specialist/Reviewer Run 数为 0；确认绑定 Preview 版本与范围 |
| 8 | 预算冻结范围 | 只冻结层级、配置项、累计不重置和 fail-closed | 通过 | 具体阈值由 Eval/成本数据校准；预算未知时危险路径 fail-closed |
| 9 | Conversation Focus | 使用 `agent_conversation_work_state` 保存单一 focused WorkItem | 通过 | `focused != running`；Focus 使用 CAS、验证所有权，不影响后台执行或权限 |
| 10 | START_NEW_WORK | 先审计命令，再从同一 inputId 派生 DerivedNormalGoal/Envelope | 通过 | 不直接创建 WorkItem、不选择 Target；旧任务不变；Abandon → Start 分别审计和分配 causationId |
| 11 | Principal/Tenant | 产品控制面统一 `tenant_id + owner_principal_id` | 通过 | 所有权链确定性校验；Recovery 子任务不继承父权限；Relation 禁止跨 tenant |
| 12 | WorkCommandClassifier 审计 | 使用 `agent_work_command_decision` 独立审计 deterministic/model 分类 | 通过 | 按钮 model calls=0；模型分类记录 Token/延迟/digest；同 inputId 只有一个 EFFECTIVE 决策 |
| 13 | ValidatedExecutionInput | extractedInputs 只作为候选，Java 形成带来源的强类型输入 | 通过 | MODEL_INFERRED 的危险标识禁止 Dispatch；Incident 范围和 Recovery incidentId 来源受限 |
| 14 | NormalGoalEnvelope / DerivedNormalGoal | DIRECT_NORMAL_GOAL 与 START_NEW_WORK 派生目标都规范化为唯一新目标信封 | 通过 | 沿用原 sourceInputId，不生成第二条用户输入，两类来源共享相同 WorkItem/Router/Validator 流程 |
| 15 | WorkItem-before-Router | 先提交 WorkItem/Relation/Event/Focus 本地事务，再调用 Router | 通过 | `agent_routing_decision.work_item_id` 非空；Router 超时、失败或拒绝时保留 WorkItem 与审计记录 |
| 16 | M1 ExecutionTarget Command Capability Matrix | 按现有代码能力冻结四类 Target 的命令支持范围 | 通过 | 不支持项返回 `UNSUPPORTED_FOR_TARGET`，不得修改底层状态或伪造控制投影成功 |
| 17 | M1 Minimal WorkEvent Boundary | M1-A 建立最小 `agent_work_event` 和本地 append；M2 扩展跨源投影、SSE、Replay | 通过 | WorkItem/Relation/Focus/本地事件同事务；append 失败整体回滚；M2 不改变 Schema 与 sequence 语义 |
| 18 | Routing Recovery Before Decision | WorkItem 创建时固化 routingRequestId；M1-B 负责 stale ROUTING 恢复 | 通过 | CAS 单实例扫描、有界 attempt、失败审计、最多一个 EFFECTIVE decision；恢复后仍经 Validator/Incident 确认门禁 |

## 3. Conversation Focus 冻结语义

```text
focused WorkItem != running WorkItem
```

- 一个 Conversation 最多一个 Focus，但可以有多个后台 RUNNING WorkItem；
- 无显式 ID 的 Resume、Add Input、Pause、Cancel、Abandon 默认指向 Focus；
- Focus 变更使用 version CAS，并验证 tenant、owner Principal、Conversation；
- Focus 只负责代词解析和前端当前视图，不赋权、不停止后台任务；
- 无 Focus、Focus 不可操作或存在歧义时 fail-closed 并澄清。

## 4. START_NEW_WORK 冻结语义

```text
START_NEW_WORK
→ 写入唯一生效的 command decision
→ 从同一 inputId 派生 DerivedNormalGoal
→ 规范化为 NormalGoalEnvelope
→ 创建新的 ROUTING WorkItem
→ CAS 将 Conversation Focus 切换到新 WorkItem
→ 默认不暂停、不取消、不放弃旧 WorkItem
```

`START_NEW_WORK` 本身不注册为 ExecutionTarget，也不直接调用 Router。只有规范化后的 `NormalGoalEnvelope` 可以进入 WorkItem/Router 链路；派生过程不得创建第二条 `agent_work_input`。

明确复合命令“放弃旧任务并开始新任务”拆成两个受控动作：

```text
ABANDON_ACTIVE_WORK
→ START_NEW_WORK
```

两者分别记录 command decision、WorkEvent、CAS 结果和 causationId。前一个失败时后一个默认不执行。

## 5. NormalGoalEnvelope 与 WorkItem-before-Router 冻结语义

唯一的新目标流程为：

```text
agent_work_input 已落库
→ WorkCommandClassifier
→ NORMAL_GOAL 直接形成 NormalGoalEnvelope
  或 START_NEW_WORK 审计后形成 DerivedNormalGoal/NormalGoalEnvelope
→ PostgreSQL 本地事务创建 ROUTING WorkItem 与稳定 routingRequestId、可选 Relation、WORK_ITEM_CREATED，并 CAS 切换 Focus
→ 事务提交
→ RoutingCoordinator CAS claim
→ UnifiedTaskRouter(workItemId, routingRequestId)
→ agent_routing_decision(workItemId NOT NULL, routingRequestId, attemptNo)
→ RoutePolicyValidator
```

Focus CAS 或本地事件 append 失败时整体回滚且不得调用 Router。Router 超时、解析失败、要求澄清或策略拒绝时，WorkItem 继续作为审计与恢复根存在；不得产生无 `workItemId` 的 routing decision。

## 6. M1 ExecutionTarget Command Capability Matrix

矩阵以当前代码能力为准，不把未来设计伪装成 M1 已支持：

| ExecutionTarget | ADD_INPUT | PAUSE | RESUME | CANCEL | ABANDON |
|---|---|---|---|---|---|
| `GENERAL_AGENT` | `UNSUPPORTED_IN_M1` | `SUPPORTED_EXISTING_RUNTIME` | `SUPPORTED_EXISTING_RUNTIME` | `SUPPORTED_EXISTING_RUNTIME` | `PRODUCT_ONLY` |
| `ORDERCARE_CASE` | `UNSUPPORTED_IN_M1` | `SUPPORTED_EXISTING_RUNTIME` | `SUPPORTED_EXISTING_RUNTIME` | `SUPPORTED_EXISTING_RUNTIME` | `PRODUCT_ONLY` |
| `INCIDENT_INVESTIGATION` | `UNSUPPORTED_IN_M1` | `UNSUPPORTED_IN_M1` | `UNSUPPORTED_IN_M1` | `UNSUPPORTED_IN_M1` | `PRODUCT_ONLY` |
| `INCIDENT_RECOVERY_PLAN` | `UNSUPPORTED_IN_M1` | `UNSUPPORTED_IN_M1` | `UNSUPPORTED_IN_M1` | `UNSUPPORTED_IN_M1` | `PRODUCT_ONLY` |

- `SUPPORTED_EXISTING_RUNTIME` 仅表示在底层 Run 状态允许时复用现有 `AgentRuntime`，不新增跨执行器协议；
- `PRODUCT_ONLY` 只改变 WorkItem 的产品关注/控制状态，不取消、暂停或修改底层执行对象；
- `UNSUPPORTED_IN_M1` 必须返回结构化 `UNSUPPORTED_FOR_TARGET`，保持 Incident、Plan、Run 与执行投影不变；
- Incident Specialist 的内部定向追问续跑不是统一入口的通用 `ADD_INPUT`。

## 7. M1 Minimal WorkEvent Boundary

M1-A 必须建立 `agent_work_event` 表和 WorkItem 本地事件追加能力，至少支持：

```text
WORK_ITEM_CREATED / ROUTING_STARTED / ROUTING_DECIDED / ROUTING_FAILED
CLARIFICATION_REQUIRED / ROUTE_CONFIRMATION_REQUIRED
DISPATCH_READY / DISPATCH_STARTED / DISPATCH_RECONCILED
EXECUTION_DISPATCHED / WORK_ITEM_ABANDONED
```

- WorkItem 自身状态、Relation、Focus 与本地事件在同一个 PostgreSQL 事务提交；
- 本地事件 `sourceType=WORK_ITEM`，由 `next_event_sequence` 分配单调产品序列；
- append 失败时创建或状态迁移整体回滚，`WORK_ITEM_CREATED` 不得仅存在于日志/内存；
- M1 只需数据库基础查询，不要求统一 SSE、跨源投影或断线回放；
- M2 才将 Run/Incident/Recovery Plan 权威事件幂等投影到同一表，并增加统一 SSE、afterSequence Replay、gap recovery、MODEL_DELTA 双通道和历史执行树；
- M2 不得重建 M1 Schema、重置 sequence 或改变本地事件语义。

## 8. Routing Recovery Before Decision

```text
NormalGoalEnvelope
→ 本地事务创建 ROUTING WorkItem + routingRequestId + WORK_ITEM_CREATED + Focus CAS
→ RoutingCoordinator CAS claim
→ UnifiedTaskRouter(workItemId, routingRequestId)
→ agent_routing_decision(attemptNo, decisionStatus)
→ RoutePolicyValidator
→ WAITING_INPUT / WAITING_CONFIRMATION / READY_TO_DISPATCH / MANUAL_REVIEW / CLOSED
```

- routingRequestId 只生成一次，所有 attempt 复用，重试不创建 WorkItem；
- WorkItem 持久化 `routing_attempt_count/routing_last_attempt_at/routing_next_retry_at/routing_failure_code`，Decision 持久化 `routing_request_id/attempt_no/decision_status`；
- 调用模型前先持久化 `STARTED` attempt；每个可观测 attempt 保留模型、Token、延迟与 failure code，同 WorkItem 最多一个 `EFFECTIVE` decision；
- M1-B 使用数据库 CAS 与单实例 stale ROUTING 扫描，不宣称多实例 lease；
- Router 返回后、Decision 前崩溃将原 attempt 标为 `RESULT_UNKNOWN` 并预留预算上界；恢复可产生新 attempt，全部可观测 Token 累计；
- 达到有界重试上限后必须离开 ROUTING；已有 EFFECTIVE decision 时禁止再次调用模型；
- Routing Recovery 仍经过 Validator，Incident 仍需 Preview/显式确认；
- Routing Recovery 与 Dispatch Reconciliation 是不同阶段，扫描器不得直接或重复 Dispatch。

## 9. Principal/Tenant 冻结语义

确定性校验链为：

```text
AuthenticatedPrincipal
→ Conversation 所有权
→ Focus/目标 WorkItem 所有权
→ 父 WorkItem/Incident 可访问性
→ Target 权限
→ ValidatedExecutionInput
→ Dispatch
```

请求体、metadata、模型输出不能覆盖 tenant、Principal、roles。WorkRelation 不允许跨 tenant；WorkLink 只关联当前 WorkItem 的 dispatchRequestId 创建或查询得到的执行对象。

## 10. Command Classifier 审计冻结语义

`agent_work_command_decision.classifier_type` 固定为：

```text
DETERMINISTIC_BUTTON
DETERMINISTIC_PROTOCOL
MODEL
```

Eval 必须能够统计：

- resume/new/abandon/add-input accuracy；
- ambiguous rate；
- wrong-focus rate；
- classification latency；
- classification Token cost；
- dangerous command misclassification count。

分类结果不能绕过 WorkCommandHandler；分类失败不得默认执行任何控制命令。

## 11. 输入与模型消息边界

```text
agent_work_input = 统一产品入口收到的用户输入事实
agent_message = 某个具体 Agent Run 的模型上下文消息
```

只有进入 General/OrderCare Run 的业务目标或经校验的补充输入可以投影到相应 `agent_message`。Resume、Pause、Cancel、Abandon、Focus 切换、Incident Preview Confirmation 等产品控制命令不得无差别写入模型上下文。

## 12. ValidatedExecutionInput 来源

标识来源固定为：

```text
EXPLICIT_USER_INPUT
TRUSTED_CONVERSATION_CONTEXT
SERVER_RESOLVED_FROM_BATCH
MODEL_INFERRED
```

`MODEL_INFERRED` 的 requestId、orderNo、deductNo、queueName、incidentId 不能 Dispatch；Incident 范围来自显式输入或服务端解析；Recovery Plan incidentId 来自已验证父 WorkItem/Conversation Context。

## 13. M1 编码顺序

M0 冻结后，后续严格按以下顺序编码：

```text
M1-A AgentWorkItem / Input / Relation / Conversation Focus / Minimal WorkEvent
→ M1-B Router / WorkCommandClassifier / Routing Recovery
→ M1-C Idempotent Adapter / Dispatch Reconciliation
→ M1-D 最小统一页面
→ M1-E 路由 Eval
```

不得跳过 M1-C 先做前端演示，也不得把 dispatch reconciliation 延后到 M3。

## 14. 可靠性测试门禁

M1-A 必须证明：

1. 创建成功必有持久化 `WORK_ITEM_CREATED`；
2. 创建事务回滚不留下孤立 WorkEvent；
3. WorkItem、Relation、Focus、事件同事务一致；
4. 并发追加 sequence 不重复；
5. 清空内存流并重启后仍可从数据库查询恢复本地事件。

M1-B 必须证明：

1. WorkItem 后/Router 前崩溃可用原 routingRequestId 恢复，且不创建第二个 WorkItem；
2. Router 后/Decision 前崩溃产生新 attempt，但最多一个 EFFECTIVE decision；
3. 重复扫描不重复决定，已有 EFFECTIVE decision 不再调用模型；
4. 全部可观测 attempt Token 累计，重试耗尽后离开 ROUTING；
5. Incident 恢复路由仍停在 WAITING_CONFIRMATION，未确认时子 Run 数为 0；
6. Routing Recovery 不重复 Dispatch，Trace 能与 Dispatch Reconciliation 明确区分。

## 15. 最终复审结论

```text
复审结论：PASSED
复审范围：Blueprint V0.2.3 / FINAL 与本决策表的一致性
复审时间：2026-07-19 CST
剩余阻塞项：无
允许冻结 M0：是
允许开始 M1-A：是
备注：允许开始不代表本轮已经开始；本轮在文档冻结后停止。
```
