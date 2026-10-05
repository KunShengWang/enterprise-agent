# Workflow Retry Contract 修复与 ROB-01 单点验收

日期：2026-10-05。分支：`feat/procurement-workflow`。

**确定性 Gate 全部通过；唯一一次 ROB-01 启动尝试在 reset 阶段发生 EVAL_INFRA_ERROR，未进入模型，模型验收未完成。已停止，等待人工 Review。** 未将本轮结果拼接为新 Smoke，也未继续 Dev。

## 1. Phase 4 ROB behavior

审计基线 `procurement-context-v1` → `2f649d6cbc56cea1803ffa6e5a3561d48a53d776`，以及冻结的 `evals/context-engineering/reports/smoke-evidence.zip`、`dev-evidence.zip` 中 ROB-01 raw/result。

| Artifact | Business | Tool Calls | Tool Errors | Runtime retries | 恢复行为 |
|---|---|---:|---:|---:|---|
| Phase 4 Smoke ROB-01 | PASS | 14 | 1 | 0 | 模型第 2 turn 重新调用 item read |
| Phase 4 Dev ROB-01 | PASS | 18 | 1 | 0 | 模型第 2 turn 重新调用 item read |

两次均为模型第 1 turn 的 `get_item(V2-WS-001)` 注入 `ERP_UNAVAILABLE / injected MCP 503`，随后另一 toolCallId、相同参数读取成功。每个 receipt 的 attempts 均为 1，并非同一 invocation 内的 Runtime retry。

Phase 4 Dev 失败 receipt=`f7233a0e-29d9-4557-ad15-ca2212fb0606`，成功 receipt=`5a26aa64-6055-491c-bcf9-0d0e4a521df9`；Smoke 分别为 `5eb34d74-586b-4a42-b8bf-7c91712ba4a8`、`d0de7553-7849-4da0-bb52-eff0866e9e4a`。

结论：**此前依赖隐式 LLM retry，而不是 Workflow 丢失了已稳定工作的 Runtime 自动重试能力。** 同类 fault 注入没有变化。Workflow 新增的阶段结束检查进一步将未恢复的 canonical 数据缺失转换成正常澄清，从而暴露原本存在的分类/重试脱节。

## 2. Current exact failure path

修复前 W1 ROB-01：临时 item read failure → `ToolExecutionOutcome.RETRYABLE_ERROR` → 原 Runtime retryable 方法只检查布尔 retryable 或 errorMessage 关键词 → `injected MCP 503` 不匹配 → attempts=1 → 模型没有重调 → missing canonicalItem → NEEDS_USER_INPUT/COMPLETED → Business FAIL，旧 Workflow Acceptance PASS。

原 failure artifacts 和报告完整保留，没有重评分覆盖。新 acceptance 单独审查旧 raw，`CLARIFICATION_CAUSALITY=false`，结果存于 `old-rob-new-acceptance.json`。

## 3. Retry classification producer

继续使用现有 `ToolExecutionOutcome.from`：结构化 errorType/errorCode/httpStatus、显式 runtime outcome 与 validation/policy metadata 产生现有七种 outcome，没有新建 Outcome Framework。

`ToolExecutionDeadline` 保持 timeout/uncertain-write 分类；只读 timeout 可交给统一策略判断，非只读 timeout 仍为 FATAL/manualReview，禁止盲目重放。`DefaultAgentToolRuntime` 不再将所有 RuntimeException 一概标成 retryable：已知连接/网络超时/SQLTransientException（包含 cause chain）可重试；IllegalArgumentException 为 VALIDATION_ERROR；其他未知异常为 FATAL_ERROR。不新增 Spring DAO 或 resilience 依赖。

## 4. Retry decision consumer / execution point

唯一决策链：`ToolCallResult.outcome()` → 小型 `ToolRetryPolicy.decide` → 原有 `DefaultAgentToolRuntime.executeWithRetry` 循环。AgentRunner 和 Workflow 不建立第二套 retry policy。

Policy 同时消费 classification、既有 `ToolRuntimeContract.Effect`、最大次数和 Run deadline。显式 executor retryable=false 仍可否决重放。VALIDATION_ERROR、BUSINESS_ERROR、POLICY_DENIED、APPROVAL_REQUIRED、FATAL_ERROR 不自动重试；SUCCESS 也不重试。

每次自动 retry 使用同一个 ToolCallRequest 和 ToolExecutionContext，工具、已验证参数、Case/version 上下文不变。模型改参数或生成新调用仍属于新的 invocation，不计为自动 retry。

