# Unified Agent Workbench M3-A Evidence

更新时间：2026-07-20 CST

## 结论

M3-A WorkCommand 多实例与跨执行器强化：**PASSED**。

统一工作台的自然语言命令和显式按钮命令现在都会先持久化 `agent_work_input`，复用唯一 EFFECTIVE `agent_work_command_decision`，再进入同一个 `WorkCommandHandler`。General/OrderCare 只适配已有 `AgentRuntime`；Incident Investigation/Recovery Plan 未获得虚构的暂停、恢复、取消或广播输入能力。

## 实现范围

### 固定能力矩阵

`ExecutionCommandCapabilityRegistry` 以 Java 代码固定四类 Target 的命令能力：

- General/OrderCare：PAUSE、RESUME、CANCEL 使用既有 Runtime；
- 所有 Target：ADD_INPUT 仍不支持；
- Incident/Recovery Plan：PAUSE、RESUME、CANCEL 仍不支持；
- 所有 Target：ABANDON 仅改变产品关注，`underlyingExecutionStopped=false`。

模型只能分类 WorkCommand，不能提升目标能力。

### 多实例命令事实

新增 `agent_work_command_execution`：

- `input_id` 唯一，稳定形成一个 `commandRequestId`；
- 同一 WorkItem 同时最多一个 `EXECUTING` 命令；
- `lease_owner + lease_until + claim_token` 支持跨实例防重、过期接管和 fencing；
- claim 时校验 WorkItem version 和 EFFECTIVE command decision；
- 相同 input 重试直接返回原持久化结果，即使 Conversation Focus 已切换；
- stale version 在调用 Runtime 前失败。

### 权威状态顺序

```text
Input + EFFECTIVE Command Decision
→ command claim（不改 WorkItem 执行投影）
→ 校验 Capability 和权威 Run 状态
→ 调用 AgentRuntime.pause/resume/cancel
→ 重新读取 AgentRunRecord
→ 同一 PostgreSQL 事务写 command result + WorkItem CAS 投影 + WorkEvent
```

因此不会只改 `WorkExecutionState` 来伪装底层命令成功。Resume 始终使用 WorkLink 绑定的原 `runId`。

### 事务与事件

命令 claim 与 `WORK_COMMAND_REQUESTED` 在同一事务提交。命令完成时，以下内容在同一事务提交或整体回滚：

- command execution 终态；
- WorkItem 三维状态迁移；
- WorkItem version CAS；
- `WORK_ITEM_PAUSE_REQUESTED/PAUSED/RESUMED/CANCEL_REQUESTED/CANCELLED` 或拒绝/失败事件；
- WorkEvent sequence 分配。

## 自动化证据

### M3-A 单元与 Runtime 回归

```powershell
mvn.cmd -q "-Dtest=DefaultAgentRuntimeStateTests,ExecutionCommandCapabilityRegistryTests,AgentRunWorkCommandAdapterTests,UnifiedWorkControllerTests" test
```

- Runtime pause/resume/checkpoint 回归：12/12；
- Capability Matrix：1/1；
- Run command adapter：2/2；
- Unified Controller：5/5；
- failure/error：0/0。

### PostgreSQL 门禁

```powershell
$env:WORKBENCH_POSTGRES_IT = "true"
mvn.cmd -q "-DargLine=-Djdk.net.URLClassPath.disableClassPathURLCheck=true" "-Dtest=*PostgresIT" test
```

- PostgreSQL 报告：63 tests；
- 实际执行：51；
- 既有外部依赖跳过：12；
- failure/error：0/0；
- M3-A `WorkCommandHandlerPostgresIT`：8/8。

M3-A 八条场景：

1. Incident PAUSE 返回 `UNSUPPORTED_FOR_TARGET`，底层和 WorkItem 执行状态不变；
2. 相同 ABANDON input 重试只产生一个 command execution 和一次 version 推进；
3. stale WorkItem version 在 Runtime 调用前失败；
4. 多个后台 WorkItem 并存时只作用于 Focus；
5. 两个 Store 实例不能同时持有同一命令；
6. Resume 保持原 runId，并按权威 Run 结果投影终态。
7. 尚处于 ROUTING、没有 ExecutionTarget 的 WorkItem 仍可执行产品级 ABANDON。
8. Focus 缺失的结构化结果被持久化并可幂等重放，不创建伪 WorkItem。

### 全量后端

```powershell
mvn.cmd -q clean "-DargLine=-Djdk.net.URLClassPath.disableClassPathURLCheck=true" test
```

- 183 tests；
- failure/error：0/0；
- 既有环境 E2E skipped：11；
- 实际执行：172。

Surefire 在 Windows 上会输出 Manifest Classpath 根路径提示，因此门禁以生成的 XML 报告计数，不以进程退出码单独作为证据。

### 前端

```powershell
cd frontend
npm.cmd run build
```

- `vue-tsc -b`：通过；
- Vite production build：通过；
- unified submit 类型已覆盖 Normal Goal 和 WorkCommand 两种结构化结果。

## 未修改范围

- 未修改 `DefaultAgentRuntime.run()`；
- 未增加 Incident/Recovery Plan 级命令服务；
- 未开放 General/OrderCare 通用 ADD_INPUT；
- 未修改四类 ExecutionTarget；
- 未执行 push。

## 下一门禁

M3-B：Router / WorkItem / Run / Incident 分层预算与 fail-closed 预算门禁。
