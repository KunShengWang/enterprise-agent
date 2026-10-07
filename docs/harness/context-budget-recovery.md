# Context Budget Recovery：审计与回归

2026-10-06。基于 `60edafa29f2954ac305c421c8ad3473cd2d3e63e`；新分支 `feat/context-reliability`。

后续状态：本文保留首次实现轮的失败门禁及 NOT RUN 历史；同日随后按人工指令完成不改源码的 A/B 诊断，Full Java 通过，并执行唯一一次 exposed ROB-04 regression PASS。最新证据与口径见 [MCP A/B 诊断报告](context-recovery-mcp-diagnostic.md)，不覆盖下文历史记录。

## 历史与曝光口径

Original Holdout-v1 永久保留 **11/12 (91.67%)，ROB-04 FAIL / CONTEXT_OVERFLOW**。原 artifacts 不改写。ROB-04 is now exposed regression case；后续单点 PASS 不能拼回旧 Holdout 宣称 12/12 unseen holdout。未来泛化数字须来自此前未曝光的 Blind 或新冻结 fresh holdout。

## 修改前 Root-Cause Audit

最后一次 ContextAssembler 输出（ConservativeTokenEstimator，非 provider billing tokens）：

| Component | Estimated tokens | Required? |
|---|---:|---|
| system/instructions | 1,693，已在消息预算外扣除 | Tier A，保持 |
| tool schemas | 1,963，已在消息预算外扣除 | 保持，不改 schema |
| current user request | 93 | Tier A，逐字保留 |
| canonical business state | 0，尚未 Patch | 存在时为 Tier A |
| conversation / assistant prose | 28 | 旧解释可淘汰；当前请求不可淘汰 |
| tool result projected content | 25,947 | Tier B，完整原文留执行记录 |
| tool envelopes / receipts / reference overhead | 263 | 保留成功状态、来源与必要引用 |
| summaries / compaction summary | 0 | 本路径不调用 LLM summarizer |
| run state / governance | 97 | Tier A |
| other / tool call arguments | 48 | 保持调用/结果配对 |

消息合计 **26,476**；加 instructions/schema 后 working context 估算 **30,132**。最大项 TOOL_RESULT 合计 **26,210**（25,947+263，约 99%）。三条原始内容共 104,680 bytes，投影后仍 103,783 bytes；注入的 get_item 单条内容 102,334 bytes。完整记录见历史 failure-analysis.json。

现有路径：AgentRunner 的 DefaultAgentRuntime → contextManager.project（fresh canonical state + timeline）→ ContextAssembler → StructuredToolProjection → 历史窗口/完整重复调用对去重 → estimator → 预算检查 → model。

1. 每轮模型调用前 assemble。旧对话在超过 `budget × compactionThreshold` 后按 recentUserTurns 修剪；结构化投影和重复对去重每轮执行。
2. 启用 Phase 4 policy 时，assembler 自己负责压缩；不调用旧 contextManager 的 timeline summarizer。每轮仅一次 assemble，没有超预算后的进一步恢复。
3. 原 timeline 的 ToolResultProjector.project 已按 24,000 chars 截断；assemble 从完整 ToolExecutionStore 恢复原文后执行 projectForContext，其无损结构化投影保留未知长文本，从而再次放大。
4. 长文本注入不是大 summary；本轮没有 summary。旧摘要已被 project 排除。
5. 当前 canonical state、当前请求、运行/审批状态、未知/未持久化/失败或需人工复核的结果必须保留；现有机制宁可 overflow。
6. 最终 `estimatedTokens > messageBudget` 立即 CONTEXT_OVERFLOW，第二次模型请求没有发出。

预算不调整：32,000 − output reserve 2,000 − safety margin 1,000 − instructions 1,693 − schemas 1,963 − framing allowance 700 = **24,644**。同时受 profile.maxInputTokens 上限约束。保持输出预留和估算余量。

## 最小参考原则

仅采用任务提供的 Pi 原则：工具结果加入后、下一次 assistant 请求前检查可用预算；以及 Codex 原则：压缩不足时有界回退，优先保留近期上下文。不复制这些产品的架构或固定 token 数；这里不是对其当前源码行为的独立验证。

## Blind inventory

修改前仅检查 tracked 文件名/目录：未发现独立 Blind set 或 Blind 冻结 manifest，不能证明已有未曝光且预先冻结的 Blind 集合；未打开任何 Blind task 内容，未运行 Blind。

## Recovery 实现与边界

仅修改现有 ContextAssembler / ContextPolicy / ToolResultProjector。现有 assemble、结构化无损投影和去重后重新估算，只有仍超预算才进行一次确定性恢复：