## 5. Tool-effect retry matrix

| Tool/category | 当前 Effect / 执行路径 | transient 分类可能出现 | 通用自动 retry | 理由 |
|---|---|---|---|---|
| item reads | MCP READ_ONLY | 是 | 预算内允许 | 相同参数无副作用读取 |
| budget reads | MCP READ_ONLY | 是 | 预算内允许 | 同上 |
| supplier/list/item-supplier reads | MCP READ_ONLY | 是 | 预算内允许 | 同上 |
| purchase history/performance reads | MCP READ_ONLY | 是 | 预算内允许 | 同上 |
| requisition/RFQ 查询 | MCP READ_ONLY | 是 | 预算内允许 | 查询不是创建 |
| supplier_search | LOCAL READ_ONLY | 是 | 分类明确时允许 | 沿用 provider 搜索只读契约 |
| case_patch | 业务上 INTERNAL_WRITE；既有 sideEffect=true 被当前元数据视图保守映射为 EXTERNAL_SIDE_EFFECT | 可能 | 不允许 | 没有显式 retry-safe 声明；不趁本轮重构 catalog |
| recommendation_finalize 工具 | LOCAL READ_ONLY（返回验证快照） | 可能 | 明确 transient 分类且预算允许 | 工具本身不执行 Workflow 的 Case 状态推进 |
| Workflow canonical Finalize + Case CAS | Java application operation | 可能 | 不新增通用 replay | 原有单次评估、绑定、CAS 路径保持不变 |
| Approval 创建/决定 | 原有 approval 生命周期 | 不适用 | 不通用 retry | REQUIRE_APPROVAL 在工具 dispatch 前暂停 |
| ERP RFQ create | EXTERNAL_SIDE_EFFECT | 是，包括不确定结果 | **禁止** | 只由已有 canonical request、idempotency、receipt/reconcile 契约处理 |
| 其他 INTERNAL_WRITE | 既有 effect 显式声明 | 是 | 仅 catalog retry="safe" 可允许 | 默认拒绝；本轮没有给任何现有写工具新增此声明 |
| DESTRUCTIVE | DESTRUCTIVE | 是 | **禁止** | 未扩展业务例外 |

本轮没有修改任何工具 Prompt、description、schema、catalog 或 provider 业务逻辑。

## 6. Retry budget / telemetry

复用 `maxToolExecutionAttempts=2`：初始 attempt + 最多 1 retry；没有第三次。复用 production backoff 配置 `toolRetryBackoffMillis=150`，原有线性按 attempt 增加；本轮正常两次尝试之间为 150 ms。确定性测试 backoff=0。

复用 Runner 传入的 `_harnessDeadlineEpochMillis`；剩余时间不足以覆盖 backoff 时不重试，每次执行仍受既有 ToolExecutionDeadline 限制。RunBudget toolCalls 继续表示逻辑 invocation；物理 attempts 单独计数，避免混用。

receipt `_harnessAttempts` 和 TOOL_COMPLETED attemptHistory 保留每次 toolCallId、toolName、attempt、outcome、retryable、effect、retryDecision、retryReason、backoffMillis。最终 outcome 与历史一并存入已有 receipt/event。

恢复成功示例：Tool Attempts=2、Tool Errors=1、Recovered Retryable Errors=1、Retries=1、最终 SUCCESS。`DefaultAgentRuntime` 发布这些计数，Workflow runner 增加 attempt 级观测指标。**Frozen V2.2 grader 未修改**；其原有逻辑 invocation 错误统计与新增物理 attempt 错误统计分开报告，不能用成功后的最终 envelope 隐藏第一次失败。

## 7. Failure provenance

现有 WorkflowEvidence 从 receipt 推导未恢复 RETRYABLE/FATAL failure。只有同 Stage、相同工具和参数、失败之后的成功 receipt 才可视为恢复；不同物料的成功读取不能抹掉失败。

Workflow 在消费 typed result 之前完成业务推进前检查：保留 `workflowDataUnavailable`（toolCallId/tool/outcome/attempts），并返回既有 WORKFLOW_ERROR/FAILED/TOOL_ERROR。错误中明确 `DATA_UNAVAILABLE_DUE_TO_TOOL_FAILURE`，不伪造 business blocked。

即使同时缺 quantity，也优先保留基础设施错误，不推进 Case；已有成功 retry 的中间失败保留在 attempt history，但不会阻断成功事实的使用。没有增加知识图谱、通用恢复引擎或公开 Outcome enum。

