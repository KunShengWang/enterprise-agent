# Unified Agent Workbench M3-D Evidence

更新时间：2026-07-20 CST

## 结论

M3-D Eval 与最终证据包门禁：**PASSED**。

Unified Agent Workbench V1 的 M0、M1、M2、M3 已按冻结顺序形成完整纵向证据。系统能够从统一自然语言入口选择 General、OrderCare、Incident Investigation 或 Incident Recovery Plan，并用 Java 门禁控制身份、标识来源、危险确认、预算、幂等派发、多实例租约、事件投影和故障恢复。

## Eval 覆盖

### Workbench 路由

- 80 条真实模型样本：20 条命令、60 条路由、31 条模糊或对抗样本；
- 79/80 通过，总通过率 98.75%；
- command accuracy：100%；
- target accuracy：98.3%；
- disposition accuracy：100%；
- dangerous misroute：0；
- dangerous command misclassification：0；
- wrong focus：0；
- identifier source violation：0；
- hidden target selection：0；
- 模型：`deepseek-v4-flash`；Prompt Token 44,865，Completion Token 9,457，总延迟 131,030 ms。

唯一未通过样本为 `adv-model-invent-id`：模型输出未达到目标准确性，但 Java 将危险标识来源收敛为澄清，没有进入危险派发，因此所有危险指标仍为 0。

### OrderCare 与 Incident

- OrderCare 真实模型：19/20，通过率 95%；
- 工具成功率 100%，Precision 100%，Recall 94.7%，F1 97.3%；
- RAG 使用准确率 100%；
- forbidden side-effect violation：0；
- hallucination risk：0；
- 三条对抗样本 3/3；
- Incident Runtime E2E：4/4。

真实模型门禁发现并修复了三个非玩具问题：Provider 前置说明导致 ToolCall 信封误判、中文审批绕过信号遗漏、Eval 将疑问句误判为副作用声明。未降低通过阈值，也未删除失败样本。

## 故障与数据库证据

- M3-D 确定性 Eval：106/106；
- 故障恢复精选门禁：31/31；
- 全部 PostgreSQL IT：15 个测试类，72/72，0 skipped；
- 覆盖 routing/dispatch/projector claim、lease、heartbeat、fencing、原幂等键 reconciliation、Incident Task 和 Recovery Item 接管；
- 重复副作用断言为 0。

证据脚本会把 `-DbPassword` 同步传播到 Agent Storage、Memory 和 RAG 数据源，避免同一进程中不同数据源使用不一致凭据。Incident E2E 清理顺序先删除 Recovery Plan Event，再删除 Plan，保证可重复执行。

## 全量回归

- 后端：271 tests，0 failures，0 errors，11 个既有环境条件跳过；
- 前端：`vue-tsc -b` 与 Vite production build 通过；
- `git diff --check`：通过；
- `DefaultAgentRuntime.run()`：未修改。

全量测试日志中的 Runtime/Projector 异常栈来自故障注入用例，Maven 最终结果为通过，不是回归失败。

## 可重复命令

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\workbench-m3-d-evidence.ps1 -Action DeterministicEval
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\workbench-m3-d-evidence.ps1 -Action RoutingModel
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\workbench-m3-d-evidence.ps1 -Action FaultRecovery -DbPassword 1234
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\workbench-m3-d-evidence.ps1 -Action BusinessE2E -DbPassword 1234
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\workbench-m3-d-evidence.ps1 -Action Full
```

`RoutingModel` 与 `BusinessE2E` 需要真实模型密钥；数据库门禁需要本机 `enterprise_agent` PostgreSQL。完整演示顺序见 `docs/unified-agent-workbench-interview-runbook.md`。

## 权威边界

- LLM 提供候选理解、诊断解释与建议，不拥有危险派发或业务恢复权威；
- Java 校验身份、来源、风险、确认、预算和状态机；
- PostgreSQL 提供本地事务、CAS、幂等、lease、fencing 与事件顺序事实；
- FlowOrder 拥有订单、库存、Proposal、Action 和业务收敛权威；
- 人工确认 Incident 启动并审批恢复副作用。

## 未完成项与风险

冻结蓝图范围内无未完成里程碑。真实模型仍有非零普通路由误差，且模型输出具有跨次波动，因此危险路径必须继续依赖 Java fail-closed 门禁，不能把 Eval 通过解释为模型确定性。真实 RabbitMQ/FlowOrder 联调仍由各业务演示脚本单独证明，本报告不伪装外部组件证据。

本报告与代码进入同一 M3-D 本地 checkpoint；未执行 push。
