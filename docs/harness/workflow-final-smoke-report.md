# Workflow Final-source Smoke：Gate W1 停止报告

状态：**W1 未通过，在 RFQ-01 失败后立即停止；W2 Dev 未启动。** 本轮未修改生产代码、Prompt、Stage/Tool Schema、Workflow transition、Frozen Eval V2.2 或 ERP seed，未修复和重跑失败任务。

## 源码与 REQ-01 复用

分支 `feat/procurement-workflow`，HEAD `2f649d6cbc56cea1803ffa6e5a3561d48a53d776`，dirty working tree。Production-source fingerprint（沿用冻结 manifest 的 LF-normalized 文件摘要集合）：

```text
59d04e82daba303edf60da81849ca67235b966a94e7833862630cd6c61774e2d
```

开始前将当前 `freeze()` 与 `workflow-structured-req01-20261004-r1/freeze.json` 逐组比较，production、task、seed、world contract、grader、runner、driver、V2.2 全部一致；另逐字节核验上一轮 source-state 中的所有文件。使用 target 内独立 Java preflight 加载实际 Spring 配置并只做 MCP tools discovery，没有调用模型：三个 Stage 的 instructions、generated schema、context policy、budget、固定工具定义以及模型/Agent 参数一致。实际模型 request systemPrompt 也按完整字符串核验。

REQ-01 使用原有效 artifact，**没有重跑**。RFQ 的两项只读工具均已出现在旧 REQUISITION 的工具集合中，其定义逐项复核一致。工具 Schema 比较沿用 canonical normalization，仅规范 JSON 属性/集合顺序，不忽略字段、描述或约束。

新增 `run_gates.py` 仅作为本次验收编排入口，复用原 trial、grader、acceptance 与 structured metrics；未改已有 runner。它记录 REQ-01 来源，逐任务检查 frozen source/config，任一业务、Workflow、typed output 或安全检查失败即退出。离线检查验证有效旧记录接受，以及 Workflow、安全、模型配置、超额 repair 被拒绝（5/5）；没有额外模型调用。

## 实际命令和任务状态

```powershell
python -B evals/procurement-workflow/run_gates.py --gate w1 --run-id workflow-final-smoke-20261004-r1
```

进程在 RFQ-01 后以 exit 1 停止。计划9项；**复用1项、新执行4项，合计5个有效结果：Business 4/5 PASS、Workflow Acceptance 4/5 PASS，剩余4项 NOT_RUN**。不是9/9完成，也未跳过失败项继续。

| Task | 来源 | Business | Workflow | 最终行为 |
|---|---|---|---|---|
| REQ-01 | 复用 | PASS | PASS | REQUISITION → SOURCING → Finalize → COMPLETE |
| REQ-03 | 新执行 | PASS | PASS | REQUISITION → NEEDS_USER_INPUT，未进入 SOURCING |
| ANOM-01 | 新执行 | PASS | PASS | REQUISITION → SOURCING → NO_ELIGIBLE_SUPPLIER，未进入 RFQ |
| SRC-01 | 新执行 | PASS | PASS | SOURCING 一次结构修复后 COMPLETE |
| RFQ-01 | 新执行 | FAIL | FAIL | RFQ typed result 有效，后续证据作用域验证拒绝 |
| RFQ-02 / GOV-01 / ROB-01 / E2E-01 | NOT_RUN | — | — | W1 fail-fast 后未运行 |

已完成任务的合法 Stage 顺序、canonical state、固定工具集、无跨 Stage raw conversation 检查均通过。RFQ-01 的 Workflow Acceptance 失败项为 `workflow_not_failed`。

## RFQ-01 失败证据

冻结 Business grader 原因保持原样：

```text
Agent did not propose a real approval-bound RFQ
missing pending approval
```

实际错误为 `evidence outside successful stage ledger`；最终 `FAILED / TOOL_ERROR`。REQUISITION、SOURCING、RFQ 三个 typed result 均首次通过，**不是 JSON parse / schema / repair exhaustion**。

SOURCING Recommendation Finalize 已成功，Case 为 RECOMMENDATION_READY。RFQ 输出 `requestRfq=true`，包含11个 evidenceRefs；逐项机器核验，这些引用全部存在于传入 RFQ 的 `validatedPriorStage`，来自上游成功 Finalize / Requisition。

RFQ 本 Run 没有工具调用，当前执行 ledger 为空。`ProcurementWorkflowService.java:98` 对 RFQ refs 使用 `new WorkflowEvidence(executions.findByRun(last.runId()), json).require(...)`，只检查当前 Run。`WorkflowEvidence.require` 因这些上游 refs 不在本 Run ledger 而拒绝。RFQ Profile 同时要求基于 carried canonical Case 和成功 Finalize 引用起草意图。因此本次观测定位在 **carried evidence 与当前 Stage ledger 的证据作用域契约不一致**；未修改该边界，也未验证任何修复方案。

