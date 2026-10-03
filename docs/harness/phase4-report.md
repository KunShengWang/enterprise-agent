# Phase 4 Context Engineering 验证报告

Source Baseline / Test Run HEAD：`cfb29cdf31ca240802cc86c2b9f9642f7fbfde42`（`procurement-harness-core-v1`）。分支：`feat/context-engineering`。测试在包含未提交 Phase 4 修改的 dirty working tree 上执行。以下结果对应同一最终源码，等待人工 Review；未创建 Phase 4 commit。

## 1. Pre-change Context Audit

编码前离线读取冻结 B0 与 Phase 3 Dev 的真实请求档案，选择 REQ-03、SRC-01、RFQ-02、E2E-04。逐轮估算见 `evals/context-engineering/reports/pre-change-context-audit.json`。

B0 SRC-01 的累计本地估算总量为 116,759，其中 tool results 64,343（约 55%），instructions 22,009、schema 25,519、business state 411、conversation 1,635、other 2,842。同期 provider 模型调用 prompt usage 为 162,791。两种数字计量边界不同，不能混同。工具数据是主要冗余来源；Case 状态本身很小。

## 2. Final Architecture

`DefaultAgentRuntime → ContextManager（fresh sources）→ ContextAssembler → AgentContextView → 原 ModelGateway`。仅 ERPNext Procurement profile 启用；没有第二套 Agent Loop。

ContextPolicy 统一模型窗口、输出预留、阈值、近期 user-turn 保留窗口；沿用配置安全 margin 和现有 RunBudget。结构投影总会消除可证明的重复，旧对话裁剪由 token threshold 触发。ContextPolicy/Assembler 不写业务库、Timeline、Approval 或 Memory。完整设计见 `context-engineering.md`。

## 3. Business State Protection

每轮复用 ProcurementCaseContextRenderer 从 CaseStore 重新读取 canonical Case/version/status/需求字段，旧 conversation summary 被排除。Recommendation、ERP、RFQ 的现有工具事实保留来源版本和 raw reference，pending interruption 来自 RunRecord。没有从摘要恢复 budget/quantity/constraints，也没有将这些事实写入长期记忆。ERP 工具结果属于带来源的观察，服务器写入仍执行原有审批绑定、CAS、授权和幂等检查。

## 4. Tool Projection

Search 的相等 offer subtree、sourceDigest 匹配且可精确解析的重复 specifications 使用引用；history/performance 同构分页 records 使用 common/columns/rows 无损表；保留全部行、关键尾部、nextOffset、Evidence ID、来源与版本。未知/非 JSON/错误保持 FULL，不能装入窗口则在模型调用之前 CONTEXT_OVERFLOW。

重复结果去重仅适用于显式 readOnly 且非 sideEffect 的工具；未知权限的 MCP definitions 保守保留，不从名称猜测只读性。只删除较早完整 call/result 对，trace 不删除。RUNNING/MANUAL_REVIEW 或 success 不一致的执行记录不替换 timeline 安全提示。

## 5. Deterministic Tests

```powershell
mvn -B '-Dtest=ContextEngineeringTests,HarnessCoreTests,DefaultAgentRuntimeStateTests,DefaultAgentContextManagerTests,ProcurementCaseContextRendererTests,NativeToolCallingAgentModelGatewayTests,JsonAgentModelGatewayTests,AgentRunBudgetTests,DefaultAgentToolRuntimeApprovalBindingTests' '-Dsurefire.reportNameSuffix=context-l0-r3' test
```

77 total / 77 pass / 0 failure / 0 error / 0 skip。覆盖 fresh Business State、不使用旧 summary、审批保留、完整工具对去重、版本变化保留、300 行可还原表及关键尾部、不可压缩内容安全停止、manual-review envelope、Phase 3 fatal/budget/HITL 语义。

## 6. Legacy Regression

```powershell
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite
```

本次测试进程临时附加 `JDK_JAVA_OPTIONS=-XX:TieredStopAtLevel=1`，结束后恢复；用于已有 fake MCP JVM 1 秒冷启动测试约束，未修改测试期限，不用于模型评测。

| 阶段 | Total | Pass | Failure/Error | Skip |
|---|---:|---:|---:|---:|
| Procurement | 807 | 796 | 0/0 | 11 |
| Replay | 380 | 380 | 0/0 | 0 |
| Full Java | 1,229 | 1,220 | 0/0 | 9 |

