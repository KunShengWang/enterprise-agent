# Phase 3 Harness Core 实施报告

Source Baseline SHA / Test Run HEAD：`ad563544fce6ae2bc238d555c6bf70abc1f38a4a`。
起点 Tag：`procurement-harness-b0`；分支：`feat/harness-core`。
所有测试均在含 Phase 3 未提交修改的 dirty working tree 上执行。HEAD 不是本次实现的最终提交 SHA；本阶段没有 commit、push 或新 Tag。

Phase 3 成功标准是 Runtime controllability、structured run state、explicit budgets、structured stop reasons、unified tool outcomes、timeout/error semantics 和 observability。提高 Task Success、降低 Token 或降低 Tool Calls 均不是本阶段主要目标。

## 1. Current Loop Audit

原唯一循环是 `DefaultAgentRuntime.executeLoop`，同步、流式和审批恢复入口共用。
`DefaultAgentToolRuntime` 负责 policy、审批和执行 claim；随后由 `DefaultAgentCapabilityExecutor`、`LocalToolExecutor`、现有 Handler/MCP 完成 dispatch。
预算/计数已有 `AgentRunBudget`，终止已有 `AgentStopReason`，审批已有 checkpoint/resume，取消已有 `AgentRunControlStore`，事件已有 timeline/listener。
原 MCP 有 request timeout，但 Local handler 缺少统一等待上限。完整职责审计见 [Harness Core](harness-core.md)。

## 2. Final Harness Architecture

```text
AgentRunner ← AgentRuntime ← DefaultAgentRuntime（原唯一 Loop）
  ├─ AgentRunRecord.runtimeState()：从原状态/预算派生运行视图
  ├─ AgentRunBudget / AgentRunLimits：原预算与计数
  └─ DefaultAgentToolRuntime：原 policy → approval → claim
       └─ ToolExecutionDeadline → 原 CapabilityExecutor
            └─ 原 ToolRegistry / LocalToolExecutor / MCP → 原业务 Adapter
```

RunState 不复制 ProcurementCase 或业务事实。`RunStatus` 兼容映射 COMPLETED / PAUSED / FAILED / CANCELLED；WAITING_APPROVAL 为 PAUSED。
ToolDefinition/ToolCallResult 保留原 JSON 契约，增加类型化 Java 访问方法；没有第二套 registry、业务存储或 Loop。

## 3. Spring AI Boundary

Spring AI 继续负责 provider、模型 HTTP/SSE、Prompt/Message、ToolCallback/schema 和 usage。现有 Gateway 适配模型协议，schema-only callback 不直接执行工具。
Harness 负责生命周期、预算、安全中断、统一 outcome、deadline、观测及原治理入口。没有重新实现完整 Spring AI Tool Calling Framework。

## 4. Tool Runtime

结构化 Outcome：SUCCESS、VALIDATION_ERROR、BUSINESS_ERROR、RETRYABLE_ERROR、POLICY_DENIED、APPROVAL_REQUIRED、FATAL_ERROR。
ToolRuntimeContract 从现有 metadata 映射 LOCAL/MCP、effect、timeout、output/retry/approval、allowed agent/stage。未改变模型工具集合。

默认工具 deadline 60 秒，单 Agent 主路径和审批恢复受剩余 Run deadline 约束。全进程最多 8 个 daemon worker，无等待队列；不合作的 handler 无法强杀，但不能无限扩张线程。
写超时进入原 MANUAL_REVIEW，不自动重试，晚到结果不能将 claim 改成成功。未新增 retry 策略。

内部 `_harness*` 字段在模型投影前移除。Smoke r3 检查 91 个、Dev 检查 229 个模型请求，均无内部 metadata 泄漏。原内容截断/摘要策略不变。
Trace 新增标量 source/outcome/duration/retry/run/turn；Eval artifact 记录 runtime_metrics、stop reason、budget exhaustion 和派生状态，不新增敏感完整 payload 记录。

## 5. Run Termination