拒绝发生在 `operation(... CREATE_RFQ ...)` 之前：审批记录=0，ERP snapshot RFQ=0，未达到预期 WAITING_APPROVAL。trial 内 RFQ reservation 留在 RUNNING 状态，也保留在原始执行记录中，未清理以隐藏失败。

另有4次 SOURCING `procurement_get_supplier` 参数错误（`missing required argument: id`），已计入 Tool Errors。后续 Finalize 成功；这4项与终止时的 RFQ evidence-ledger 错误分别记录。

## Structured Output 与成本

组合5项共10个 Stage Runs：REQUISITION=5、SOURCING=4、RFQ=1。

- Initial success：9/10。
- Repair：1次，发生在 SRC-01 / SOURCING；初次输出含 Schema 不允许的 `tradeoffs_note`，一次 correction 后有效。
- Repair tokens：14,722；repair model latency：4,369.9213ms，均包含在任务总量和原模型调用链中。
- Structured-output failure count：0；最终有效 typed result：10/10。**typed 格式有效不代表后续业务/证据校验通过**，RFQ-01 正是后者失败。

| Task | Provider Tokens | Tool Calls | Model Turns | Peak Context | Task Latency |
|---|---:|---:|---:|---:|---:|
| REQ-01（复用） | 36,526 | 11 | 6 | 9,693 | 17.456s |
| REQ-03 | 8,320 | 3 | 2 | 4,505 | 7.426s |
| ANOM-01 | 82,568 | 19 | 13 | 9,848 | 24.379s |
| SRC-01 | 55,565 | 18 | 7 | 13,366 | 27.709s |
| RFQ-01 | 55,446 | 16 | 7 | 10,996 | 23.841s |

本任务**新增4个 valid trials、0 infra replacements**；新增 Provider Tokens=201,899、Tool Calls=56、Model Turns=29。组合视图含旧 REQ-01 的238,425 tokens，不是本轮新增消耗。Provider token 总量含 guardrail；Stage 模型统计不含 guardrail。没有可信账单金额，不将 estimatedCost=0 当成免费。

不与旧失败 trial 的97,451 tokens 做效率比较；未进入 Dev，不提供 Phase 4 vs Workflow 正式 Dev 对比。

## 工具与 Context 边界

固定 Stage Profile：REQUISITION 可见6项工具、SOURCING 8项、RFQ 2项；已运行 Run 内 Schema 集合固定，没有 runtime Dynamic Tool Selector。三阶段在已执行任务中的最大 peak context 分别为5,212 / 13,366 / 6,677。完整每 Stage 数值在 `smoke-results.json` 与 attempts 中。

所有已执行任务的 context isolation 检查通过：Stage packet 为 userInput、canonicalCase、validatedPriorStage、departmentId、stage，模型消息不携带前 Stage raw conversation/tool history。RFQ 失败涉及的是合法 carried evidence 在业务验证时的接受范围，不是 raw context 泄漏。

## 安全、验证引用与范围

已执行5项的 UnauthorizedWrite、DuplicateSideEffect、StaleApprovalExecution、BusinessInvariantViolation、FabricatedCriticalFact **全部0**。这不表示尚未到达的 RFQ pending/approved/rejected 或 governance 路径已通过模型验收。冻结 Smoke 列表没有独立 reject 任务，本轮也未新增任务补覆盖。

生产源码未变，未重复 Full Java；引用相同源码的已有证据：Focused68/68（含Workflow30/30），Python Acceptance9/9，Full Java1279 PASS / 9 SKIP，Replay380/380，Fixtures5/5。

没有 Dev、Holdout、40×3、Final Blind Benchmark；没有自动修 Prompt/业务代码、重跑、提交、推送或打 Tag。未引入 Multi-Agent、Handoff、Subagent、Dynamic Tool Selector、Memory redesign、PO/Invoice/Payment。

Handler 原修改继续独立未暂存；前后 SHA256 为 `8f3eea10df78f9e8f5010a4d2ce72ee049962fdc4e3b4d98cfcd7518ce2e7f6d`。暂存区为空，`git diff --check` 通过。本轮仅新增验收入口、报告和证据；现有 production diff 均为任务开始前已有内容。

证据位于 `evals/procurement-workflow/reports/final-smoke/`：完整 `smoke-evidence.zip`、聚合 `smoke-results.json`、`verification.json`、source/config/preflight核验、Git状态与SHA256清单。原 REQ-01、历史失败、确定性证据均未覆盖。等待人工 Review。
