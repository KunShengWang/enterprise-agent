# MCP Tool Contract 到 Harness ToolEffect：修复与 ROB-01 验收

日期：2026-10-05。分支：`feat/procurement-workflow`。HEAD 保持 `2f649d6cbc56cea1803ffa6e5a3561d48a53d776`，在已有未提交 Workflow/Retry 源码上工作。历史失败 trial 和报告不覆盖。

## 1. Metadata exact loss point

修改前实际进程返回的 `procurement_get_item`：

```json
{"annotations":{"destructiveHint":false,"idempotentHint":true,"readOnlyHint":true},"name":"procurement_get_item"}
```

raw `_meta` absent。完整原始响应保存为 `raw-tools-list-before.json`。因此 A：server 已声明只读；B：在 `StdioMcpToolGateway.toToolDefinition` 丢失；C：服务端缺声明的假设不成立。上轮确定性测试显式构造 READ_ONLY definition，不代表真实 discovery 先前已知道只读。

项目实际链：`ProcurementMcpService.tools` → `ProcurementMcpServer` 的 `tools/list` → `StdioMcpToolGateway` 原始 Map → 项目 `ToolDefinition` / `LocalToolRegistry` → `ToolRuntimeContract.from` → `ToolRetryPolicy`。当前没有独立 ToolEffectResolver；`ToolRuntimeContract.from` 是已有 canonical view。本项目这条路径不经过 McpSchema.Tool 或 Spring MCP callback，不能将这些不存在的层描述成实际丢失点。

## 2. Spring AI 2.0.0 exact behavior

直接检查 Maven Central 的 `spring-ai-mcp:2.0.0:sources` 与 `spring-ai-model:2.0.0:sources`；版本与 pom 的 `spring-ai.version=2.0.0` 一致。下载仅用于审计，没有添加项目依赖。

- `SyncMcpToolCallbackProvider.getToolCallbacks` 从 `mcpClient.listTools().tools()` 取得完整 `McpSchema.Tool`，传给 callback builder。
- `SyncMcpToolCallback` 私有 `tool` 字段保存原始对象；公开 `getToolDefinition()` 调用 `McpToolUtils.createToolDefinition`。
- 该转换仅构造 name、description、inputSchema；Spring `ToolDefinition` 不承载 annotations。
- callback 没有覆盖 `getToolMetadata()`，沿用 `ToolCallback` 默认 `ToolMetadata.builder().build()`；该接口的行为字段是 `returnDirect`，没有 MCP annotations。
- callback call 路径的 `ToolContextToMcpMetaConverter` 构造的是 **CallToolRequest meta**，不等于 tools/list 的定义 `_meta`，也不自动把只读声明传进 Harness。

Spring MCP 模块不在项目当前运行 classpath；本轮未 patch dependency、未新增反射访问。精确版本源码、URL 与 SHA256 保存在归档 `source-audit.json` 和 `evidence.zip`。

## 3. Official annotation semantics

依据官方协议仓库 `schema/2025-11-25/schema.ts` 的 `ToolAnnotations`：readOnlyHint 表示不修改环境；annotations 全部是 hints，未知服务器的 hints 不能作为安全决策依据。openWorldHint 表示交互世界是否开放，不是外部写标志；idempotentHint 描述重复调用附加效果，不会将写工具变成读取。

本轮只消费明确的 Boolean `readOnlyHint=true` 作为受控 effect 输入，其他三个 Boolean hints 仅保留诊断，不改变 policy。

## 4. Trusted MCP boundary

新增连接级本地配置 `trusted-procurement`，默认 false。仅项目 ERPNext profile 的自有连接显式设置 true；trial driver 对自己启动的 `ProcurementMcpServer` 同样设置。

adapter 同时要求本地 flag=true、规范化 serverId=`erpnext`、serverName=`erpnext`、prefix=`mcp.erpnext.`。名称或远程 self-report 单独不构成信任，重复连接 id 也不自动继承。该标记由本地操作者配置，绝不从 tools/list annotations、`_meta`、description 或工具名称读取。不是 PKI 或远端身份认证；若操作者更换此连接的程序为第三方，应撤销 opt-in。