## 8. Clarification causality

已知 item ID 但缺少 canonical item、缺失 canonical budget、未完成 canonical requisition scan 不再被视为用户可补充信息。真正缺 quantity 且 canonical read 成功仍可 NEEDS_USER_INPUT。既有 inactive/duplicate 等 canonical demand blocker 仍是可由用户调整需求的业务澄清。

新增 Workflow-specific `CLARIFICATION_CAUSALITY`：NEEDS_USER_INPUT 必须具备实际缺失的用户字段或既有 canonical demand conflict，且不能掩盖未恢复的基础设施失败。模型叙述本身不是因果证据。Frozen Business Grader 和既有 task/seed 均不变。

## 9. Deterministic fault-injection results

```powershell
mvn -B '-Dtest=ToolRetryPolicyTests,HarnessCoreTests,DefaultAgentRuntimeStateTests,ProcurementWorkflow*Tests,ProcurementStructuredOutputTests' '-Dsurefire.reportNameSuffix=workflow-retry-focused-r2' test
python -B -m unittest discover -s evals/procurement-workflow -p 'test_*.py'
```

Java **158/158 PASS**，0 fail/error/skip；Python **31/31 PASS**。

包含 ToolRetryPolicy 9、WorkflowRetry 6、HarnessCore 15、RuntimeState 23、BusinessOutcome 15、EvidenceCarry 23、RequisitionBinding 10、Workflow 30、StructuredOutput 27 项 Java 测试。不是把各阶段重复运行的相同测试相加。

已验证：transient read 第一次失败第二次成功；两次失败后无第三次且不是澄清；缺 quantity 与 infra failure 并存；真正缺 quantity；不同参数成功不能恢复旧错误；所有 outcome/effect 组合；validation/business/policy/fatal 不重试；不确定 external/destructive/internal write 不盲目 replay；明确 safe internal metadata、预算/deadline/veto；错误计数保留；既有无供应商正常终态及 HITL、evidence、RFQ binding 回归。

首次 focused 编译因非必要 Spring DAO 引用失败，没有运行测试；移除该引用后 r2 全通过。保留首次日志，不把它当作成功运行。

## 10. Legacy regression

```powershell
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite
```

证据：`target/procurement-v0/v0-20261005-100150-b974b985`。

| 阶段 | Total | PASS | SKIP | FAIL/ERROR |
|---|---:|---:|---:|---:|
| Procurement focused | 918 | 907 | 11 | 0 |
| Replay | 380 | 380 | 0 | 0 |
| Full Java | 1351 | 1342 | 9 | 0 |

Build 成功；5/5 frozen fixture bytes/hash 不变。Skip 是现有条件行为；未开启 Live/Postgres switches，不能宣称真实 PostgreSQL 或模型通过。每个 suite 的 skip 数见 results.json。

## 11. ROB-01 唯一启动结果

```powershell
python -B target/workflow-retry-audit-20261005/run_rob.py --prepare
python -B target/workflow-retry-audit-20261005/run_rob.py --model
```

prepare 只做真实配置/工具发现，不调用模型，也不证明 ERP 可用；Java Set 顺序和 schema JSON key 顺序不同，经已有语义规范化比较，Stage Profiles、Prompt、tool schema 和模型配置均相同。

启动 run：`workflow-retry-rob01-20261005-r1`，attempt：`ROB-01-1-a1`。reset 立即失败：

```text
failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine
The system cannot find the file specified.
```

分类 EVAL_INFRA_ERROR / EVAL_ENVIRONMENT_ERROR，partial artifact；0 valid trial，0 model call。world reset 在 Java trial driver 之前执行，故没有 raw/model trace、业务 canonical snapshot 或 RFQ receipt。

**Business=NOT_RUN；Workflow Acceptance=NOT_RUN；模型级 retry path=NOT_VERIFIED。** 不是 ROB-01 的模型质量失败，也不是 PASS。没有恢复 Docker、替换 attempt、付费重跑、继续其他 Smoke/Dev。运行脚本的进程 exit=0 仅表示已保存停止记录，不表示验收成功；`final_acceptance=false`。

## 12. Retry / tool-error metrics

确定性故障注入：恢复用例 2 attempts / 1 error / 1 retry / 1 recovered；耗尽用例 2 attempts / 2 errors / 1 retry / 0 recovered。

