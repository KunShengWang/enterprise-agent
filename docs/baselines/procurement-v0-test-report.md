# Procurement V0 测试基线报告

## 结论

Legacy 确定性采购回归、真实 stdio MCP 集成、显式 Artifact Replay、全仓 Java 测试及可执行 JAR 打包通过。**历史 Live 业务选择结果为 4/4、Legacy grader 为 0/4，真实 PostgreSQL IT 跳过**，本报告不是“所有验收全绿”的声明。未修改任何生产业务逻辑、Prompt、评分规则或 fixture 业务内容。

V0 用于 Legacy Regression，不是未来 Harness B0。测试 pass 与业务评分 pass 分开统计；各阶段重复选择测试，不累加成独立样本数。

## 运行身份与环境

| 项目 | 本次事实 |
| --- | --- |
| 日期/时区 | 2026-10-02，Asia/Shanghai（UTC+08:00） |
| Git branch | `main` |
| Source Baseline SHA | `2e9676afef5b7af1cb887760c608337c92e7c9c7`：Phase 0 开始前的已提交源码版本，不包含本轮 baseline 文档、脚本或 `.gitattributes` |
| Test Run HEAD | `2e9676afef5b7af1cb887760c608337c92e7c9c7`：此前两次基线运行的 `environment.json.commit` 与本次提交前 `git rev-parse HEAD` 均为此值；与 Source Baseline SHA 相同，但含义不同 |
| 初始工作区 | **dirty**：`ProcurementToolHandler.java` 已有 12 行中文注释，原样保留 |
| Working Tree State | **dirty**：原有 Handler 注释与未提交 Phase 0 文件共同存在。首次主运行启动时已有 `.gitattributes`、回归入口及 LF 恢复，文档在运行期间继续撰写；实际启动清单以各次 `environment.json.gitStatus` 为准。fixture 改回 LF 后 Git blob 与 HEAD 一致，无业务数据 diff |
| Uncommitted Phase-0 Changes | **yes**：测试执行了包含未提交 Phase 0 配置/入口的 working tree，不能称为纯 clean HEAD 的验证；暂存不改变此事实 |
| Java | Oracle Java 17.0.14，`17.0.14+8-LTS-191`，HotSpot 64-bit |
| Maven | Apache Maven 3.9.12；Surefire 3.5.6；Spring Boot 4.1.0 |
| OS / Shell | Windows 10 amd64，10.0.19045；PowerShell 7.6.5 |
| Locale / 编码 | zh_CN；Java 平台编码 GBK；冻结资源 UTF-8/LF；Git `core.autocrlf=true` |
| 数据源 | synthetic；工作站 scenario 固定，五项资源 SHA-256 见 [fixtures.json](../../evals/regression/legacy-v1/fixtures.json) |
| PostgreSQL | 未配置 `AGENT_STORAGE_POSTGRES_*`，未启用 `PROCUREMENT_POSTGRES_IT`；本地 5432 无监听，Docker daemon 不可用；没有尝试用生产数据库替代 |
| Live | 进程环境已有 API Key（不记录值）；显式启用 `PROCUREMENT_LIVE_EVAL=true`，只运行一次原有四 Case Eval |
| 模型身份 | 配置/报告为 `deepseek-chat`，HTTP response `model` 观察为 `deepseek-flash`；不可假设该 alias 后端永远不变 |
| Git 操作 | 未 commit、未 tag、未 push、未触发 CI；最终新增文件等待人工 Review |

主要实测归档：`target/procurement-v0/v0-20261002-000655-13c369f9/`。修复前日志：`target/procurement-v0/pre-change/`。日志、Surefire XML、artifact、JAR 全部位于 ignored `target/`，未加入版本控制。`mvn clean` 会删除这些本地产物，需长期保存时另行归档；本 Markdown 和资源指纹是提交用记录。

当前尚无包含全部 Phase 0 文件的最终 commit SHA，不填写或推测该值。人工 Review 并完成最终 commit 后，可将 `procurement-v0-baseline` tag 指向该提交，以确定完整 Legacy Baseline；本次收尾不创建 commit/tag。