1. 仅选择可信 runtime contract 为 READ_ONLY、完整执行结果已持久化、身份匹配且成功的终态结果；不改变 ToolEffect 或 trust 判定。
2. 对 JSON 中可缩减的长文本叶子提供 256 字符前缀和 256 字符后缀、truncated=true、originalChars、rawJsonPointer；保留完整结果的 SHA-256 和 tool-execution:ID 引用。没有生成业务结论，所有数值、布尔值、短文本和行保持原样。
3. identity/ref/digest/version/evidence/source、record、offers/evaluations/recommendation、specifications、审批/授权/policy/constraints 等子树保留原样；结构化表格也保留，避免丢失数组列中的证据身份。
4. 若仍超预算，按时间顺序删除非当前 Run、recentUserTurns 窗口外且无额外 metadata 的 Tier-C 旧 prose。完整 tool call/result 配对、canonical state、SYSTEM 和当前请求不动。原有完整重复对去重继续复用，不增加激进的“语义去重”。
5. 每次 assemble 最多一次恢复 pass，重新估算仍超预算则沿用 CONTEXT_OVERFLOW。没有新增 LLM summarizer、Memory 框架或额外模型请求。

保守限制：未知纯文本、关键子树过大、无法确定可舍弃部分或不可约 Tier-A 超预算时仍失败；不通过删除关键事实或整行数据来强行放行。该机制不按任务 ID 或工具名称特判，也不保证任意不可约内容都能放入窗口。

正常未超预算的消息内容和原投影保持；仅补充 telemetry。pre_compaction_tokens / post_compaction_tokens 使用消息预算口径；tokens_before_recovery / tokens_after_recovery、history_items_evicted、tool_results_reprojected、final_context_tokens、budget 和 recovery_passes 均记录。一次 Run 可跨多轮再次从完整 store 组装，每次 assemble 各最多一个 pass，绝非无限 compaction loop。

## 本轮确定性结果与模型门禁

本轮实现已完成，但**验收未完成**：Full Java 出现既有 MCP discovery 测试错误，单独复核也失败。因此确定性门禁未通过，未调用模型，未执行 ROB-04。不能将 focused tests 的通过等同于完整门禁通过。

实际命令：

```powershell
mvn -B -Dtest=ContextBudgetRecoveryTests,ContextEngineeringTests,DefaultAgentContextManagerTests,HarnessCoreTests,DefaultAgentRuntimeStateTests test
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite
mvn -B -Dtest=StdioMcpToolGatewayTests#refreshFailureInOneServerDoesNotAffectAnotherServer -Dsurefire.reportNameSuffix=context-recovery-mcp-check test
```

FullSuite 内部实际执行的全量 Java 命令：

```powershell
mvn -B -Devaluation.codeRevision=60edafa29f2954ac305c421c8ad3473cd2d3e63e -Dsurefire.reportNameSuffix=v0-20261006-110607-57421be5-full test
```

上述 revision 是 Test Run HEAD；实际测试源码为该 HEAD 加本轮未提交的 Context Recovery 修改，非纯 Tag 源码。

| 检查 | Total | Pass | Failure | Error | Skip |
|---|---:|---:|---:|---:|---:|
| Focused 最终执行 | 66 | 66 | 0 | 0 | 0 |
| Legacy procurement | 807 | 796 | 0 | 0 | 11 |
| Legacy Replay | 380 | 380 | 0 | 0 | 0 |
| Full Java | 1,255 | 1,245 | 0 | 1 | 9 |
| MCP 单独复核 | 1 | 0 | 0 | 1 | 0 |

构建成功；frozen fixture SHA-256 **5/5 unchanged**。各行是不同运行且存在重复测试，不能相加为唯一测试总数。

新增 8 个测试覆盖：任意工具名的大 READ_ONLY JSON 结果恢复、完整执行记录不变、稳定引用、canonical/current user/governance/evidence 保留、旧 prose 淘汰且近期消息/工具配对保留、不可约 Tier A overflow、最多一次 recovery、正常内容不变，以及写入/失败/manual review/未持久化/未知纯文本不被强制缩减。既有 `DefaultAgentRuntimeStateTests.oversizedRequiredContextStopsBeforeModelCall` 验证真实 Runtime 的 CONTEXT_OVERFLOW 和不发出模型/工具调用。

最初 focused 运行 42 项中有 1 个测试断言失败：新建 history eviction fixture 的 600-token 预算在投影后已足够，不会触发预期的 eviction；仅调整测试输入及预算至 400 后执行上述 66 项并通过。初次失败日志保留为 `target/context-reliability/focused.log`，最终日志为 `focused-final.log`；不隐去初始失败。

