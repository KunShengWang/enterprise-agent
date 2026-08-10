# 学习顺序（面试优先）

> 当前实现基线：`b6207a4`。

这个项目类很多，不建议按包逐个阅读。面试准备只需要先掌握五条主线，再根据追问补 Store、SQL 和测试。

## 1. 第一主线：单 Agent Runtime

目标：能独立讲清 Model–Tool Loop 与可靠性边界。

阅读顺序：

1. `web/AgentController.java`：直接 Runtime API；
2. `runtime/AgentRuntime.java`：run/resume/cancel 契约；
3. `runtime/DefaultAgentRuntime.java`：`run`、`executeLoop`、恢复分支；
4. `runtime/AgentRunRecord.java`：权威 Run 快照；
5. `runtime/AgentRunBudget.java`：模型、工具、Token、成本和时间预算；
6. `runtime/JdbcAgentRuntimeStore.java`：Checkpoint、Tool Claim 和原子恢复。

只需先掌握：

```text
Input Guardrail
→ Session Lease + AgentRunRecord
→ Context Projection / Compact
→ Model
→ final text 或 ToolCall
→ ToolResult 回填
→ 继续 Loop
→ COMPLETED / PAUSED / WAITING_APPROVAL / FAILED / CANCELLED
```

面试重点：Checkpoint 不是保存线程栈；Session Lease 控制同一会话并发；Budget 是执行上限；恢复使用原 runId、Profile、预算和事件序号。

## 2. 第二主线：模型协议与工具安全

目标：解释“LLM 只能提出工具请求，Java 才拥有执行权”。

阅读顺序：

1. `runtime/AgentModelGatewayConfiguration.java`；
2. `runtime/NativeToolCallingAgentModelGateway.java`；
3. `runtime/DefaultAgentCapabilityRegistry.java`；
4. `runtime/DefaultAgentToolRuntime.java`；
5. `runtime/DefaultAgentCapabilityExecutor.java`；
6. `approval/LocalApprovalService.java`；
7. `tool/LocalToolRegistry.java`。

记住一句话：

> Capability 决定有没有，Profile 决定能不能用，Visibility 决定当前阶段是否可见，Guardrail 决定这次能不能做，Approval 决定人是否同意，Tool Claim 决定是否已经做过。

默认使用 Provider 原生 `tools/tool_calls`。Gateway 只转换协议，不调用工具；`JsonAgentModelGateway` 是兼容模式。

用 `floworder_recovery_execute` 准备一个完整案例：模型只传 proposalId，Java 恢复不可变 Preview，审批绑定版本/指纹/digest，审批后按原 actionRequestId 执行，网络超时先对账而不是换 ID 重试。

## 3. 第三主线：Unified Agent Workbench

目标：理解单 Agent 上方为什么还需要产品控制面。

第一遍只看以下文件：

1. `workbench/model/AgentConversationTurn.java`；
2. `workbench/model/AgentWorkItem.java`；
3. `workbench/web/UnifiedWorkController.java` 的 `submitBlocking`；
4. `workbench/application/UnifiedWorkIntakeService.java` 的 `accept`；
5. `workbench/application/DefaultWorkCommandClassifier.java`；
6. `workbench/application/RoutingCoordinator.java`；
7. `workbench/application/LlmUnifiedTaskRouter.java`；
8. `workbench/application/RoutePolicyValidator.java`；
9. `workbench/dispatch/DispatchCoordinator.java`；
10. `workbench/dispatch/GeneralAgentExecutionAdapter.java`。

主链：

```text
用户输入先落库
→ WorkCommand 还是新目标
→ 新目标创建 WorkItem
→ Router 建议 + Java 校验
→ 必要时 Preview/Confirm
→ DispatchAdapter
→ WorkLink 关联底层 Run/Incident/Plan
```

Workbench 面试只需先讲四个工程点：

- `Idempotency-Key` 防止重复输入和 WorkItem；
- `dispatchRequestId` 防止重复创建底层执行；
- `WorkControlState / WorkExecutionState / WorkOutcome` 分离控制、执行和结果；
- Projector 将底层终态幂等收敛回 WorkItem。

第二遍再看 `JdbcWorkbenchStore`、Routing/Dispatch Reconciler、Projection Cursor、lease/fencing。

## 4. 第四主线：Incident Multi-Agent