## 提交前收尾复跑（2026-10-02，最新确定性结果）

准确入口命令：

```powershell
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite
```

本次归档：`target/procurement-v0/v0-20261002-091712-4a8cbaf9/`。`environment.json` 记录启动时间 `2026-10-02T09:17:12.9124624+08:00`，Test Run HEAD 为 `2e9676afef5b7af1cb887760c608337c92e7c9c7`，Working Tree State 为 dirty，Uncommitted Phase-0 Changes 为 yes，`live=false/postgres=false/fullSuite=true`。执行期间仅报告文字及 Git 暂存状态继续更新，没有测试/生产代码或 fixture 变更。

| 阶段 | Total | Pass | Fail | Error | Skip | Exit |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Procurement（含显式选择的 PG IT） | 795 | 784 | 0 | 0 | 11 | 0 |
| 显式 Replay | 380 | 380 | 0 | 0 | 0 | 0 |
| 全仓 Java `mvn test` | 1170 | 1161 | 0 | 0 | 9 | 0 |
| `mvn -DskipTests package` | 0 | 0 | 0 | 0 | 0 | 0 |

脚本整体 exit=0；各阶段完整参数、日志与独立 Surefire XML 在该归档的 `*-command.txt`、`*.log`、`*-surefire/`，计数在 `results.json`。其中全仓实际命令为：

```powershell
mvn '-B' '-Devaluation.codeRevision=2e9676afef5b7af1cb887760c608337c92e7c9c7' '-Dsurefire.reportNameSuffix=v0-20261002-091712-4a8cbaf9-full' 'test'
```

`-FullSuite` 已实际执行全仓 `mvn test`，无需另行重复一次。其余 Maven 参数与下方历史命令一致，只替换本次 suffix 并省略 live 阶段。本次不重跑 Live，原归档及固定路径 `procurement-live-eval/report.json` SHA-256 均保持 `206fb9ed74b2fc85cfc677dc1ddc34cd270f1aa63ea36c6af93987905fda8fcc`。

采购 11 skip 仍为 PG IT 2 + artifact gate 9，Replay 已实际执行后者；全仓 9 skip 为相同 artifact gate。PG XML 对两项测试给出相同原因：`Environment variable [PROCUREMENT_POSTGRES_IT] with value [false] does not match regular expression [true]`。5 个资源通过执行前后 SHA-256 检查，且原始 Git blob 与 HEAD 一致；没有新增 fixture/hash 回归。未观察到本次确定性 flaky。

## 首次冻结测试结果（历史记录）

Fail 列包含 assertion failures + errors，另列 Error 明细避免混淆。

| 阶段 | Total | Pass | Fail（含 Error） | 其中 Error | Skip | Exit |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 修改前采购测试（CRLF checkout） | 795 | 532 | 252 | 150 | 11 | 1 |
| 固定 LF 后采购套件（含显式 PG IT） | 795 | 784 | 0 | 0 | 11 | 0 |
| 显式 Artifact Replay / 相关评分回归 | 380 | 380 | 0 | 0 | 0 | 0 |
| Live Model Eval（1 个 JUnit 包含 4 Case） | 1 | 0 | 1 | 0 | 0 | 1 |
| 全仓 Java `test` | 1170 | 1161 | 0 | 0 | 9 | 0 |
| `-DskipTests package` | 0 | 0 | 0 | 0 | 0 | 0 |

最终脚本增加逐阶段 artifact 归档和当前产物时间校验后，另运行一次默认入口（不启用 Live、不再跑全仓）：

```powershell
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1
```

归档 `target/procurement-v0/v0-20261002-001112-ad90cda1/`：采购 795/784 pass/0 fail/11 skip，Replay 380/380 pass/0 fail/0 skip，package 成功，脚本 exit=0。该次验证了最终交付的回归脚本；两次确定性运行不计作独立业务样本。具体 Maven 参数与主运行相同，仅省略 live/full 阶段并把 suffix 中的运行 ID 替换为 `v0-20261002-001112-ad90cda1`，完整命令仍保存在该次 `*-command.txt`。

