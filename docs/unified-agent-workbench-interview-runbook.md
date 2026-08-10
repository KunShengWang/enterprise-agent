# Unified Agent Workbench V1 面试演示手册

## 1. 启动边界

启动 PostgreSQL、FlowOrder、RabbitMQ 后，在 `enterprise-agent` 进程中启用 Workbench、Incident Command、Recovery Planner 和 Phase 3。生产副作用 kill switch 必须按演示目标显式设置；只读调查不需要打开恢复执行。

统一页面使用 `http://127.0.0.1:5173/`；`/workbench` 和 `/runtime` 会重定向到 `/`。后端默认使用 `8083`。

## 2. 推荐演示顺序

1. 普通知识目标：`解释 Java CAS、ABA 与幂等的关系`，展示 Router 选择 General Agent。
2. 单订单诊断：`诊断 requestId=ORDERCARE-M05-REQUEST`，展示强类型标识与 OrderCare Run。
3. Multi-Agent 调查：优先输入“调查昨晚订单超时但库存未释放的问题，只调查并生成 Assessment，不执行恢复”，展示 Scope Discovery、Preview、显式确认、Commander、Specialist、Reviewer 和 Evidence；也可用明确 requestIds 演示原直达路径。
4. 恢复计划：在已 `ASSESSED` 事故后输入“基于刚才事故生成受控恢复计划”，展示新的子 WorkItem、Proposal 和审批边界。
5. 故障证据：展示相同 dispatchRequestId 不重复创建目标、旧 fencing token 被拒绝、Projector 从 cursor 恢复。
6. 预算证据：展示 WorkItem/Incident 子账户和耗尽后不再创建新 Run。

## 3. 面试责任边界

```text
LLM：理解目标、路由候选、诊断解释、恢复建议
Java：标识来源、权限、风险、确认、预算、冲突、收敛判定
PostgreSQL：幂等、CAS、租约、fencing、事件顺序、故障恢复事实
FlowOrder：订单/库存领域规则、Proposal、Action 与最终业务收敛
Human：Incident 启动确认与高风险恢复审批
```

不要表述为“模型自主修改订单”或“全链路 exactly-once”。准确表述是：危险决策 fail closed，副作用使用稳定业务幂等键，未知结果通过对账收敛。

## 4. 可重复证据命令

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\workbench-m3-d-evidence.ps1 -Action DeterministicEval
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\workbench-m3-d-evidence.ps1 -Action RoutingModel
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\workbench-m3-d-evidence.ps1 -Action FaultRecovery -DbPassword 1234
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\workbench-m3-d-evidence.ps1 -Action BusinessE2E -DbPassword 1234
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\workbench-m3-d-evidence.ps1 -Action Full
```

`RoutingModel` 和 `BusinessE2E` 使用真实模型，必须存在有效模型密钥。`FaultRecovery` 需要真实 PostgreSQL。Incident Runtime E2E 使用真实 PostgreSQL 与 Runtime/Tool/事务，外部 FlowOrder、RabbitMQ Management 和模型由确定性隔离 Stub 替代，不应表述成真实外部中间件 E2E。

## 5. 失败时怎么讲

- Router 选错但 Java 澄清：模型质量问题，安全边界有效。
- Router 输出危险字段被拒绝：策略门禁有效，不进入 Adapter。
- Adapter 返回后进程崩溃：原 dispatchRequestId reconciliation，不创建第二目标。
- Tool 写请求超时：进入 UNKNOWN，使用原 actionRequestId 查询，不换键重试。
- Projector 崩溃：底层任务不受影响，新 owner 从 cursor 补投影。
- 预算耗尽：阻止新模型/工具调用，已提交副作用仍继续对账。