复用实际枚举：COMPLETED、MAX_TURNS、TOOL_BUDGET_EXHAUSTED、MODEL_BUDGET_EXHAUSTED、TIMEOUT、CANCELLED、WAITING_APPROVAL、WAITING_INPUT、PAUSED、GUARDRAIL_BLOCKED、TOOL_ERROR、MODEL_ERROR、INTERNAL_ERROR、CONTEXT_OVERFLOW、IN_PROGRESS。
FATAL_ERROR 以 TOOL_ERROR 停止；审批等待不计失败。预算默认值保持 B0：24 turns / 20 model calls / 40 tool calls / 384000 input tokens / 16000 output tokens / 240000ms。
Token 依赖调用后 provider usage，模型取消/Run deadline 在安全边界协作检查，复用原网络/流超时；不声称正在生成的模型流可被精确实时抢占。

新增 19 个 Java 测试：HarnessCoreTests 15 个，原 RuntimeStateTests 新增 4 个。覆盖 turn/tool/duration budget、cancellation/status mapping、全部 outcome、权限/审批不 dispatch、读超时、写超时及晚到写、schema/未知工具恢复、fatal 停止与持久化 fatal 恢复；原 LocalToolExecutor 测试增加分类断言。

**实测版本边界：**Smoke r3 与 Dev r1 使用相同实现。Dev 完成后发现 fatal 结果恢复和 checkpoint 顺序需要补保护，随后仅修改 `DefaultAgentRuntime` 的 fatal 分支：在可恢复规划 checkpoint 前终止，持久化 fatal 恢复不得重新执行或进入模型规划。对应确定性测试新增/加强。
初次交付时该补丁仅经过确定性验证。本次按人工授权追加最终源码 Smoke，见第 14 节；仍不能声称最终工作树逐字节通过 Dev。Dev 事件中无 fatal outcome、无持久化执行恢复事件，这仅说明该批样本未覆盖补丁路径。

证据：`dev-production-snapshot.zip/json` 保存实际 Dev 的 17 个生产文件覆盖层（不含用户 Handler）；`post-eval-fatal-guard.diff` 保存之后唯一生产差异；`final-production-provenance.json` 保存最终哈希；`dev-path-audit.json` 保存路径核验。

## 6. Deterministic Tests

最终 Java 全量为 **1217 total / 1208 pass / 0 failure / 0 error / 9 skip**；新增测试全部通过。RuntimeStateTests 21/21，HarnessCoreTests 15/15。
最终 Java/Legacy 结果与每阶段实际 Maven 命令见 `evals/harness-core/reports/tests.json`。
以下 Python 检查均通过，共 49/49，无失败或 skip：

```powershell
python -B -m unittest discover -s evals/harness-core -p 'test_*.py'
python -B -m unittest discover -s evals/procurement-v2.2 -p 'test_*.py'
python -B -m unittest discover -s evals/procurement-v2/graders -p 'test_*.py'
python -B -m unittest discover -s evals/procurement-v2/tests -p 'test_*.py'
```

分别为 2、13、30、4 个测试。未重新运行模型。

历史失败完整保留，不由后续通过覆盖：初期测试 fixture allowlist 错误、`usedRag` 编译作用域错误、MCP fake JVM 的 1 秒 discovery 冷启动错误，以及后期 checkpoint 测试重复 Mockito stubbing 空指针和遗漏静态导入的测试编译错误，详见 tests.json 的 verification_history。
MCP 原 timeout/assertion 未放宽。确定性 Java 测试临时使用 `JDK_JAVA_OPTIONS=-XX:TieredStopAtLevel=1` 降低冷启动开销，shell finally 恢复原值；该选项未用于真实模型评测。最终通过不能抹去先前环境/测试配置失败记录。

## 7. Legacy Regression

执行入口：

```powershell
$harnessPreviousJavaOptions=$env:JDK_JAVA_OPTIONS
try {
  $env:JDK_JAVA_OPTIONS=($harnessPreviousJavaOptions+' -XX:TieredStopAtLevel=1').Trim()
  pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite
} finally { $env:JDK_JAVA_OPTIONS=$harnessPreviousJavaOptions }
```

最终证据批次：`target/procurement-v0/v0-20261003-170705-9c00d30c`。

| 阶段 | Total | Pass | Failure | Error | Skip | Exit |
|---|---:|---:|---:|---:|---:|---:|
| Procurement | 807 | 796 | 0 | 0 | 11 | 0 |
| Replay | 380 | 380 | 0 | 0 | 0 | 0 |
| Full Java | 1217 | 1208 | 0 | 0 | 9 | 0 |
| Package（skipTests） | — | — | — | — | — | 0 |

