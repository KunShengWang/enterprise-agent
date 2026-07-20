# Unified Agent Workbench V1 — M2-A WorkEvent Projector Evidence

> 日期：2026-07-20 CST  
> 蓝图：V0.2.3 / FINAL  
> 前置门禁：M1-E PASSED（`2c5701e`）

## 1. 交付结论

M2-A 已完成 Runtime、Incident 和 Recovery Plan 到 `agent_work_event` 的持久化跨源投影。Projector 只接受 `agent_work_link` 已绑定的来源，保留来源序号、来源时间、投影时间和因果字段，并继续使用 M1 冻结的 `next_event_sequence` 行锁协议分配产品投影顺序。

本阶段没有实现统一 SSE、afterSequence 网络重放、MODEL_DELTA 实时双通道或聊天内 Multi-Agent 执行树；这些边界分别属于 M2-B/M2-C。

## 2. 三类权威来源

### 2.1 Runtime

- 来源表：`agent_runtime_event`；
- 来源坐标：`runId + eventId + eventSequence + createdAt`；
- 投影类型：`RUN_EVENT_PROJECTED`；
- `MODEL_DELTA` 和 `HEARTBEAT` 只推进 projector cursor，不复制到 WorkEvent；
- 模型、工具、审批和 Run 生命周期事件保留结构化 payload。

### 2.2 Incident

- 来源表：`agent_task_event`；
- 来源坐标：`incidentId + eventId + eventSequence + createdAt`；
- 投影类型：`INCIDENT_EVENT_PROJECTED`；
- 保留 taskId、childRunId、actor、sender/recipient role、messageDepth、correlationId 和 causationId。

### 2.3 Recovery Plan

代码审计发现既有 `RECOVERY_PLAN_CHANGED` 是 best-effort Incident/SSE 投影，失败不会回滚权威 Plan JSON，因此不能作为 M2-A 的权威来源。

M2-A 新增 `agent_incident_recovery_plan_event`：

- `create/update/fenced update` 与版本快照事件使用同一个 PostgreSQL 本地事务；
- `event_sequence = plan.version`；
- 唯一键为 `(plan_id, event_sequence)`；
- 既有 Plan 在建表时以当前版本生成兼容性 snapshot；
- 事件写入失败时 Plan 状态更新整体回滚。

## 3. WorkEvent 事务协议

新增内部 `WorkEventProjectionStore`，不暴露为 HTTP/Capability。一次投影按以下顺序执行：

```text
锁定 agent_work_item 行
→ 验证 WorkLink(sourceType, sourceId)
→ 检查 (workItemId, sourceType, sourceId, sourceEventId) 幂等键
→ 使用 next_event_sequence 写 agent_work_event
→ 成功插入才递增 next_event_sequence
→ 同事务推进 agent_work_projection_cursor
→ commit
```

重复事件只更新 cursor，不消耗新产品 sequence。并发 projector 依靠 WorkItem 行锁和数据库唯一键得到唯一、连续的产品顺序；本阶段没有实现或宣称 projector claim/lease、续租、stale owner 接管。

统一 `sequence` 仍只表示 WorkEvent 成功提交顺序，不代表三个 Store 的分布式真实发生顺序；真实分析必须同时参考 `sourceSequence/sourceCreatedAt/projectedAt/correlationId/causationId`。

## 4. 调度与失败隔离

新增默认关闭的配置：

```powershell
$env:WORKBENCH_PROJECTION_ENABLED = 'true'
$env:WORKBENCH_PROJECTION_SCAN_DELAY_MILLIS = '2000'
$env:WORKBENCH_PROJECTION_SOURCE_BATCH_SIZE = '200'
$env:WORKBENCH_PROJECTION_EVENT_BATCH_SIZE = '500'
```

Projector 按 cursor 最久未扫描来源优先，避免固定前 N 个 Link 长期饥饿。单来源读取或写入失败只记录“时间线同步延迟”日志并继续其他来源，不修改 WorkItem、Run、Incident 或 Recovery Plan 的业务状态。

## 5. 自动化证据

### 5.1 Projector 单元

```powershell
mvn.cmd '-Dtest=UnifiedWorkEventProjectorTests' test
```

结果：2/2。覆盖三来源映射、MODEL_DELTA 排除和单来源失败隔离。

### 5.2 真实 PostgreSQL

```powershell
$env:WORKBENCH_POSTGRES_IT = 'true'
$env:INCIDENT_POSTGRES_IT = 'true'
$env:AGENT_STORAGE_POSTGRES_URL = 'jdbc:postgresql://127.0.0.1:5432/enterprise_agent'
$env:AGENT_STORAGE_POSTGRES_USERNAME = 'postgres'
$env:AGENT_STORAGE_POSTGRES_PASSWORD = '1234'
mvn.cmd '-Dtest=*PostgresIT' test
```

结果：52 tests，0 failures，0 errors，0 skipped。其中 M2-A 专项证明：

- 同一来源事件重放 10 次只生成一个 WorkEvent；
- 未绑定 WorkLink 的 sourceId 被拒绝且不产生事件；
- RUN/INCIDENT/RECOVERY_PLAN 三来源 30 个事件并发提交，产品 sequence 从 0 到 30 连续且唯一；
- 实际 Runtime/Incident/Recovery Store 事件被同一 Projector 投影；
- MODEL_DELTA 不进入 WorkEvent；
- Projector 重放 10 次后事件数不变；
- Recovery Plan 版本 0/1 产生对应权威事件；
- 注入下一版本事件冲突时，Plan 更新回滚到原版本。

### 5.3 全量与前端

```powershell
mvn.cmd clean test
cd frontend
npm.cmd run build
```

结果：后端 168 tests，0 failures，0 errors，11 skipped；11 条均为既有显式外部环境门禁。前端 `vue-tsc -b` 与 Vite production build 通过。

## 6. Schema 兼容性

- `agent_work_event`：未删除、重命名或重解释任何列/唯一键；
- `agent_work_item.next_event_sequence`：起点和语义未改变；
- 新增表：`agent_work_projection_cursor`、`agent_incident_recovery_plan_event`；
- 新增 WorkEvent 类型：RUN/INCIDENT/RECOVERY_PLAN projected；
- M1 本地 WORK_ITEM 事件继续使用原 append 路径；
- 默认 Feature Flag 关闭，不改变现有部署行为。

## 7. 范围审计

- `DefaultAgentRuntime.run()`：未修改；
- ExecutionTarget、Adapter、Controller、前端页面：未新增或修改；
- M2-B SSE/Replay：未开始；
- M2-C Multi-Agent 执行树：未开始；
- M3 projector lease/崩溃接管：未提前实现或宣称；
- `git diff --check`：通过；
- push：未执行。

最终判定：`M2-A PASSED`。Checkpoint 为本报告所在的本地提交。