只有四个已知 Boolean hints 被复制到嵌套只读 `mcpToolAnnotations`；`mcpTrustedProcurement` 来自本地决策；远端 `_meta` 不合并进 canonical metadata，不能注入 effect 或 trust。

## 5. Canonical effect resolution

trusted + explicit Boolean readOnlyHint=true → 构造项目 `ToolDefinition.metadata.effect=READ_ONLY` → 现有 `runtimeContract().effect()` → 未改动的 RetryPolicy。

missing/false/string-true/untrusted → 不授予 READ_ONLY → 保留现有保守 EXTERNAL_SIDE_EFFECT。没有按 get 前缀、description、HTTP method 或模型输出猜测 effect。MCP source 与 effect 分离：相同 MCP source 可有 READ_ONLY 和 EXTERNAL_SIDE_EFFECT。没有新增 resolver、复制服务端 tool catalog，或扩展 RetryPolicy。

## 6. Read / write matrix

| 实际操作 | MCP readOnlyHint | 修复后 Harness effect / 行为 |
|---|---|---|
| Item list/get | true | READ_ONLY |
| Budget query | true | READ_ONLY |
| Supplier list/get、item supplier query | true | READ_ONLY |
| Purchase history / performance | true | READ_ONLY |
| Requisition list/get | true | READ_ONLY |
| 内部 search_suppliers / get_offers bridge | true | READ_ONLY；原有模型可见性不变 |
| Case mutation | 不适用，LOCAL metadata readOnly=false / sideEffect=true | 保持现有保守 EXTERNAL_SIDE_EFFECT |
| Approval action | 非 MCP 工具，为 Runtime/approval service 操作 | 没有新增 effect 或通用重放，原有 HITL 保持 |
| LOCAL RFQ intent | 不适用，readOnly=false / sideEffect=true | EXTERNAL_SIDE_EFFECT；仍需审批 |
| ERP RFQ execution | false，idempotentHint=true | EXTERNAL_SIDE_EFFECT；generic retry denied |

服务端 14 个工具声明不变，13 个只读、1 个 RFQ 写入。没有修改 server annotation、模型 tool description/input schema、stage scope 或 canonical RFQ binding。

## 7. Deterministic round-trip / safety

新增 `McpAnnotationTestServer` 使用生产 `ProcurementMcpService` 声明及 `ProcurementMcpServer.serve` 协议，adapter 为离线 fixture。客户端实际启动子进程并经 stdio tools/list discovery；不是只单测 resolver。

`McpToolEffectContractTests` 9 个执行用例涵盖 canonical 全工具矩阵、真实 item transient round-trip、missing/false/string annotation、idempotent-only、openWorld 与 effect 正交、未知来源伪造只读及 `_meta`、连接 identity 不匹配、可信 RFQ 不重放。写工具即使伪造 readOnlyHint=true，只要来源未获本地信任，Runtime 仍执行一次并拒绝 retry。

Focused Java 命令：

```powershell
mvn -B '-Dtest=McpToolEffectContractTests,StdioMcpToolGatewayTests,ProcurementErpMcpTests,ToolRetryPolicyTests,ProcurementWorkflowRetryTests,ProcurementWorkflowBusinessOutcomeTests,ProcurementWorkflowEvidenceCarryTests,ProcurementWorkflowRequisitionBindingTests,HarnessCoreTests' '-Dsurefire.reportNameSuffix=mcp-contract-focused-r2' test
python -B -m unittest discover -s evals/procurement-workflow -p 'test_*.py'
```

结果：Java 113/113 PASS，Python 31/31 PASS。首轮同源码 Java 为 110 PASS、3 ERROR：既有 `StdioMcpToolGatewayTests` 在子进程初始化短超时后得到空列表；未修改其测试或超时，原样复验全过。首轮日志/XML 保留，不隐去首次错误。