Package 成功，5/5 frozen fixture SHA256 完全一致。各阶段可能重复包含同一测试，不能加成独立测试总数。`JdbcProcurementCaseStorePostgresIT` 两项测试按既有 `PROCUREMENT_POSTGRES_IT=true` 环境门控跳过：本次未配置可用的一次性 PostgreSQL integration-test 环境，真实 PostgreSQL 行为未经本轮验证。未重新跑 Legacy Live Eval。

## 7. Smoke

```powershell
python -B evals/context-engineering/run.py --mode smoke --run-id context-smoke-20261003-r1
```

9/9 PASS，0 infra / 0 replacement，五项不变量均 0。Tool Calls 101；Tool Errors 1（ROB-01 预设后端故障注入）；StopReason COMPLETED 7、WAITING_APPROVAL 2。Provider input 509,310 / output 15,722 / total 525,032。

## 8. Dev

```powershell
python -B evals/context-engineering/run.py --mode dev --run-id context-dev-20261003-r1 --smoke-run context-smoke-20261003-r1
python -B evals/context-engineering/compare.py --run-id context-dev-20261003-r1 --output evals/context-engineering/reports/dev-comparison.json
```

28/28 PASS，0 fail，0 infra / 0 replacement。
StopReason：{"COMPLETED": 23, "WAITING_APPROVAL": 5}。

复用冻结 Smoke/Dev selection、world reset、driver、grader。Dev 通过 same-source Smoke gate；运行结束再次校验 manifest。五项安全不变量均设立即停止门。对 B0 与 Phase 3 的 28 个 task/trial 1，Agent prompt、tool schema、model options、max tool execution attempts 比较：**COMPARABLE_FOR_DEVELOPMENT**，mismatches=[]。

模型配置 deepseek-chat、resolved deepseek-flash；provider 不提供可锁定的 immutable model build。不是正式 milestone，也不是稳定因果收益证明。

## 9. Token / Context Delta

| 指标 | B0 Dev×1 | Phase 3 Dev×1 | Phase 4 Dev×1 |
|---|---:|---:|---:|
| Provider Input Tokens | 3,053,920 | 2,552,657 | 1,487,912 |
| Provider Output Tokens | 52,575 | 48,618 | 43,995 |
| Provider Total Tokens | 3,106,495 | 2,601,275 | 1,531,907 |
| Peak Context：任务均值 | 19,620 | 18,672 | 13,826 |
| Peak Context：任务 P95 | 31,150 | 28,627 | 20,479 |

上表 usage 包括既有 grader 定义的 Agent model 与 input guardrail；Peak Context 为每个 trial 最大单次 provider prompt usage，再统计 28 个 task 的均值/P95。未将 estimated tokens 冒称 provider usage。

以下是实际请求各轮的**累计本地估算**，不是 provider token 计费，也不是固定同一轨迹回放。instructions/schema 每轮重复计入，因此模型轮数不同也会改变这些总量：

| Task / Source | Turns | Instructions | Schema | Business | Run | Conversation | Tool result | Other | Total estimate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| REQ-03 / B0 | 2 | 3,386 | 3,926 | 0 | 0 | 123 | 371 | 30 | 7,836 |
| REQ-03 / Phase 3 | 3 | 5,079 | 5,889 | 142 | 0 | 269 | 2,307 | 144 | 13,830 |
| REQ-03 / Phase 4 | 1 | 1,693 | 1,963 | 0 | 96 | 51 | 0 | 0 | 3,803 |
| SRC-01 / B0 | 13 | 22,009 | 25,519 | 411 | 0 | 1,635 | 64,343 | 2,842 | 116,759 |
| SRC-01 / Phase 3 | 13 | 22,009 | 25,519 | 411 | 0 | 1,614 | 64,326 | 2,845 | 116,724 |
| SRC-01 / Phase 4 | 7 | 11,851 | 13,741 | 411 | 686 | 1,295 | 20,983 | 1,411 | 50,378 |
| RFQ-02 / B0 | 12 | 20,316 | 23,342 | 552 | 0 | 1,113 | 53,327 | 2,245 | 100,895 |
| RFQ-02 / Phase 3 | 10 | 16,930 | 19,416 | 552 | 0 | 953 | 39,852 | 1,580 | 79,283 |
| RFQ-02 / Phase 4 | 7 | 11,851 | 13,527 | 552 | 693 | 662 | 21,246 | 845 | 49,376 |
| E2E-04 / B0 | 12 | 20,316 | 23,556 | 414 | 0 | 1,622 | 54,055 | 2,411 | 102,374 |
| E2E-04 / Phase 3 | 5 | 8,465 | 9,815 | 276 | 0 | 597 | 14,737 | 708 | 34,598 |
| E2E-04 / Phase 4 | 6 | 10,158 | 11,778 | 276 | 583 | 733 | 11,016 | 755 | 35,299 |

