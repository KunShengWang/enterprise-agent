# 文档索引与事实源规则

> 当前实现基线：`b6207a48d6db295e6d7c6e8c054c3d1df5d952db`
>
> 核对日期：2026-08-10（Asia/Shanghai）

本页用于解决“设计文档、阶段报告和当前代码互相覆盖”的问题。阅读或更新文档时，必须先判断文档属于哪一类。

## 1. 事实源优先级

发生冲突时按以下顺序判断：

1. 当前已提交代码、配置和自动化测试；
2. 当前状态文档；
3. 已冻结设计蓝图；
4. 历史阶段 Evidence / Gap Matrix；
5. 早期设计记录和未来 Backlog。

`docs/reports/**` 是某个 checkpoint 当时的证据，不是滚动更新的产品说明。报告中的“未开始”“禁止进入下一阶段”“测试数量”和端口，只描述报告生成时的状态，不应覆盖后续实现。

工作区未提交代码不进入稳定能力声明。本文核对时存在 Runtime、Incident、Workbench 等未提交 Java 修改；它们必须在独立验收和提交后，才能写入“已实现”。

## 2. 当前文档入口

| 目的 | 文档 | 性质 |
|---|---|---|
| 快速了解项目 | [README](../README.md) | 当前状态 |
| 看系统分层与主链 | [当前架构](architecture.md) | 当前状态 |
| 本地启动与环境变量 | [构建与运行](build-and-run.md) | 当前状态 |
| 调用后端接口 | [API 使用指南](api-guide.md) | 当前状态 |
| 学习与面试准备 | [学习顺序](learning-guide.md) / [面试讲法](interview-guide.md) | 当前状态 |
| 使用统一前端 | [Unified Agent Workbench](frontend-learning-console.md) | 当前状态 |
| 查看真实边界 | [仍然存在的边界](remaining-gaps.md) | 当前状态 |
| OrderCare 总体设计 | [Enterprise Agent Master Blueprint](enterprise-agent-master-blueprint.md) | 冻结设计 + 实现后补充 |
| Unified Workbench 设计 | [Unified Agent Workbench V1](unified-agent-workbench-v1-design.md) | 冻结设计 |
| Incident Command 设计 | [OrderCare Incident Command V1](ordercare-incident-command-v1-design.md) | 冻结设计 |
| 查看里程碑证据 | [reports](reports/) | 历史证据 |

## 3. 当前已提交能力快照

### Runtime

- 一个持久化、可暂停/恢复的 `DefaultAgentRuntime` Model–Tool Loop；
- Session lease、Checkpoint、预算、Guardrail、HITL、ToolExecutionClaim 和 UNKNOWN 对账；
- Provider 原生流式正文和原生 `tools/tool_calls`，默认使用 `NativeToolCallingAgentModelGateway`；
- `AGENT_MODEL_TOOL_CALLING_MODE=json` 仅保留为兼容模式；
- ToolCall 仍由 Runtime 执行权限、阶段可见性、参数约束、审批和幂等门禁，Gateway 不执行工具。

### Unified Agent Workbench

- 用户输入先持久化为 `AgentConversationTurn`，再区分 WorkCommand 与新目标；
- `AgentWorkItem` 使用 `WorkControlState / WorkExecutionState / WorkOutcome` 三维状态；
- 四个稳定执行目标：`GENERAL_AGENT`、`ORDERCARE_CASE`、`INCIDENT_INVESTIGATION`、`INCIDENT_RECOVERY_PLAN`；
- 幂等 Routing/Dispatch、WorkLink、跨源 WorkEvent 投影、统一 SSE/Replay、执行树、分层预算和多实例 claim/lease/fencing；
- `/` 是统一工作台，Run 历史和事故控制台是高级观测入口。

### OrderCare 与 Multi-Agent

- 单案例只读诊断、不可变 Recovery Proposal、版本绑定审批、Action 幂等、收敛检查与 UNKNOWN 对账；
- Incident Command Phase 1～3：Commander、Specialist、Reviewer、Recovery Planner 和多实例可靠性内核；
- Commander 通过受控、只读、低风险、single-use 的 SubAgent Tool 调度领域 Specialist；满足并行安全条件的 SubAgent Tool 可由 Runtime 有界并行执行；
- Reviewer 输出必须引用 Evidence/Conflict，Java Assembler 校验证据覆盖和结构，不接受无引用结论。

### Incident Scope Discovery

- 用户可以只描述受支持的业务现象和时间/业务锚点，不必预先知道 requestId 或 queueName；
- Java 通过 FlowOrder 固定只读接口发现 requestId、orderNo、deductNo、deadLetterId 和权威 queueName；
- Snapshot 使用版本、fingerprint、TTL、确认绑定、claim/lease/fencing；
- 当前自动时间表达仅支持 `前天`、`昨晚`、`今天`、`最近/过去 N 小时（1～24）` 和不超过 24 小时的 ISO `start/end`；其他表达进入澄清，不能宣称理解任意自然语言时间。

## 4. 名称冲突说明

早期 OrderCare 恢复蓝图把“M4”用于“安全与部署硬化”；后续独立产品里程碑又使用“M4 Incident Scope Discovery V1”。两者不是同一个阶段：

- `Incident Scope Discovery M4` 已完成代码与自动化/跨服务证据，人工浏览器截图仍是证据边界；
- 身份认证、服务间正式凭证治理、版本化迁移、生产部署和告警仍属于未完成的部署硬化，不应再简称为“M4”，本文统一称为“Production Hardening”。

## 5. 更新规则

- 功能提交必须同步更新 README、架构、API/运行说明或本索引中受影响的部分；
- Evidence 只追加新报告，不改写旧 checkpoint 的事实；
- 具体测试数字只写在 Evidence；当前状态文档只引用对应报告，避免每次测试增减导致全仓数字漂移；
- 不把未提交代码、Mock 结果或未完成的人工验收写成已完成能力；
- 不把模型 Prompt 约束描述成 Java 强制安全边界。