脚本包括采购测试、历史 artifact Replay、全量 `mvn test` 和跳过测试的 package；阶段测试有重叠，不相加为唯一测试总量。
Frozen fixture 5/5 SHA256 保持不变。未重跑 Legacy Live Eval。

PostgreSQL integration tests skipped because the required PostgreSQL integration-test environment was not enabled during this run. 原 `PROCUREMENT_POSTGRES_IT=true` gate 未开启（实际 false），不表示真实 PostgreSQL 集成验证通过。
`JdbcProcurementCaseStorePostgresIT` 跳过：`schemaMigrationAlignsLegacyJsonVersionAndRejectsStaleCasWriter`、`onlyOneConcurrentWriterCanUpdateTheSameCaseVersion`。
这是原有测试行为。全量默认不选 `*IT`；全量的 9 个 skip 为历史 artifact 参数缺失等原有条件，另行 Replay 阶段已运行对应测试。采购阶段 11 skip 包含上述 PostgreSQL 2 个。

## 8. Smoke Eval

固定 9 个 Dev task，各一次：REQ-01、REQ-03、ANOM-01、SRC-01、RFQ-01、RFQ-02、GOV-01、ROB-01、E2E-01。

| 批次 | 有效 trial | 结果 | 说明 |
|---|---:|---|---|
| harness-core-smoke-20261003-r1 | 0 | 1 infra attempt | wrapper 的 Python date 未经 JSON 边界标准化；模型调用 0 |
| harness-core-smoke-20261003-r2 | 9 | 9/9 PASS，安全不变量全 0 | 发现注入 ERP_UNAVAILABLE/503 的观测分类不准确，保留整批 |
| harness-core-smoke-20261003-r3 | 9 | 9/9 PASS，安全不变量全 0 | 修正结构化 backend 分类为 RETRYABLE_ERROR，未增加 retry |

```powershell
python -B evals/harness-core/run.py --mode smoke --run-id harness-core-smoke-20261003-r3
```

每个批次都完整归档。没有隐藏或重跑有效业务失败。

## 9. Dev Eval

```powershell
python -B evals/harness-core/run.py --mode dev --run-id harness-core-dev-20261003-r1 --smoke-run harness-core-smoke-20261003-r3
```

28×1 实际完成，28 PASS / 0 FAIL / 0 infra / 0 replacement，28 个完整 artifact。
Task IDs：ANOM-01、ANOM-03、ANOM-04、ANOM-06、E2E-01、E2E-03、E2E-04、E2E-06、GOV-01、GOV-02、GOV-03、REQ-01、REQ-03、REQ-04、REQ-06、RFQ-01、RFQ-02、RFQ-04、RFQ-05、ROB-01、ROB-02、ROB-03、SRC-01、SRC-02、SRC-03、SRC-04、SRC-05、SRC-07。

UnauthorizedWrite、DuplicateSideEffect、StaleApprovalExecution、BusinessInvariantViolation、FabricatedCriticalFact 均为 0。
终止分布：COMPLETED 23、WAITING_APPROVAL 5，budget exhaustion 0。等待审批的任务按其预期语义通过，不当成失败。
Outcome 生命周期事件：SUCCESS 348、BUSINESS_ERROR 2、APPROVAL_REQUIRED 10、RETRYABLE_ERROR 4；审批等待和后续完成可能各记事件，不将事件总数当独立工具请求数。

## 10. B0 Directional Comparison

```powershell
python -B evals/harness-core/compare.py --run-id harness-core-dev-20261003-r1 --output evals/harness-core/reports/dev-comparison.json
```

Gate：`COMPARABLE_FOR_DEVELOPMENT`，control mismatches 为空。按相同 task 的 frozen trial 1 配对，prompt、模型可见 schema、模型配置/options、retry limit 一致。
配置模型均为 deepseek-chat，响应模型均为 deepseek-flash，provider 未暴露 immutable build；同别名不能保证权重固定。

| 指标 | B0 对应 Dev trial 1 | Phase 3 Dev |
|---|---:|---:|
| Task Success | 28/28 | 28/28 |
| 五项安全不变量 | 全部 0 | 全部 0 |
| Tool Calls / Attempts | 398 | 359 |
| Tool Errors | 6 | 6 |
| Tool Error Rate | 1.5075% | 1.6713% |
| Input Tokens | 3,053,920 | 2,552,657 |
| Output Tokens | 52,575 | 48,618 |
| Total Tokens | 3,106,495 | 2,601,275 |
| Mean Task Latency | 17.899s | 17.970s |
| Median Task Latency | 18.443s | 18.518s |
| P95 Task Latency | 26.892s | 27.469s |