本次 live attempt：尚未到达 injected item call，因此模型 Tool Calls、Tool Errors、Retries、Recovered 等观测 **N/A**，不伪填 0/绿色。只可确认没有发送模型请求。

## 13. Safety

确定性保护用例通过，Legacy 冻结回归通过。本次模型试验未执行，五项 Frozen Safety（UnauthorizedWrite、DuplicateSideEffect、StaleApprovalExecution、BusinessInvariantViolation、FabricatedCriticalFact）均 **NOT_EVALUATED**；result 中空 invariant list 不能解释为五项安全验收通过。

## 14. Cost / source freeze / mature references

模型调用 0，本轮启动未消耗模型 tokens；provider usage 字段为空，不是测得的 token=0。失败 attempt wall time=0.625 s。不得复用旧 W1 的成本/安全结果冒充本轮。

修复后 production fingerprint：

```text
052e44be79e63f95dc233ff6881389e336d7df652a152956ad3967ef89f95e65
```

对比旧 freeze，仅 production 与 workflow_runner 区域改变；tasks、seed、world contract、frozen graders、V2.2、trial driver 均保持不变。启动前与停止后 freeze 相同。旧 Smoke 因 production fingerprint 已变只能作历史证据。

成熟实现的最小映射：

- **Codex：retry only classified transient failures。** 本轮官方检索未定位用户提供的 `is_retryable_error(...)` / `retry_on_overload(...)` SDK 符号或原句，故不声称独立验证了其具体 SDK 实现。[OpenAI 官方恢复文档](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery) 明确将临时 503 的 bounded backoff 与 invalid body/权限错误的禁止盲重试分开；本项目映射为结构化 outcome + bounded policy，不把所有异常都重试。
- **Spring AI：application owns tool execution/error handling。** [官方 Tool Calling 文档](https://docs.spring.io/spring-ai/reference/api/tools.html#exception-handling) 明确 ToolExecutionExceptionProcessor 可返回错误给模型或抛给调用者。本项目由 Runtime 执行安全 retry，Workflow 保留耗尽来源，不要求模型制定 retry policy。
- **Pi：tool failure is explicit isError；replay safety is separate tool metadata。** [官方 types.ts](https://github.com/badlogic/pi-mono/blob/main/packages/agent/src/types.ts) 已取得，execute 文档要求 throw 或 isError=true，独立声明 `replay?: "never" | "safe"`。本项目复用 Effect + 明确 safe retry metadata；不是完全复刻 Pi 的未知结果恢复机制。

## 15. Git

HEAD 与 `procurement-context-v1` 仍为 `2f649d6cbc56cea1803ffa6e5a3561d48a53d776`，分支未变。dirty 工作树包括既有 Workflow 实现和本轮修复，没有把 HEAD 当成已包含工作区的提交 SHA。

开始时保存 1278 个既有文件字节哈希；本轮仅 10 个既有源码/测试/Workflow runner 文件发生授权范围内修改，另新增 RetryPolicy、2 个 Java 测试类、Python causality tests、报告与证据目录。其余 1268 个既有文件保持一致。旧报告/artifact、Prompt/Profile/catalog、frozen grader/seed 均保留。

Handler 原有注释保持未暂存、未提交、原始字节相同：

```text
 M src/main/java/com/agent/platform/procurement/tool/ProcurementToolHandler.java
SHA256 8f3eea10df78f9e8f5010a4d2ce72ee049962fdc4e3b4d98cfcd7518ce2e7f6d
```

暂存区为空，git diff --check 通过。完整状态、allowlist、哈希、测试日志和 partial attempt 归档于 `evals/procurement-workflow/reports/retry-contract/`。

## 16. Scope / stop

未修改 Prompt、Tool Description、Stage/Tool Schema、Frozen Business Grader、seed、model config；未新增通用 retry/recovery 框架或 dependency；未改 Handler。未 commit/push/tag，未进入其他 Smoke、Dev、Holdout、40×3、Multi-Agent、Dynamic Selector 或新 Memory。

确定性修复完成，模型验收因环境阻断仍未完成。停止等待人工 Review，不将失败启动换成第二次 attempt。后续是否恢复环境并重新授权 ROB-01 由人工决定。

保留用户指定止损线：本轮获得模型验收后，下一次 same-source Smoke 若再发现新的核心 Production Contract 缺口，停止持续 patch，进入 Phase 4 vs Workflow Architecture Review；本轮没有提前启动该 Smoke。
