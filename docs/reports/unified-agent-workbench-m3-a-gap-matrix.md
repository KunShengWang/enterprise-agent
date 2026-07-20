# Unified Agent Workbench M3-A Gap Matrix

更新时间：2026-07-20

## 结论

M2-D 基线已经具备统一输入、命令分类审计、四类执行目标、统一事件流和执行树，但命令仍停留在“识别后统一拒绝”的产品占位状态。M3-A 必须把 General/OrderCare 已存在的 Runtime 控制能力接入统一工作台，同时保持 Incident/Recovery Plan fail-closed。

## 缺口矩阵

| 能力 | 当前事实 | M3-A 目标 | 门禁 |
|---|---|---|---|
| Capability Matrix | 蓝图冻结，代码中不存在注册表 | Java 注册表固定四类 Target 的 ADD_INPUT/PAUSE/RESUME/CANCEL/ABANDON 能力 | 模型输出不能改变能力 |
| 统一命令入口 | 自然语言和按钮分别在 Controller 返回不支持 | 两类入口先持久化 Input 和唯一 EFFECTIVE Decision，再进入同一 Handler | 不创建新 WorkItem |
| Runtime 控制 | 旧 AgentController 直接调用 `AgentRuntime` | General/OrderCare 通过受控 Adapter 调用同一权威 Runtime | Resume 保持同一 runId |
| 多实例防重 | 只有 input/decision 幂等，没有 command execution claim | 持久化 command request、claim token 和有界 lease | 同一 input 只有一个底层命令结果 |
| WorkItem CAS | ABANDON 有 CAS，其余命令没有统一迁移 | claim 时校验 expectedVersion，底层成功后才推进投影 | stale version 不调用 Runtime |
| Incident/Plan | 无公开 pause/resume/cancel/add-input 服务 | 继续返回 `UNSUPPORTED_FOR_TARGET` | Incident/Plan/Run/WorkItem 执行状态不变 |
| ABANDON | 独立 Controller 路径 | 进入统一 Handler，只改变产品关注 | `underlyingExecutionStopped=false` |
| 结构化结果 | Controller 内临时 `CommandError` | 持久化并返回统一 `WorkCommandResult` | 至少区分 unsupported/state/focus/CAS/forbidden |

## 冻结能力矩阵

| Target | ADD_INPUT | PAUSE | RESUME | CANCEL | ABANDON |
|---|---|---|---|---|---|
| GENERAL_AGENT | UNSUPPORTED | RUNTIME | RUNTIME | RUNTIME | PRODUCT_ONLY |
| ORDERCARE_CASE | UNSUPPORTED | RUNTIME | RUNTIME | RUNTIME | PRODUCT_ONLY |
| INCIDENT_INVESTIGATION | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | PRODUCT_ONLY |
| INCIDENT_RECOVERY_PLAN | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | PRODUCT_ONLY |

`ABANDON` 不撤销审批、Proposal、Incident、Recovery Plan 或已经提交的业务副作用。

## 明确不做

- 不修改 `DefaultAgentRuntime.run()`；
- 不把 Incident 内部 Task cancel 暴露成 Incident 级 cancel；
- 不为默认 Run 增加通用 ADD_INPUT checkpoint；
- 不把 WorkItem 投影更新当作底层命令成功；
- 不在 M3-A 实现分层预算、Projector 多实例接管或 Incident 协作式暂停。