目标：说明它不是“多个模型自由聊天”，而是受控任务和证据协议。

阅读顺序：

1. `ordercare/incident/application/IncidentInvestigationOrchestrator.java`；
2. `ordercare/incident/application/IncidentExecutionProfileFactory.java`；
3. `ordercare/incident/tool/IncidentToolCatalog.java`；
4. `ordercare/incident/tool/IncidentSubAgentToolHandler.java`；
5. `ordercare/incident/application/IncidentSubAgentTaskService.java`；
6. `ordercare/incident/application/IncidentTaskScheduler.java`；
7. `ordercare/incident/application/IncidentReviewerAgentService.java`；
8. `ordercare/incident/application/IncidentAssessmentAssembler.java`。

主链：

```text
Commander Run
→ delegate_order_analyst / delegate_inventory_analyst / delegate_mq_analyst
→ 独立 Specialist child Run
→ 结构化 Evidence
→ Conflict Checker
→ review_incident_evidence
→ Reviewer Draft
→ Java Assessment Assembler
```

重点：SubAgent Tool 必须只读、低风险、parallelSafe、singleUse；Reviewer 必须引用 Evidence/Conflict；Java 校验角色和证据覆盖；Phase 3 使用 Task lease/fencing 接管，不建设通用 Mailbox。

## 5. 第五主线：Incident Scope Discovery 与 FlowOrder 恢复

目标：把 Agent 基础设施连接到真实业务价值。

### Scope Discovery

阅读：

- `workbench/application/DefaultIncidentScopeRoutePreflight.java`；
- `ordercare/incident/scope/application/IncidentTimeRangeResolver.java`；
- `ordercare/incident/scope/application/IncidentScopeDiscoveryCoordinator.java`；
- `ordercare/incident/scope/persistence/JdbcIncidentScopeDiscoveryStore.java`；
- `ordercare/incident/scope/client/FlowOrderScopeDiscoveryClient.java`。

重点：模型理解现象，但不能猜内部 ID；Java 调用固定只读接口发现范围；Snapshot 绑定 version/fingerprint/TTL；用户显式确认后才进入已有 Incident Adapter。

当前时间白名单是 `前天`、`昨晚`、`今天`、1～24 小时相对范围和 ISO 范围，不要表述为任意时间理解。

### 受控恢复

阅读：

- `ordercare/tool/OrderCareRecoveryToolHandler.java`；
- `ordercare/tool/OrderCareApprovalRequestPreparer.java`；
- `ordercare/application/RecoveryConvergenceChecker.java`；
- `ordercare/application/RecoveryOutcomeReconciler.java`；
- `ordercare/tool/OrderCareUncertainExecutionResolver.java`。

重点区分：

```text
ProposalStatus：预演/审批对象状态
ActionStatus：命令提交状态
CaseOutcome：最终业务结果
```

`SUBMITTED` 不等于 `RESOLVED`。

## 6. 第六主线：事件、SSE 与前端

只有需要讲可观测性时再深入：

1. `stream/DefaultStreamingAgentExecutor.java`；
2. `workbench/application/UnifiedWorkEventProjector.java`；
3. `workbench/web/UnifiedWorkEventStreamService.java`；
4. `workbench/presentation/PublicPresentationService.java`；
5. `frontend/src/composables/useWorkbenchConversation.ts`；
6. `frontend/src/components/ConversationItemRenderer.vue`；
7. `frontend/src/components/ExecutionInspector.vue`。

必须区分：

- Runtime Event：Run 内部事实；
- WorkEvent：统一任务投影；
- PublicPresentation：用户可读安全契约；
- `MODEL_DELTA`：Primary Run 实时正文；
- heartbeat：传输层保活，不是持久化业务事件。

## 7. 可以暂时不看的内容

- 每条 JDBC SQL 和全部 DDL；
- 所有 Controller 运维接口；
- 前端 CSS；
- 每个 Eval case；
- 每个历史 Gap Matrix；
- 未使用场景的全部 DTO。

## 8. 面试学习验收

每个主题只准备四项：

1. 一张主流程图；
2. 三个关键类；
3. 两个故障场景；
4. 一段 1～2 分钟回答。

建议掌握比例：单 Agent 80%，工具安全 70%，Workbench 50%，Multi-Agent 50%，FlowOrder 业务闭环 70%，前端与全部 SQL 20%。