全仓默认 `test` 不选择 `*IT`；因此其 9 skip 不包含 PostgreSQL 的 2 项。采购套件 11 skip = PostgreSQL 2 + 历史重评 gate 9。显式 Replay 随后设置全部已有 artifact 属性，9 个 gated 用例实际执行，380 项无 skip。PostgreSQL 2 项仍未实际访问数据库，不记为 pass。

### PostgreSQL Integration Test skip 明细

PostgreSQL integration tests skipped because the required PostgreSQL integration-test environment was not configured/available during this baseline run.

对应测试类：`src/test/java/com/agent/platform/procurement/JdbcProcurementCaseStorePostgresIT.java`。

| Test name | 验证目标 | 本轮状态 |
| --- | --- | --- |
| `onlyOneConcurrentWriterCanUpdateTheSameCaseVersion` | 真实 PostgreSQL 同版本并发 CAS 仅一个写入成功 | SKIP |
| `schemaMigrationAlignsLegacyJsonVersionAndRejectsStaleCasWriter` | 旧 JSON/列 version 迁移与 stale CAS 拒绝 | SKIP |

类级 gate 是既有的 `@EnabledIfEnvironmentVariable(named = "PROCUREMENT_POSTGRES_IT", matches = "true")`。本轮未传 `-Postgres`，现有回归脚本将该变量设为 `false`；Surefire 因 gate 不满足而跳过，两项均未连接数据库。未配置/不可用的数据库环境是未启用 gate 的原因，不是本轮已尝试数据库连接后得到的测试失败。该注解在 Source Baseline SHA 中已经存在，属于 **pre-existing test behavior**；本次不改测试、gate 或数据库配置来消除 skip。

构建结果：`target/enterprise-agent-0.0.1-SNAPSHOT.jar` 经 Spring Boot repackage 成功。前端未改动，因此未运行前端测试。没有把仅打包成功说成持久化服务/浏览器启动成功。

## 修改前失败与唯一最小修复

初次运行在新增配置前，102 assertion failures + 150 errors，共 252 项 failures/errors。这些失败主要由 checkout 后 CRLF/LF normalization 改变 frozen fixture bytes、触发 byte-level hash mismatch 引起；恢复 canonical LF representation 后 deterministic regression 恢复通过，**不是 252 个采购业务功能 Bug**。根因证据：

1. `git ls-files --eol` 显示采购数据/测试资源为 `i/lf w/crlf`；`core.autocrlf=true`。
2. CRLF benchmark SHA-256 为 `202579c7636b8824fe34662c75386aa562321118b7e8e1e56a2a3e9aa1583c1e`，与原 Policy 固定的 SHA 不同。
3. 仅将换行恢复 LF，benchmark SHA 变为 `f0c0763148783ab9e461ccf6a21fe86ba81e4a6681a2f0f423075f9b0c37f6b1`；scenario SHA 为 `a0b6760e51e71ddbcc23e9f79c7ab1266cd52b9e197620204067dd4784c3d4b8`，两者精确匹配原 Policy。
4. Policy 的 byte-hash gate 在 CRLF 下阻断，并连带触发 coverage/completeness/comparison 错误；同一套件恢复 LF 后全部已执行项通过。

受影响的既有类及修改前 failure/error 数：

| 类（省略 Procurement 前缀） | Failure | Error |
| --- | ---: | ---: |
| AnswerAggregationTests | 42 | 16 |
| AnswerCompletenessTests | 16 | 37 |
| AnswerEvaluationV3Tests | 7 | 35 |
| AnswerPolicyTests | 6 | 8 |
| AnswerV3ReplayTests | 8 | 2 |
| ComparisonInputsTests | 14 | 5 |
| ComparisonMetricsTests | 9 | 6 |
| ComparisonReportsTests | 0 | 41 |

这些是修改前可复现的 checkout/字节身份问题，不是本轮引入。最小修复只新增 `.gitattributes` 的两条 procurement 资源规则（`text eol=lf`），并把本地五个文件恢复为原 Git LF 字节。已逐一验证 `git hash-object --no-filters` 等于对应 `HEAD:<path>`；无 fixture 语义 diff、无 Policy 哈希放宽、无生产修复。