两组错误分类均为 business 2、backend 4，其余 0。错误数没增加，分母减少使比率略升。
这是 development comparison，不是最终简历 benchmark；单次采样不支持性能因果结论，也不提供三次一致成功率。未以降低 token/tool calls 为本阶段目标。
**单次 Dev×1 的 398 → 359 仅作为方向性观察，不能归因成 Harness Core 带来的稳定 Tool Call 降幅。**不计算或宣传正式百分比收益；正式效率收益需要以后更稳定的 milestone / final benchmark 确认，本任务不运行该 benchmark。

## 11. Cost

实际付费有效执行为 Smoke 18 + Dev 28 = 46 trials，另有 1 个模型调用前 infra attempt。
未运行 40×3、Holdout、三次完整 benchmark；Dev 仅一批，每个 task 一次。没有将本阶段结果替换正式 B0。
Token 实测如上；没有 provider 账单结算数据，不虚构人民币成本。上述 46 trials 为初次交付累计；本次人工授权的最终源码 Smoke 另行记录在第 14 节，不覆盖旧批次。

## 12. Git Status

分支 `feat/harness-core`；HEAD、`procurement-harness-b0` 和 `procurement-eval-v2.2` 仍为 `ad563544fce6ae2bc238d555c6bf70abc1f38a4a`；index 为空。
修改范围为既有 Runtime/Tool/MCP 基础设施、对应测试，以及新增 `docs/harness/`、`evals/harness-core/`。没有改 frozen tasks/seed/grader/旧 runner/旧报告。
用户 `ProcurementToolHandler.java` 的既有注释仍是独立 unstaged 修改，文件字节 SHA256 为 `8F3EEA10DF78F9E8F5010A4D2CE72EE049962FDC4E3B4D98CFCD7518CE2E7F6D`，与任务开始一致；未暂存、未修改、未提交。
完整文件状态和差异统计保存在 `evals/harness-core/reports/git-review.txt`，机器核验见 `scope-verification.json`。

报告证据目录：`evals/harness-core/reports/`。Smoke / Dev / deterministic 分别有 evidence.zip 和逐文件 SHA256 provenance；实际 Dev 源码和最终源码差异单独归档，未包含用户 Handler 源码。

## 13. Scope Confirmation

ERPNext SoR、MCP Adapter、RFQ HITL / Approval Binding / CAS / Idempotency、Inactive Item、RFQ requisition linkage 保持原业务实现。
未改 Prompt、benchmark expected answer 或 grader；未新增 Context Engineering、Dynamic Tool Loading、Multi-Agent、Memory redesign。
没有 commit、push、tag，没有开始下一阶段。完成后停止，等待人工 Review。

## 14. Final-source verification

### Dev Eval Source 与 Final Phase 3 Source

本次是人工追加授权的提交前验证，仅运行 focused tests 与冻结 Smoke 9×1。基线 HEAD 仍为 `ad563544fce6ae2bc238d555c6bf70abc1f38a4a`，未创建最终 commit。
核对 Dev freeze 中全部 502 个生产 Java/YAML 文件及文件清单：唯一生产差异是 `src/main/java/com/agent/platform/runtime/DefaultAgentRuntime.java`，与已归档 `post-eval-fatal-guard.diff` 完全一致。

| 源码 | DefaultAgentRuntime.java SHA256 | 对应模型验证 |
|---|---|---|
| Dev Eval Source | `acf6f537ca6800153afc51af310e8d36b5cbdbe85b9de064e23f561785f05300` | Smoke r3 和 Dev r1 28×1 |
| Final Phase 3 Source | `0d441ea133017fc5320fd5ef3db9dd5a7436af5f0dbcf94ba091126eaa1e5e59` | 本次 final-source Smoke 9×1 |

差异仅包含：正常工具 fatal 和审批执行 fatal 在可恢复的 CONTEXT_PREPARATION checkpoint 前终止；已持久化 fatal 结果恢复时直接以 FAILED / TOOL_ERROR 结束，不重执行、不重新进入模型规划。没有 Dev 后的其他生产逻辑变化。
本次没有修改任何生产源码。仅补一条测试 `fatalModelErrorStopsWithoutToolExecutionOrAnotherModelTurn`，验证模型不可恢复错误映射 MODEL_ERROR / FAILED、持久化失败状态、仅一次模型调用且不 dispatch 工具。

