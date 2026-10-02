# Legacy Procurement Regression Suite v1

保护 Procurement V0，复用现有 JUnit/Surefire 和 Eval，不引入新测试框架或生产能力。代码、数据、边界见 [V0 架构](../../../docs/procurement-v0-baseline.md)；本次结果见 [实测报告](../../../docs/baselines/procurement-v0-test-report.md)。

## 一键入口

在仓库根目录，使用 Java 17、Maven、PowerShell 7：

```powershell
# 确定性采购测试 + 显式历史 Artifact Replay + package
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1
# 加全仓 Java 测试
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite
# 单次 Live Eval；先在当前进程环境配置 DEEPSEEK_API_KEY
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite -Live
# 可选真实 PostgreSQL CAS IT；仅指向可丢弃的测试数据库
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -Postgres
```

`-Postgres` 要求 `AGENT_STORAGE_POSTGRES_URL`，用户名/密码沿用 `AGENT_STORAGE_POSTGRES_USERNAME/PASSWORD`。现有 IT 会建/迁移 Case 表并清理 `procurement-cas-it-%` 测试行，因此不得用生产库。没有该开关时 IT 会被显式选择但 skip。`-Live` 会调用配置的外部模型，使用已有 Native Tool Calling Eval 和内存测试存储；不执行 RFQ，不代表 HTTP UI/真实 PostgreSQL 验收。无开关时脚本在子进程中关闭两种 opt-in，退出恢复原环境。

脚本先校验 [fixtures.json](fixtures.json) 的五个原始字节 SHA-256，失败即停止；不迁移或复制 fixture。采购资源必须为 UTF-8/LF，`.gitattributes` 固定该规则。已有 Windows checkout 如果曾被自动转为 CRLF，可仅将清单中的文件保存为 UTF-8 无 BOM、LF 后再次检查哈希；不要修改冻结 Policy 的哈希来掩盖问题。

输出位于 ignored 的 `target/procurement-v0/v0-<timestamp>-<id>/`：

- `environment.json`：SHA、branch、dirty status、Java/Maven/OS/PowerShell、启用的环境门控和资源身份；不输出密钥。
- `*-command.txt`、`*.log`：每阶段完整 Maven 命令和原始日志。
- `*-surefire/`、`results.json`：仅按本次唯一 reportNameSuffix 收集 XML，计算总数、pass、failure、error、skip，不混入旧报告。
- `procurement-evaluation/`、各阶段 `*-evaluation/`：现有固定路径产物的归档；旧内容先存入 `previous-*`。Replay 要求本轮 Runtime artifact 生成测试通过且产物时间属于本轮。
- `procurement-live-eval/report.json`：仅在本轮 Live 实际写出报告时归档。正式 v3/Comparator 验收使用现有测试临时目录，不承诺所有临时文件长期保留；其断言证据在 Surefire。

任何阶段 Maven 非零都会使脚本最终非零；不会把 Live 失败隐藏为套件成功。JUnit pass 是“断言符合当前契约”，可能包含对业务评分 FAIL 的断言，不能当作任务成功率。各阶段有重叠，不累加为独立测试数。Build 行 0 tests 表示 `-DskipTests package`，不是遗漏前面的测试。

## 覆盖映射

以下类均位于 `src/test/java/com/agent/platform/procurement/`，除非另有说明。