**Flaky**：本轮未识别出确定性测试 flaky；修复前失败可由资源换行解释，不能标成随机 flaky。真实模型只跑一次，不能据此估计随机波动率；其失败不是通过多次重跑消除的。

## Live Eval：Legacy Live Eval grading mismatch

**Business decision result: passed for all 4 scenarios**，具体指 Eligibility 与 Recommendation Outcome 均为 4/4（包含正确无推荐场景），不扩展为完整业务闭环或全文回答验收。

**Legacy grader result: 0/4 due to productCategory exact-match mismatch**。这是 Legacy Live Eval grading mismatch，不能描述成“4 个采购任务全部失败”。本次提交前收尾保留原始结果，不重跑 Live，也不修改 Agent Prompt、category 输出、grader 或 benchmark expected answer。

四个 Run 都为 COMPLETED。每 Case 唯一报告的 requirement mismatch 为 `productCategory`。现有 `ProcurementLiveModelEvalIT.requirementMismatchFields` 使用 `Objects.equals` 比较该自由文本字段，expected 为“计算工作站”；报告未保存完整 actual Case 原文，因此不从日志相似文本补造 actual 值，也不擅自改成语义通过。

| Case | Run | Requirement | Eligibility | Recommendation outcome | Evidence |
| --- | --- | --- | --- | --- | --- |
| delivery_priority_two_eligible | `f6345784-2ace-442e-a6f8-d7aef0caf963` | FAIL：productCategory | PASS | PASS：D | PASS |
| price_priority_two_eligible | `afd7e29d-5992-4dfb-a321-129b9c9b3d47` | FAIL：productCategory | PASS | PASS：B | PASS |
| single_eligible_after_exclusions | `66ee2937-b3c6-44a9-8edb-28cb278f2e54` | FAIL：productCategory | PASS | PASS：D | PASS |
| no_eligible_under_hard_constraints | `07c773e9-9ace-4067-875c-dbd1eb6bc2f5` | FAIL：productCategory | PASS | PASS：无推荐 | N/A |

原冻结口径：总成功 **0/4**，需求提取 **0/4**，Eligibility **4/4**，Recommendation Outcome **4/4**（其中 3 次推荐、1 次正确无推荐），Evidence **3/3 applicable**。保留这几个分母，不把 N/A 视为 pass。

本轮未改生产 Prompt 或 Live grader；这是现有实现/口径下的新观测失败。没有在修改前做同一真实模型运行，故不声称已经通过成对实验证明该 Live 失败是 pre-existing。它不影响确定性 Legacy Suite 建立，但阻止把 Live 总评写成通过。

归档 Live report 原始文件 SHA-256：`206fb9ed74b2fc85cfc677dc1ddc34cd270f1aa63ea36c6af93987905fda8fcc`。数据来自原报告，不转换成缺失原始工具证据的 v3 artifact，也不参与正式跨模型 Comparator。

## 当前可以可靠记录的指标