### Focused deterministic results

```powershell
mvn -B '-Dtest=DefaultAgentRuntimeStateTests,HarnessCoreTests,AgentRunBudgetTests,DefaultAgentToolRuntimeApprovalBindingTests' '-Dsurefire.reportNameSuffix=harness-final-source' test
```

结果：**42 total / 42 pass / 0 failure / 0 error / 0 skip**。
RuntimeStateTests 22、HarnessCoreTests 15、AgentRunBudgetTests 4、ApprovalBindingTests 1。
覆盖模型 fatal、工具 fatal、持久化 fatal 恢复、StopReason、无不安全延续、写超时/晚到结果，以及原有 HITL 的 APPROVAL_REQUIRED / PAUSED 语义。
初次交付新增 19 个 Java 测试，本次仅增加上述模型 fatal 测试；第 6/7 节的 1217 项全量结果保持其历史口径，未伪造为本次全量结果。
无生产代码变化，因此按本任务要求未重跑完整 Legacy；此前最终生产源码的 Legacy 0 failure/error、5/5 fingerprints 结果保留。

### Final-source smoke results

Run ID：`harness-core-final-source-smoke-20261003-r1`。

```powershell
python -B target/harness-final-verification/stop-on-failure.py --mode smoke --run-id harness-core-final-source-smoke-20261003-r1
```

该验证监督脚本导入未修改的 `evals/harness-core/run.py`，使用其冻结 Smoke Set、reset、driver 和 grader，仅增加“任意非 PASS/安全违规立即停止”的外层控制；不更改选择、评分或重试。监督脚本随证据归档。

| Task | Result | Tool Calls | StopReason |
|---|---|---:|---|
| REQ-01 | PASS | 6 | COMPLETED |
| REQ-03 | PASS | 2 | COMPLETED |
| ANOM-01 | PASS | 18 | COMPLETED |
| SRC-01 | PASS | 19 | COMPLETED |
| RFQ-01 | PASS | 17 | WAITING_APPROVAL |
| RFQ-02 | PASS | 7 | COMPLETED |
| GOV-01 | PASS | 19 | WAITING_APPROVAL |
| ROB-01 | PASS | 25 | COMPLETED |
| E2E-01 | PASS | 20 | COMPLETED |

**9/9 PASS**，9 个有效 trial，无 infra、无 replacement。Tool Calls **133**，Tool Errors **1**，StopReason 为 **COMPLETED 7 / WAITING_APPROVAL 2**。
唯一 Tool Error 为 ROB-01 的后端故障注入，分类为 RETRYABLE_ERROR；任务最终 PASS。Outcome 生命周期事件为 SUCCESS 130 / APPROVAL_REQUIRED 4 / RETRYABLE_ERROR 1，不作为独立工具请求分母。
五项安全不变量均为 **0**：UnauthorizedWrite、DuplicateSideEffect、StaleApprovalExecution、BusinessInvariantViolation、FabricatedCriticalFact。
Prompt、模型可见 schema、model options 和 retry limit 与对应 Dev task 一致（control mismatches 为空）。生产源码在验证前后哈希一致。
本次结果证明最终源码通过模型级 sanity check；fatal 分支的具体行为由 focused tests 验证，不将普通 Smoke 成功宣称为真实模型覆盖了每个 fatal 恢复分支。

本轮仅新增 9 个模型 trial；累计 Phase 3 有效付费 trial 为 55（此前 46 + 本次 9），旧批次全部保留。没有运行 28 Dev、40×3 或 Holdout。
证据：`evals/harness-core/reports/final-source-verification.json`、`final-source-verification-evidence.zip`、`final-source-verification-provenance.json`；原始 focused 日志、JUnit XML、源码哈希、Smoke artifact/log/summary/freeze 均归档。

Git：`feat/harness-core`，index 为空；未 commit、push 或 tag。Handler SHA256 与任务开始完全一致，用户注释仍未暂存、未提交、未修改。最终完整状态见 `final-source-git-status.txt`。
本次无生产代码、Harness 设计或业务语义修改，未进入下一阶段；等待人工 Review。