Full Java 和单独复核均在 `StdioMcpToolGatewayTests.refreshFailureInOneServerDoesNotAffectAnotherServer:331` 报 `NoSuchElementException`。测试创建 MCP 假服务器并设置 `Duration.ofSeconds(1)` discovery timeout。单独复核日志显示：

```text
11:10:34.141 fake-refresh-die-once connected
11:10:35.287 discovery failed: McpRequestTimeoutException
11:10:35.304 fake-refresh-change connected
11:10:36.472 discovery failed: McpRequestTimeoutException
```

随后查找 `mcp.failed.echo` 工具定义失败。测试及 MCP 生产源码均未改动；当前证据只能确认 discovery 超时，不能确认机器负载或 Docker 启动是根因。未调大测试超时、未修改 MCP/retry contract、未反复重跑来获得绿灯。原 FullSuite 非零退出和独立复核非零退出均保留。

Skip 为既有环境条件：procurement 阶段 `JdbcProcurementCaseStorePostgresIT` 两个测试因 `PROCUREMENT_POSTGRES_IT=false` 跳过；另 9 项依赖 artifact system properties，在普通 full/procurement 阶段跳过，Replay 阶段提供相关 artifacts 后通过。逐测试原因见 `target/context-reliability/skips.json`。

## Holdout-v1 ROB-04 regression：NOT RUN

确定性未全部 PASS，故本轮模型 trial 数 **0**，没有第二次模型请求或 post-fix Business PASS 证据。未启动模型 readiness/reset/reseed 或 trial helper；helper 仅保存为未执行的准备文件。

| Context 指标 | 原始失败 | 本轮模型回归 |
|---|---:|---|
| existing compaction 后 / recovery 前 | 26,476 | N/A，未执行 |
| recovered / final_context_tokens | 无二级恢复，26,476 | N/A，未执行 |
| budget | 24,644 | 配置未改；未执行模型核验 |
| 第二次模型请求 | 未发出 | N/A，未执行 |

确定性大结果用例已证明：before recovery > 3,000、after recovery ≤ 3,000、recovery_passes=1、tool_results_reprojected=1，完整原结果不变；近期历史用例最终 ≤ 400，淘汰 1 条旧 prose。以上是测试专用预算，不是改动正式 24,644 预算，也不是 ROB-04 模型测量值。

本轮模型五项安全指标均 **N/A / NOT RUN**：UnauthorizedWrite、DuplicateSideEffect、StaleApprovalExecution、BusinessInvariantViolation、FabricatedCriticalFact。不能写为模型安全全零。原始 Holdout-v1 五项仍全零。

本轮 Provider calls=0；Provider input/output/total usage 无返回值，记为 N/A，不虚构 billing savings。恢复 telemetry 已实现，但没有本轮 ROB-04 trace 可报告。

## 证据、Git 与范围

主要证据：

- `target/context-reliability/focused-final.log`
- `target/context-reliability/mcp-check.log`
- `target/procurement-v0/v0-20261006-110607-57421be5/results.json` 及各阶段日志/Surefire XML
- `target/context-reliability/final-verification.json`

最终检查：新 worktree 分支 `feat/context-reliability`，HEAD 仍为 `60edafa29f2954ac305c421c8ad3473cd2d3e63e`；3 个 runtime 源文件修改，新增 1 个测试文件和本报告，均未暂存。`git diff --check` 通过。正式 `procurement-harness-reliability-v1` 为 annotated tag，其 tag object 与 peeled commit 分别核验，peeled commit 仍是上述 SHA；所有已有 tag objects 均与任务开始前一致。

原工作区 Git status 完全一致，Handler 字节 SHA-256 仍为 `8f3eea10df78f9e8f5010a4d2ce72ee049962fdc4e3b4d98cfcd7518ce2e7f6d`，未暂存/未提交/未修改。旧 Holdout 146 个 artifact 文件逐个 SHA-256 核验不变。

环境准备中启动了 Docker Desktop，并用既有 compose 恢复 ERPNext 服务；共享服务挂载现指向本次新 worktree。未删除 volume 或执行 reset/reseed；原工作区及旧 artifact 未写入。凭据仅复制到 ignored 环境文件，未进入报告或 Git。

未修改业务规则、业务 Prompt、schema、budget/model、MCP trust/retry、HITL/RFQ、grader 或 seed。未实现 Multi-Agent、Workflow、Dynamic Tool Loading；未运行 Smoke、Dev、其他 Holdout tasks、40×3 或 Blind；未 commit/push/tag。当前停在确定性门禁失败处，等待人工 Review，不能宣称 Context Recovery 已通过模型验收。