| 指标 | 本次值/来源 | 解释边界 |
| --- | --- | --- |
| Task Success | Live 冻结总评 0/4；scripted structured artifact 1/1 PASS | 不等于生产任务成功率或完整回答正确率 |
| Recommendation Correctness | Live 原口径 outcome 4/4；选中供应商 D/B/D/无 | 不证明全文语义正确 |
| Tool Calls | 保存的 scripted runtime budget：3；modelCalls=4 | Scripted 固定轨迹；Live report 不输出独立 tool-call 总数，Live 该指标 V0 当前不可用 |
| Token Usage | Live parent + provenance-backed child：input=102518、output=5621；modelCalls=22、childRuns=4 | 原 Runtime budget 汇总；单次观测，不是服务端账单或模型提升证据 |
| Scripted Token Usage | input=400、output=160 | 测试模型预设值，不能冒充真实模型资源消耗 |
| Latency | 可比较的逐 Case/ERP latency：**V0 当前不可用** | Surefire Live elapsed=38.09s 含启动与测试，不用作模型推理延迟基准 |
| Approval Behavior | RFQ 测试：批准前 create=0；批准后 same Run、resumeCount=1、attempt=1、create=1；重复 resume 仍 create=1 | 实际通过的 scripted Runtime/Gateway 计数断言，不是 Live Benchmark 指标 |
| 不确定副作用 | create=1、find=1、MANUAL_REVIEW，max attempts=3 时仍不重放 | 当前 simulated/fault-injection 契约 |
| 完整回答 v3 | 保存的 scripted Runtime 重评为 FAIL，缺 delivery_advantage / budget_compliance | 相应测试 **PASS 是正确保留 FAIL**，不是回答完整性通过 |
| 正式比较资格 | Comparator 合成测试通过；没有本轮真实两组实验 Manifest | 必须整体 COMPARABLE 才统计，零分母保留 NOT_COMPUTABLE；无提升率/胜率 |

首次 scripted Artifact 保存在 `.../replay-evidence/runtime-artifact.json`，文件 SHA-256 为 `81897bb93384ad3390503c981efee47458173ad6b31bcd531969f2b42d403678`；文件字节哈希不同于内部 canonical artifactSha256，不混用。全仓测试会生成另一个 Run 的同名 artifact，已保留前一份供 Replay 身份核对。后续脚本为各阶段独立归档。

## Regression Coverage、Demo 与 Known Gaps

10 项要求均有已有自动化覆盖：正常推荐、硬约束拒绝、Finalize、Evidence、stale Case/version、审批要求、Approval Binding、幂等、Resume/Recovery、防重复副作用。逐项类/方法映射见 [Suite README](../../evals/regression/legacy-v1/README.md)。

实际采购集成包含真实 Runtime → Registry/Profile → ToolRuntime → Handler、独立 JVM stdio MCP server；不是只测单个函数。`ProcurementAgentRuntimeE2ETests` 的 6 项和 `ProcurementRfqHitlTests` 的 22 项均通过，Legacy 推荐与模拟 RFQ 核心 demo 可在该测试环境运行。Live 三个可推荐 Case 产生预期供应商，另一个正确无推荐，但保留前述总评失败。

已知缺口：真实 PostgreSQL CAS/迁移 IT 2 项未执行；未启动 HTTP Workbench + PostgreSQL 的完整 UI Demo；无真实 ERP RFQ、跨进程外部 exactly-once、断电恢复或生产供应商履约验证。当前 V0 暂无稳定自动化覆盖这些外部业务边界。本轮没有前端改动，也没有 CI 结果。

## 修改范围与结束状态

新增三份文档：本报告、[V0 架构基线](../procurement-v0-baseline.md)、[V2 升级方案](../procurement-v2-upgrade-plan.md)。新增 `evals/regression/legacy-v1/{README.md,fixtures.json,run.ps1}` 提供资源锁定、覆盖映射和复现入口；新增 `.gitattributes` 修复字节级基线在 Windows 的稳定性。

没有新增/修改生产 Java、前端、fixture schema、供应商数据、Benchmark、Policy、模型 Prompt。用户原有 Handler 注释保留且不纳入本轮成果。未新增 ERPNext、Procurement V2、Multi-Agent 或 Harness；现有 Specialist 的运行只是回归验证。到此停止，等待人工 Review，不开始 Phase 1。

### 提交范围审计

Phase 0 开始前的首次 `git status --short` 已显示 `ProcurementToolHandler.java` 修改；首次 diff 即为当前这 12 行注释。它们不属于建立 baseline 的必要改动，不删除、不恢复、不混入 Phase 0 提交。提交前只按以下精确清单暂存，Handler 保留为 unstaged 用户改动：

```text
.gitattributes
docs/procurement-v0-baseline.md
docs/procurement-v2-upgrade-plan.md
docs/baselines/procurement-v0-test-report.md
evals/regression/legacy-v1/README.md
evals/regression/legacy-v1/fixtures.json
evals/regression/legacy-v1/run.ps1
```