## 8. Retry deterministic result

从真实 stdio discovered `procurement_get_item` 执行既有 Runtime：1 logical invocation、同一个 request 对象/ID/参数；attempt 1 经生产 server 返回 ERP_UNAVAILABLE，分类 RETRYABLE_ERROR，effect READ_ONLY，decision=true；attempt 2 SUCCESS。2 physical attempts、1 error、1 retry、1 recovered。既有 exhaustion/clarification/business outcome/evidence/RFQ binding 回归全过。没有发现需要扩展本轮范围的独立 Evidence bug。

## 9. Legacy

`pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite` 完成：Full Java 1360 total = **1351 PASS / 9 SKIP / 0 FAIL**；Replay **380/380**；Frozen fixtures **5/5 unchanged**；build PASS。Procurement 子集单独为 918 total / 907 PASS / 11 SKIP / 0 FAIL，不与 Full 分母相加。SKIP 为既有环境门控，逐项原因在 `deterministic.json`。路径：`target/procurement-v0/v0-20261005-110704-cd691816`。

## 10. ROB-01 唯一模型结果

本轮 run `workflow-mcp-contract-rob01-20261005-r1`，唯一有效 trial `ROB-01-1-a1`：**Frozen Business PASS、Workflow Acceptance PASS、final_acceptance=true**。所有 11 个 Workflow checks 为 true，包括 CLARIFICATION_CAUSALITY。最终 outcome=COMPLETE、stopReason=COMPLETED，选中 V2-SUP-001。通过后停止，没有执行后续 Smoke。

命令：`python -B target/workflow-mcp-contract-audit-20261005/verify_rob.py --readiness`，随后仅一次 `--model`。复用冻结 world、trial、grade 与已有附加验收器；没有改变任务或判分规则。

Docker Linux、ERP HTTP pong、DB、实际 erpnext backend 与可信连接配置全部通过。readiness reset 和 runner 的 trial reset 均通过：suppliers=6、items=10、item_suppliers=18、departments=3、budgets=3、history=24、performance=6、sample requisitions=6。canonical hash=`028cf070b54ad26661db050e7a7af1b11f33a0fc64ff8a432096ce59bc76d4a5`；RFQ 前后均 0，审批起始/结束均 0。本任务 ANALYZE，不强制进入 RFQ。

目标链确实发生：`procurement_get_item({"id":"V2-WS-001"})`；toolCallId=`16200a32-96ce-4469-9e77-157893ae63b8`；服务端 readOnlyHint=true、本地 trusted=true、resolved effect=READ_ONLY。attempt 1：ERP_UNAVAILABLE / injected MCP 503 → RETRYABLE_ERROR → TRANSIENT_REPLAY_SAFE_WITHIN_BUDGET → backoff 150ms → attempt 2 SUCCESS。**同一 requestId、相同 validated arguments、同一 Run 的 1 logical call / 2 attempts / 1 error / 1 retry / 1 recovered**，不是 LLM 新一轮重调。

两次 attempt 发生在 REQUISITION 同一 invocation 内，该 Run 进入时 canonical caseId 为空、caseVersion=-1；item 恢复后才推进 Case。原 Runtime 使用同一个请求/上下文的执行循环未修改。原始 annotations、discovered definition、trust decision、逐次 failure/result/event/receipt 均在 `diagnosis.json` 与完整 trace 归档中。

## 11. Logical calls / physical attempts

全 trial：**22 logical model tool calls、23 physical executor attempts、1 Runtime retry、1 recovered、retry exhausted=0**。另有 1 次 Java-owned canonical Finalize，故 ledger receipts=23；它不并入 model call 或 executor attempt 分母。MCP calls=40，包含 provider/Finalize 内部读取，不能将其当作 logical tool calls 或自动重试数。