| Legacy 行为 | 现有测试与保护内容 |
| --- | --- |
| 正常推荐 | `ProcurementSourcingMvpTests`、`ProcurementAgentRuntimeE2ETests#modelToolResultAndReplanCompleteRecommendationLoop`：Patch → Search → Finalize → D/B canonical 事实 |
| 硬约束拒绝 | `ProcurementSourcingMvpTests`、`ProcurementBenchmarkContractTests`：排除、预算、交期、GPU、无 eligible |
| Finalization | `ProcurementRecommendationFinalizeTests`：由 Agent 选择、selected/alternative OFFER、tradeoff、非法选择拒绝 |
| Evidence Validation | `ProcurementEvidenceTrustTests`、`McpProcurementDataProviderTests`：provenance/ID 篡改、MCP 事实输入校验 |
| stale Case/version | `ProcurementRecommendationFinalizeTests#finalizerFailsClosedForStaleCaseVersion`；`ProcurementCasePatchTests#concurrentPatchesUseVersionCasAndDoNotSilentlyOverwrite`；RFQ stale prepared request 拒绝 |
| RFQ 必须审批 | `ProcurementRfqHitlTests#realDefaultRuntimeWaitsForApprovalThenResumesSameRunAndDoesNotRetryCommittedRfq`、`#deniedApprovalDoesNotInvokeGateway` |
| Approval Binding | `ProcurementRfqHitlTests#preparerRebuildsExactAuthoritativeRequestAndDiscardsModelFields`；`src/test/java/com/agent/platform/runtime/DefaultAgentToolRuntimeApprovalBindingTests.java` 由全仓测试覆盖 |
| RFQ 幂等 | `ProcurementRfqHitlTests#simulatedGatewayIsIdempotentByApprovalBoundKey`；Patch 另有相同/历史 input ID 去重测试 |
| Resume/Recovery | `ProcurementRfqHitlTests`：same-Run resume、RUNNING resolver 只查 receipt；全仓覆盖 `DefaultAgentToolRuntimeReconciliationTests` |
| 防重复业务副作用 | `ProcurementRfqHitlTests#commitThenThrowIsReconciledWithoutSecondCreate`、`#uncertainRfqIsNotRetriedWhenRuntimeAllowsMultipleAttempts`；不确定状态保留 MANUAL_REVIEW |
| Specialist/Trace | `ProcurementAdaptiveMultiAgentTests`、Runtime E2E：child isolation、advisory、native parallel batch、usage provenance、单候选不委派 |
| MCP Integration | `McpProcurementDataProviderTests`、`ProcurementAgentRuntimeE2ETests#mcpBackedRuntimeKeepsTheThreeToolLoopAndUsesInternalStdioSource`：独立 child JVM/真实 stdio、注册与 Profile 过滤 |
| PostgreSQL Integration | `JdbcProcurementCaseStorePostgresIT`：真实 SQL CAS 竞争与旧 version 迁移；需要环境门控，skip 不表示通过 |
| 公开入口 | `PublicProcurementRunTests`、`ProcurementOnlyWorkbenchRoutingTests`：采购边界、身份和路由 |
| Eval | `ProcurementDeterministicGraderTests`、Answer/Policy/Relation/Completeness/Aggregation/V3、Comparison Inputs/Metrics/Reports：冻结评分和可信比较口径 |

## 核心 Demo 与手工命令

无需数据库或 API Key 的推荐、只读 MCP、Specialist 与模拟 RFQ demo：

```powershell
mvn -B '-Dtest=ProcurementAgentRuntimeE2ETests,ProcurementRfqHitlTests' test
```

这是 scripted model + 实际 Runtime/Handler 的 demo；RFQ 使用 simulated gateway 和内存存储。真实模型推荐演示由 `-Live` 的四个固定输入承担。HTTP Workbench + 持久数据库的端到端 UI 演示本次未验证，不能由上述命令替代宣称通过。

统一脚本核心测试选择器：`-Dtest=*Procurement*Tests,JdbcProcurementCaseStorePostgresIT`。Replay 使用本轮生成的 `target/procurement-evaluation/runtime-artifact.json`，同时设置 `evaluation.artifact`、`answer.artifact`、`answer.v2.artifact`、`relation.artifact`、`completeness.artifact`、`answer.v3.artifact` 六个已有属性，避免默认九项历史重评静默 skip。每次完整命令自动写入 `*-command.txt`。

## 未覆盖边界

当前 V0 暂无稳定自动化覆盖：真实 ERP RFQ、真实下游跨进程幂等与断电恢复、真实供应商履约、生产环境全链路 UI 验收。测试用 Gateway 计数证明当前契约，不代表真实供应商外部副作用已经发生。PostgreSQL IT 有实现，但本次环境缺少数据库，实际执行状态以报告为准。

发生失败时先保留资源哈希、Run/Case/报告身份与断言，不自动修改 Benchmark、Prompt 或评分规则，也不反复跑 Live 直到通过。本阶段结束后停止，等待人工 Review。