`.gitattributes` 维持原有窄范围，无本次规则改动：

```gitattributes
/data/procurement/** text eol=lf
/src/test/resources/procurement/** text eol=lf
```

两目录当前只有冻结采购数据/资源，没有 `.bat/.cmd/.ps1`；`git check-attr` 确认 `pom.xml`、`src/main/resources/application.yaml`、回归 PowerShell 脚本及目录外 Windows 脚本路径不受这些规则影响。仓库没有其他已跟踪 `.gitattributes`，也未发现本地额外 attributes 覆盖；没有全仓 `* text eol=lf`、全仓 renormalize 或无关换行 diff。未来若往这两个 fixture 目录添加脚本，应重新检查属性适用范围。

推荐 Commit Message：`chore: freeze legacy procurement baseline`。本轮未创建该提交。

## 完整执行命令

工作目录：`D:\JDK\IDEA\java_reinforcement_learning\enterprise-agent`。

修改前命令（输出重定向到 `target/procurement-v0/pre-change/maven.log`）：

```powershell
mvn -B '-Dtest=*Procurement*Tests,JdbcProcurementCaseStorePostgresIT' '-Dsurefire.reportsDirectory=target/procurement-v0/pre-change/surefire' test
```

此 Maven 属性没有改变该项目实际 Surefire 输出位置；初次 XML 从默认 `target/surefire-reports` 中复制，并仅统计无历史 suffix 的本次 795 项，不能混入目录中已有的旧报告。后续统一使用实测有效的唯一 `surefire.reportNameSuffix`。

主验证命令（实际 exit=1，原因仅为 Live 失败，脚本仍继续执行全仓与 build）：

```powershell
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite -Live
```

以下 Maven 命令来自该次归档的 `*-command.txt`；Live 阶段的 opt-in 由脚本设置并在退出时恢复：

```powershell
mvn '-B' '-Devaluation.codeRevision=2e9676afef5b7af1cb887760c608337c92e7c9c7' '-Dsurefire.reportNameSuffix=v0-20261002-000655-13c369f9-procurement' '-Dtest=*Procurement*Tests,JdbcProcurementCaseStorePostgresIT' 'test'

mvn '-B' '-Devaluation.codeRevision=2e9676afef5b7af1cb887760c608337c92e7c9c7' '-Dsurefire.reportNameSuffix=v0-20261002-000655-13c369f9-replay' '-Dtest=ProcurementArtifactReplayTests,ProcurementAnswerReplayTests,ProcurementAnswerPolicyTests,ProcurementAnswerRelationTests,ProcurementAnswerCompletenessTests,ProcurementAnswerAggregationTests,ProcurementAnswerV3ReplayTests,ProcurementComparisonMetricsTests,ProcurementComparisonReportsTests' '-Devaluation.artifact=target/procurement-evaluation/runtime-artifact.json' '-Danswer.artifact=target/procurement-evaluation/runtime-artifact.json' '-Danswer.v2.artifact=target/procurement-evaluation/runtime-artifact.json' '-Drelation.artifact=target/procurement-evaluation/runtime-artifact.json' '-Dcompleteness.artifact=target/procurement-evaluation/runtime-artifact.json' '-Danswer.v3.artifact=target/procurement-evaluation/runtime-artifact.json' 'test'

mvn '-B' '-Devaluation.codeRevision=2e9676afef5b7af1cb887760c608337c92e7c9c7' '-Dsurefire.reportNameSuffix=v0-20261002-000655-13c369f9-live' '-Dtest=ProcurementLiveModelEvalIT' 'test'

mvn '-B' '-Devaluation.codeRevision=2e9676afef5b7af1cb887760c608337c92e7c9c7' '-Dsurefire.reportNameSuffix=v0-20261002-000655-13c369f9-full' 'test'

mvn '-B' '-Devaluation.codeRevision=2e9676afef5b7af1cb887760c608337c92e7c9c7' '-Dsurefire.reportNameSuffix=v0-20261002-000655-13c369f9-build' '-DskipTests' 'package'
```