**Tool Errors=5**，其中 1 次为恢复成功的注入 transient error，另 4 次为模型对 `procurement_get_supplier` 漏传必填 `id` 导致的 VALIDATION_ERROR，均未自动重试。既有 frozen reporting 的分类保留为 backend_error=1、business_error=4；结构化 runtime classification 为上述 VALIDATION_ERROR。不将错误隐藏为 0，不为这些模型参数错误调整 Prompt/schema 或重跑。

## 12. Evidence / structured output

Evidence scope **PASS**，CURRENT_STAGE 成功 refs：REQUISITION=5、SOURCING=8；WORKFLOW_CARRIED=0，本轮未进入 RFQ；unauthorized accepted=0。上一轮失败 item receipt 被模型当作证据的问题，本次在只读恢复后未重现；无需修改 Evidence Carry 或 validator。

两个 stage 均 initial structured-output success：2/2，repair count=0、repair failures=0。没有 fake NEEDS_USER_INPUT。Business Outcome、Canonical RFQ binding、HITL/idempotency 保持原样；RFQ 模型路径未覆盖，相关 deterministic regression 已通过。

## 13. Safety

UnauthorizedWrite=0；DuplicateSideEffect=0；StaleApprovalExecution=0；BusinessInvariantViolation=0；FabricatedCriticalFact=0。仅为本次真实 ROB trial 的冻结结果，不宣称整个 Smoke 或完整任务集通过。

## 14. Cost

Provider calls **12 = 10 Agent + 2 input guardrail**；Model turns=10。Agent input/output/total=84,332 / 2,707 / 87,039；guardrail=986 / 73 / 1,059；合计 **85,318 input / 2,780 output / 88,098 total tokens**。Peak context=13,811 provider input tokens；task latency=22,632.6144ms；含 reset/启动/snapshot 的 trial wall clock=46.907s。Provider internal retries 未暴露，记 unknown。

配置模型仍为 deepseek-chat，provider 响应标识为 deepseek-flash；没有主动更换 provider/model。未返回货币账单，不将成本填为 0。

## 15. Fingerprint

修复前 production=`052e44be79e63f95dc233ff6881389e336d7df652a152956ad3967ef89f95e65`。

修复后 production=`b5f91b46ac4c2e394f77652e4a7bacfae191a19e24bf763afbde577e4b8aa668`。该 freeze 在确定性验证、readiness、唯一 model trial、最终归档之间一致。源码改动限于本报告的 trust/discovery 范围；Prompt、Stage/Tool input-output schema、Workflow、RetryPolicy、grader、seed 的旧字节保持。

## 16. Git / evidence

branch=`feat/procurement-workflow`，HEAD 未变，index empty；git diff --check PASS。1321 个既有非忽略文件中，4 个授权文件变更、1317 个保持原字节。新增 2 个离线测试文件及本报告/归档。trial driver 的唯一改动为对自有 MCP 连接设置 trustedProcurement=true。

Handler 保持独立未暂存修改，SHA256=`8f3eea10df78f9e8f5010a4d2ce72ee049962fdc4e3b4d98cfcd7518ce2e7f6d`；没有 stage、commit、push、tag、restore/reset。完整 git status 保存在 `evals/procurement-workflow/reports/mcp-tool-contract/git-status.txt`。

归档含完整 raw/trace/reset/snapshot、首次失败和复验日志、Legacy 结果、源码片段/版本审计、freeze、checksums。旧 ROB 失败记录完整保留，新通过记录独立存储，未覆盖或重评分历史 trial。

## 17. Scope / stop

未修改 RetryPolicy、Workflow business logic、Prompt、Stage/Tool schema、grader、seed、Evidence Carry、Business Outcome mapping、Canonical RFQ binding；未新增大型 catalog、通用 trust/PKI/retry framework、Memory、Multi-Agent 或 Dynamic Tool Selector。

**只执行本轮 ROB-01 ×1。PASS 后停止，等待人工 Review；未开始其他 Smoke/Dev/Holdout/40×3。**