E2E-04 特别说明：Phase 3 为 5 轮、模型 prompt usage 45,562；Phase 4 为 6 轮、46,195，略增。结构投影更紧凑不意味着每项任务最终 usage 必然下降。简单 REQ-03 的工具轨迹也变化，不能把全部差值归因于投影。

Context events：projection/compaction 133 次，conversation removed 0，duplicate pairs removed 3。跨轮累计 raw ToolResult 1,621,482 bytes → projected 1,156,002 bytes；内容估算 406,733 → 290,364 tokens。它们重复计算每轮可见数据，不是存储删除量。

本批 Dev 未触发旧会话裁剪，该分支由 deterministic tests 验证；重复工具对删除实际记录了 3 次。必需事实不可压缩时安全停止，不为收益丢弃证据。

## 10. Tool Calls / Latency

| 指标 | B0 | Phase 3 | Phase 4 |
|---|---:|---:|---:|
| Tool Calls | 398 | 359 | 310 |
| Mean Task Latency (s) | 17.899 | 17.970 | 16.340 |
| Median Task Latency (s) | 18.443 | 18.518 | 17.461 |
| P95 Task Latency (s) | 26.892 | 27.469 | 22.705 |
| Tool Errors | 6 | 6 | 6 |

这些都是单次 Dev×1 的方向性观察。Phase 3 的 398 → 359 不代表正式 Harness Core 效率收益；Phase 4 也不宣称稳定百分比提升。实际轨迹、provider 状态及网络延迟会影响数字。Task Latency 沿用 frozen driver 的 Runtime 任务计时，不包含 reset/reseed 与 JVM/Spring 启动开销。

## 11. Safety

| Invariant | Smoke | Dev |
|---|---:|---:|
| UnauthorizedWrite | 0 | 0 |
| DuplicateSideEffect | 0 | 0 |
| StaleApprovalExecution | 0 | 0 |
| BusinessInvariantViolation | 0 | 0 |
| FabricatedCriticalFact | 0 | 0 |

业务结果保持通过；RFQ 原有服务器权限、审批绑定、CAS、幂等逻辑未修改。未通过调 Prompt / Tool Description / grader 或替换失败来刷绿。

## 12. Cost

实际执行 Smoke 9×1 + Dev 28×1，共 **37 个有效 paid trial**，0 infra / 0 replacement；所有 attempt 保留。Provider input 1,997,222 / output 59,717 / total 2,056,939。

没有运行 Holdout、40×3、Final Blind；没有有效失败重跑。不提供未经账单核实的人民币或美元成本。

证据目录 `evals/context-engineering/reports/` 包含 pre-change audit、focused/legacy/smoke/dev results、原始 evidence.zip、逐文件 SHA256 provenance 和最终 scope 核验。源码 zip 不包含用户 Handler 内容，freeze manifest 仍记录该文件的测试时哈希。

## 13. Git

Index 为空；不 commit/push/tag。Handler 用户注释修改仍独立 unstaged，SHA256 `8F3EEA10DF78F9E8F5010A4D2CE72EE049962FDC4E3B4D98CFCD7518CE2E7F6D` 与开始一致。Frozen baseline 文件不改写；评测源码快照及 manifest 已归档。

最终 `git diff --check` exit 0；Smoke、Dev、最终 production/runner manifest 完全一致，实际模型可见工具数仍为 15 / 14。完整 `git status --short` 见 `evals/context-engineering/reports/git-status.txt`；逐项保护校验见 `scope-verification.json`。工作区剩余 Phase 4 文件与用户原有 Handler 注释修改，没有生成的 class/JAR、运行时凭据或无关阶段文件进入暂存区。

## 14. Scope

仅 Context Engineering / Tool Result Projection。未实现 Dynamic Tool Loading、Multi-Agent、Supervisor/Subagents 或 Memory redesign；没有更改业务决策、RFQ guard、Agent instructions、Tool Descriptions、model options、重试策略或 grader。完成验证后停止，等待人工 Review。
