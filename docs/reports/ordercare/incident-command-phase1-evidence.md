# OrderCare Incident Command Phase 1 实施与证据报告

> 场景：`ordercare-incident-command-v1`
>
> 范围：M1-A ～ M1-E
>
> 结论：`PHASE_1_IMPLEMENTED`
>
> 日期：2026-07-18

## 已完成的业务闭环

```text
有界告警范围
→ Commander 生成结构化只读分工
→ Java 校验并创建最多三个 Specialist Task
→ Specialist 独立 child Run 并行采集订单/库存/死信/MQ 事实
→ ToolExecution 投影为 append-only FACT Evidence
→ Java EvidenceComparisonRule 做跨 subtype 冲突检查
→ Reviewer 基于有效 evidenceId/conflictId 评估，最多一次定向补证
→ Java Assembler 决定 ASSESSED/PARTIAL/MANUAL_REVIEW
→ 单窗口通过 SSE 展示 Task、Run、Evidence、Conflict、Assessment 和 Trace
```

Phase 1 全程只读。模型不能提交 requestId 列表、队列名、URL、SQL 或写动作；工具只接受 `snapshotId`，服务端从 Incident Store 恢复冻结范围。

## 工程边界

- `agent_incident` 是协调根，不创建无模型 coordinator Run；Trace 使用 synthetic coordinator span，并明确排除模型指标。
- Commander、每个 Specialist 和 Reviewer 都是独立真实 Run，分别统计 Prompt、Token、结果和失败。
- Task version CAS、Evidence append、`EVIDENCE_SUBMITTED`、eventSequence 和 Task 状态推进处于同一个 PostgreSQL 事务。
- 并行 Specialist 事务统一按 `agent_incident -> agent_task` 顺序加锁，避免 eventSequence 与 Task/FK 锁形成死锁。
- Phase 1 只有单实例防重和一次有界重试；没有实现或宣称多实例 claim/lease、崩溃接管。
- RabbitMQ Management 只生成队列级运行态信号，不与订单、扣减或死信业务数量做等值比较。
- `floworder_incident_mq_facts` 固定先读取持久化死信，再观察 Broker；Broker 超时时保留 dead-letter FACT 并记录 `BROKER_TIMEOUT`。

## 三条纵向 E2E

测试类：

```text
IncidentCommandRuntimeE2ETests
```

它使用真实 PostgreSQL、真实 Agent Runtime、Tool Runtime、Task/Event Store、事务提交和 Trace 投影；外部模型、FlowOrder HTTP 和 Rabbit Management 使用确定性契约 Stub，避免把网络和模型随机性混入编排正确性门禁。

| 场景 | 断言 |
|---|---|
| `HAPPY_CONSISTENT` | 订单/未释放扣减/死信集合一致，得到 `ASSESSED`，包含队列 FACT，无 Java 冲突 |
| `CONFLICT_126_100_93` | 126 条物理死信、100 个业务键、93 个未释放扣减，产生显式 `COUNT_MISMATCH`，得到 `MANUAL_REVIEW` |
| `MQ_TIMEOUT_PARTIAL` | Rabbit Management 超时，仍保存 `DEAD_LETTER_SET`，不伪造 `QUEUE_RUNTIME_STATUS`，Assessment 含 `BROKER_TIMEOUT` |

结果：

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

## 十条核心 Eval

测试类：

```text
IncidentCommandCoreEvalTests
```

覆盖角色数量、角色唯一性、写意图拒绝、EvidenceSubtype 越权、126/100/93 比较、禁止队列数跨域比较、scopeHash 不可比较、MQ partial、无效 Evidence 引用、OPEN HIGH conflict 强制人工复核。

```text
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
```

## 真实 Fixture 证据

FlowOrder 提供三套 SQL Fixture 和统一 PowerShell 脚本：

```text
floworder/scripts/ordercare/incident-command/
```

脚本会：

1. 清理固定业务键前缀；
2. 在真实 MySQL 构造订单、扣减和重复死信；
3. 创建专用 `floworder.incident.e2e.dlq` 队列；
4. 通过 RabbitMQ Management 向专用队列发布 3 或 126 条消息；
5. 校验 record/distinct/duplicate/MQ messages 口径；
6. 删除专用队列并清理 SQL 数据，不触碰现有业务队列。

已实际验证：

```text
CONFLICT_126_100_93:
terminalOrderCount=100
unreleasedDeductCount=93
deadLetterRecordCount=126
distinctBizKeyCount=100
duplicateRecordCount=26
Rabbit messages=126
```

## 最终回归

```text
enterprise-agent: Tests run: 113, Failures: 0, Errors: 0, Skipped: 10
FlowOrder resource-service: Tests run: 85, Failures: 0, Errors: 0, Skipped: 2
FlowOrder order-service: Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
PostgreSQL M1-B + M1-C + M1-E: Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
frontend: vue-tsc + vite build passed
```

## 演示命令

启用后端场景：

```powershell
$env:ORDERCARE_INCIDENT_COMMAND_ENABLED = "true"
```

构造并发起一致场景：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts\ordercare\incident-command\incident-command-fixture.ps1 `
  -Action Scenario -Scenario HappyConsistent -DbPassword 1234
```

Rabbit Management 超时场景需先用不可达的 Management 地址启动 enterprise-agent；这只故障化 Broker 观察，不影响 FlowOrder 持久化死信读取：

```powershell
$env:RABBITMQ_MANAGEMENT_BASE_URL = "http://127.0.0.1:1"
```

## 可用于简历的保守表述

> 实现面向异常订单事故的只读 Multi-Agent 调查系统：由 Commander 动态委派受限 Specialist 并行采集订单、库存、死信和 MQ 证据，Java 负责持久化任务状态、预算、显式跨域冲突检测和一次定向补证，最终形成带 Evidence/Conflict 引用的可追溯事故评估报告。

当前不能宣称多实例租约接管、生产级 SLO、自动批量恢复或通用 Agent 消息总线。

