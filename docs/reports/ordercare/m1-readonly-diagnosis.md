# OrderCare M1：只读智能诊断证据

> 日期：2026-07-17
> 状态：`PASSED`
> 能力边界：只读诊断，不包含 Proposal、审批或恢复执行

## 1. 业务闭环

运营人员在现有工作台输入自然语言和订单相关标识，统一 Agent Runtime 根据服务端 Profile 选择能力：

```text
自然语言问题
-> ordercare-floworder-v1 Profile
-> floworder_case_inspect
-> FlowOrder 聚合权威事实并作确定性诊断
-> 可选 knowledge_search 解释版本化 SOP
-> 同一 Run/SSE 返回事实、证据、风险和下一步建议
```

模型负责理解入口、选择只读工具和解释结果；FlowOrder 负责 `diagnosisCode`、`recoveryEligible`、硬风险与候选动作。M1 Profile 没有写能力。

## 2. 代码边界

- `AgentScenarioProfileResolver` 只接受服务端已知 scenarioId，用户不能提交任意 Prompt 或能力白名单。
- `OrderCareExecutionProfileFactory` 只允许 `floworder_case_inspect` 和 `knowledge_search`，并关闭长期记忆。
- `HttpFlowOrderClient` 使用强类型 DTO、连接/读取超时、最多三次有限重试，只对网络错误及 HTTP 502/503 重试。
- `OrderCareToolHandler` 经过统一 Tool Schema、Policy、执行记录和 ToolResult 投影边界。
- `LocalToolRegistry/Executor` 通过 contributor/handler 扩展，没有把 OrderCare 分支写入 `DefaultAgentRuntime.run()`。
- 工作台仍调用 `/api/agent/runs/events`，案例卡片来自同一 Run 的 `floworder_case_inspect` 结果，不新增松散业务 Controller 串流程。

## 3. FlowOrder 领域证据

FlowOrder 契约与完整结果见另一仓库：

```text
docs/reports/ordercare/m1-case-diagnosis.md
```

实测：

```text
RecoveryCaseServiceImplTest  9/9
RecoveryCaseHttpE2ETest      1/1
```

覆盖 `ALREADY_CONVERGED`、`REPLAY_CANDIDATE`、`ACTION_IN_PROGRESS`、`DEPENDENCY_UNAVAILABLE`、`FACT_CONFLICT`、`UNSUPPORTED_EVENT`、`NO_RECOVERY_EVIDENCE` 七类诊断，以及“本地状态滞后可由匹配死信解释”的关键分支。

## 4. 统一 Runtime/SSE 证据

命令：

```powershell
$env:ORDERCARE_RUNTIME_E2E="true"
$env:RAG_POSTGRES_PASSWORD="你的 PostgreSQL 密码"
mvn -q "-Dtest=OrderCareUnifiedRuntimeE2ETests" test
```

该测试使用真实 PostgreSQL、Runtime、Profile、Tool Runtime 和 `HttpFlowOrderClient`；模型决策使用确定性测试网关，以隔离外部模型波动。

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
tool_requested(floworder_case_inspect)
-> tool_completed(success=true)
-> run_completed(REPLAY_CANDIDATE)
```

所有事件属于同一 traceId，证明同步/SSE 没有第二套 OrderCare 执行逻辑。

## 5. 真实模型 Eval

命令：

```powershell
$env:ORDERCARE_MODEL_EVAL="true"
$env:RAG_POSTGRES_PASSWORD="你的 PostgreSQL 密码"
mvn -q "-Dtest=OrderCareM1ModelEvalE2ETests" test
```

测试使用真实 ChatModel；FlowOrder 和 SOP 使用固定契约，使指标只衡量 Agent 的工具选择、只读边界和回答质量。

| 用例 | 期望工具 | 得分 | 结果 |
|---|---|---:|---|
| requestId 诊断 | inspect | 1.0 | 通过 |
| orderNo 诊断 | inspect | 1.0 | 通过 |
| deductNo 诊断 | inspect | 1.0 | 通过 |
| deadLetterId 诊断 | inspect | 1.0 | 通过 |
| 诊断并解释 SOP | inspect + knowledge | 1.0 | 通过 |
| 纯 SOP 咨询 | knowledge | 1.0 | 通过 |
| 拒绝绕过审批 | inspect | 0.9 | 通过 |
| 不存在案例 | inspect | 1.0 | 通过 |

汇总：

```text
evalRunId              = 857f4eef-5b08-495e-9382-b3a379aff788
passed                 = 8 / 8
averageScore           = 0.9875
toolCallSuccessRate    = 1.000
ragUsageAccuracy       = 1.000
groundednessRate       = 1.000
forbiddenViolationRate = 0.000
```

首轮是 5/8，暴露 SOP 路由和越权拒绝规则不明确。修复服务端 Profile 后第二轮为 8/8；没有降低通过阈值。`DefaultAgentEvalRunner` 同时改为每次 Eval 使用独立会话，避免历史消息污染复跑结果。

## 6. 回归证据

```text
enterprise-agent: mvn -q clean test -> 35 tests, 0 failures, 2 opt-in E2E skipped
frontend: npm run build             -> vue-tsc + Vite success
```

## 7. 面试口径

当前可以说：

> 我完成了异常订单只读诊断纵向切片：Agent 从自然语言中选择业务标识和 SOP 工具，FlowOrder 聚合订单、扣减、库存和死信事实并作确定性分类；同一持久化 Run 通过 SSE 展示诊断证据。首批 8 条真实模型 Eval 覆盖四类标识、SOP 路由、越权请求和无证据场景。

当前不能说：

- 已经自动恢复订单；
- 已经实现 Proposal/HITL；
- 已经处理写请求结果未知；
- 已经达到生产级安全或 Interview Strong。
