# Phase 5 Dynamic Tool Loading 验证报告

Source Baseline / Test Run HEAD：`2f649d6cbc56cea1803ffa6e5a3561d48a53d776`（`procurement-context-v1`）。分支 `feat/dynamic-tool-loading`，dirty working tree，Phase 5 尚未提交。以下为最终同源验证结果，等待人工 Review；未 commit / push / tag。

## 1. Pre-change Tool Audit

初始 15 个，RFQ singleUse 后 14 个。依据真实冻结请求定义、ToolCatalog、MCP service 和调用链审计，原 exposure 为各 Case 状态均可见；下列执行条件不等于旧 exposure 筛选。

| Tool Name | Domain | Effect / Side Effect | Approval | 原执行条件 | 主要阶段 |
|---|---|---|---|---|---|
| procurement_case_patch | Procurement internal Case | INTERNAL_WRITE / yes | False | trusted scope + validated Patch + CAS | Case building |
| procurement_supplier_search | Procurement ERP facts / RFQ | READ_ONLY / no | False | current complete Case | Sourcing |
| procurement_recommendation_finalize | Procurement ERP facts / RFQ | READ_ONLY / no | False | current Case version + eligible supplier + canonical Evidence | Sourcing |
| procurement_create_rfq | Procurement ERP facts / RFQ | EXTERNAL_WRITE_INTENT / yes | True | current Run verified Finalize, current Case binding, approval, authorization, CAS and idempotency | RFQ preparation |
| mcp.erpnext.procurement_get_requisition | Procurement ERP facts / RFQ | READ_ONLY / no | False | ERP adapter validation; no Case-state gate; read-only adapter method | Analysis / inspection |
| mcp.erpnext.procurement_list_requisitions | Procurement ERP facts / RFQ | READ_ONLY / no | False | ERP adapter validation; no Case-state gate; read-only adapter method | Analysis / inspection |
| mcp.erpnext.procurement_get_item | Procurement ERP facts / RFQ | READ_ONLY / no | False | ERP adapter validation; no Case-state gate; read-only adapter method | Analysis / inspection |
| mcp.erpnext.procurement_list_items | Procurement ERP facts / RFQ | READ_ONLY / no | False | ERP adapter validation; no Case-state gate; read-only adapter method | Analysis / inspection |
| mcp.erpnext.procurement_get_budget_status | Procurement ERP facts / RFQ | READ_ONLY / no | False | ERP adapter validation; no Case-state gate; read-only adapter method | Analysis / inspection |
| mcp.erpnext.procurement_list_suppliers | Procurement ERP facts / RFQ | READ_ONLY / no | False | ERP adapter validation; no Case-state gate; read-only adapter method | Sourcing |
| mcp.erpnext.procurement_get_supplier | Procurement ERP facts / RFQ | READ_ONLY / no | False | ERP adapter validation; no Case-state gate; read-only adapter method | Sourcing |
| mcp.erpnext.procurement_list_item_suppliers | Procurement ERP facts / RFQ | READ_ONLY / no | False | ERP adapter validation; no Case-state gate; read-only adapter method | Sourcing |
| mcp.erpnext.procurement_get_purchase_history | Procurement ERP facts / RFQ | READ_ONLY / no | False | ERP adapter validation; no Case-state gate; read-only adapter method | Sourcing |
| mcp.erpnext.procurement_get_supplier_performance | Procurement ERP facts / RFQ | READ_ONLY / no | False | ERP adapter validation; no Case-state gate; read-only adapter method | Sourcing |
| mcp.erpnext.procurement_get_rfq | Procurement ERP facts / RFQ | READ_ONLY / no | False | ERP adapter validation; no Case-state gate; read-only adapter method | Analysis / inspection |

## 2. Tool Selection Architecture

Registry/profile/visibility/singleUse → ToolSelector（服务端 Case、Run/pending、当前执行记录）→ 原 ToolDefinitions 子集 → ContextAssembler → 原 ModelGateway。Selector 与 Policy/HITL/CAS/幂等安全边界保持独立。详见 `dynamic-tool-loading.md`。

## 3. Tool Matrix

REQUEST_ANALYSIS 12；SOURCING 13；绑定当前版本成功 Finalize 后 RFQ_PREPARATION 8；PENDING_APPROVAL 4；POST_RFQ 5；SAFE_READ_ONLY 4。完整规则见设计文档；不是新增持久化状态机。

| Business / Run State | Visible Tools（完整 registry 下） | Hidden Tools | Why |
|---|---|---|---|
| 已验证无 Case / CLARIFICATION_REQUIRED | 12：11 MCP reads + Case Patch | Search、Finalize、RFQ | 保留既有 Prompt 在 Patch 前读取供应商/history/performance 的依赖；不改 Prompt |
| 完整 Case，尚无当前版本 Finalize | 13：Core、Patch、Search、Finalize、item/supplier discovery、supplier/history/performance reads | RFQ、list_requisitions | 进入已有 Case 的寻源，尚不能提议写入 |
| 当前 run 成功 Finalize，Case/version 匹配且 RFQ 字段齐备 | 8：Core、Patch、list_items、get_supplier、RFQ | 其他 discovery/Search/Finalize/history/performance | 将当前推荐推进到审批提议；仍允许修需求（下轮重新判定） |
| WAITING_APPROVAL / pendingToolCall | 4：Core | 所有非 Core | 待审批停止期间不继续 sourcing 或重复提议 |
| RFQ singleUse 已消耗，运行中恢复 | 5：Core、get_supplier | 其他工具，包括 RFQ | 查询写入结果，不重新暴露已消费写工具 |
| unknown / missing / invalid Case state | 4：有效 Core | 所有非 Core | 无权威状态时收紧为只读；不 fallback 全集 |
| 错误 Agent identity 却启用 selector | 0 | 全部 | 显式配置错误不能扩大能力 |

## 4. Fail-safe Rules

unknown/missing/invalid → 有效 Core read 子集；错误 Agent → 空集；非法 metadata 隐藏该工具；CaseStore 错误显式失败。模型自报 stage 不授权。

## 5. Deterministic Tests

Focused r3：109/109 PASS，0 fail/error/skip。r1 为99/99，r2 在新增测试时编译失败（错误引用结果 accessor），修为读取持久 RunRecord 后 r3 通过；所有日志保留。

```powershell
mvn -B '-Dtest=ProcurementToolSelectorTests,ContextEngineeringTests,DefaultAgentRuntimeStateTests,HarnessCoreTests,ProcurementRfqHitlTests,ErpNextRfqGatewayTests,ProcurementCaseContextRendererTests,DefaultAgentContextManagerTests,NativeToolCallingAgentModelGatewayTests' '-Dsurefire.reportNameSuffix=dynamic-l0-r3' test
```

## 6. Legacy Regression

```powershell
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite
```

仅该测试进程临时设置 `JDK_JAVA_OPTIONS=-XX:TieredStopAtLevel=1`，用于已有 fake MCP JVM 1 秒冷启动约束，结束恢复；模型 Eval 未携带该选项。未修改测试 timeout。

| 阶段 | Total | PASS | Failure / Error | SKIP |
|---|---:|---:|---:|---:|
| Procurement | 817 | 806 | 0 / 0 | 11 |
| Replay | 380 | 380 | 0 / 0 | 0 |
| Full Java | 1,240 | 1,231 | 0 / 0 | 9 |

Package 成功；5/5 frozen fixture byte SHA256 unchanged。各阶段重叠，不合并为独立测试总数。`JdbcProcurementCaseStorePostgresIT` 两项按既有 `PROCUREMENT_POSTGRES_IT=true` 条件跳过：本次未配置一次性 PostgreSQL IT 环境，未验证真实 PostgreSQL。其余 skip 为既有门控行为；完整 suite 与 skip 明细保存在 deterministic evidence。未运行 Legacy Live。

## 7. Smoke

```powershell
python -B evals/dynamic-tool-loading/run.py --mode smoke --run-id dynamic-smoke-20261003-r1
```

9/9 PASS，0 fail；0 infra / 0 replacement。StopReason：{"COMPLETED": 7, "WAITING_APPROVAL": 2}。

Tool Calls 116，Tool Errors 1（ROB-01 预设后端故障）。Provider input 623,799 / output 17,083 / total 640,882。68 次模型请求的 visible tool 均值 11.68，schema 估算均值 1,455.41、P95 1,673；同轮 candidate schema 均值 1,956.71。

复用冻结 world / driver / grader / selection，所有有效 trial 只执行一次。Dev 使用同源 Smoke gate，结束核验源码 manifest。

## 8. Dev

```powershell
python -B evals/dynamic-tool-loading/run.py --mode dev --run-id dynamic-dev-20261003-r1 --smoke-run dynamic-smoke-20261003-r1
```

28/28 PASS，0 fail；1 infra / 1 replacement。StopReason：{"COMPLETED": 23, "WAITING_APPROVAL": 5}。

REQ-03-1-a1 在 reset 删除 Supplier 时遇到 MariaDB 1020（Record has changed since last read），被 Frappe 包装为 QueryTimeoutError；尚未调用模型。只读 process-list 检查无残留站点事务后，以原源码恢复：

```powershell
python -B evals/dynamic-tool-loading/run.py --mode dev --run-id dynamic-dev-20261003-r1 --smoke-run dynamic-smoke-20261003-r1 --resume
```

REQ-03-1-a2 PASS。原 reset.log/result.json、诊断和 replacement accounting 均保留，未重启服务、改 timeout 或重跑有效 trial。详见 `reports/infra-diagnosis.json`。

复用冻结 world / driver / grader / selection，所有有效 trial 只执行一次。Dev 使用同源 Smoke gate，结束核验源码 manifest。

## 9. Tool Schema Delta

| 指标 | Phase 4 Dev×1 | Phase 5 Dev×1 |
|---|---:|---:|
| Average visible tools / turn | 14.97 | 11.67 |
| Average schema estimate / turn | 1,956.63 | 1,450.37 |
| P95 schema estimate / turn | 1,963 | 1,673 |
| Model requests | 168 | 180 |

**同一 Phase 5 当前轮**：筛选前 candidate schema 均值 1,957.06 → selected 1,450.37；这是最直接的 schema exposure 差值。它不包含 provider framing，不等于计费 Token。

| Task | Phase 4 tools mean | Phase 5 tools mean (range) | Phase 4 schema mean | Phase 5 schema mean |
|---|---:|---:|---:|---:|
| REQ-01 | 15 | 11.89 (8–13) | 1,963 | 1,494.56 |
| SRC-01 | 15 | 11.83 (8–13) | 1,963 | 1,519.33 |
| RFQ-02 | 14.86 | 10.71 (5–13) | 1,932.43 | 1,320 |

比较 Gate：**COMPARABLE_FOR_DEVELOPMENT**；控制差异：[]。Task/trial1、Prompt、model options、retry 和单个 ToolDefinition contract 对齐；schema 集合变化是本阶段自变量。阶段出现次数：{"REQUEST_ANALYSIS": 110, "SOURCING": 47, "RFQ_PREPARATION": 18, "POST_RFQ": 5}。

## 10. Token / Context Delta

| 指标 | Phase 4 Dev×1 | Phase 5 Dev×1 |
|---|---:|---:|
| Provider Input Tokens | 1,487,912 | 1,572,945 |
| Provider Output Tokens | 43,995 | 45,821 |
| Provider Total Tokens | 1,531,907 | 1,618,766 |
| Mean Peak Context | 13,825.89 | 13,459.32 |
| P95 Peak Context | 20,479 | 22,732 |

usage 延续冻结 grader 口径（Agent model + input guardrail）；Peak 为 task 最大单次 prompt usage 的分布。以下实际请求累计本地估算展示 schema 与其他上下文的轨迹变化，不是计费：

| Component | Phase 4 accumulated estimate | Phase 5 accumulated estimate |
|---|---:|---:|
| instructions | 284,424 | 304,740 |
| tool_schema | 328,714 | 261,067 |
| business_state | 9,899 | 9,646 |
| run_state | 16,469 | 17,635 |
| conversation | 22,690 | 26,015 |
| tool_result | 413,353 | 482,733 |
| other | 26,802 | 31,721 |

**Dev×1 directional observation only**。不同 planning path、轮数和读取行为也改变总量，不能把总体 Token 差值全部归因 Tool Schema。未新增 Context/Projection 优化；配置 deepseek-chat，provider resolved deepseek-flash；未锁定 provider immutable build。正式收益需以后独立授权的稳定 milestone / final benchmark 验证。

## 11. Tool Calls / Tool Errors / Latency

| 指标 | Phase 4 | Phase 5 |
|---|---:|---:|
| Tool Calls | 310 | 328 |
| Mean task latency (s) | 16.34 | 16.33 |
| P95 task latency (s) | 22.70 | 26.71 |
| Tool Errors | 6 | 6 |

SELECTOR_HIDDEN_TOOL attempts：0；Tool Error categories：{"schema_invalid": 0, "tool_not_found": 0, "business_error": 2, "backend_error": 4, "policy_rejected": 0, "other": 0}。重复成功相同 MCP read request proxy：{"phase4": 0, "phase5": 0}；这不代表语义上 unnecessary，独立 unnecessary-call grader 不存在，未伪造该指标。本轮没有 Task 失败，也没有 hidden-tool attempt，因此未出现需要归因的 TOOL_SELECTION_MISS。

6 个 Tool Errors 来自 ANOM-04 / REQ-06 的两个 ERP_NOT_FOUND，以及 ROB-01 的一次、ROB-02 的三次预设 MCP 503。schema_invalid / tool_not_found 均 0；reset 基础设施中断不计入这些工具错误。

## 12. Safety

| Invariant | Smoke | Dev |
|---|---:|---:|
| UnauthorizedWrite | 0 | 0 |
| DuplicateSideEffect | 0 | 0 |
| StaleApprovalExecution | 0 | 0 |
| BusinessInvariantViolation | 0 | 0 |
| FabricatedCriticalFact | 0 | 0 |

五项全部立即停止 gate；真正执行安全仍由原 Policy/HITL/Binding/CAS/Authorization/Idempotency 负责。

## 13. Cost

实际 Smoke 9×1 + Dev28×1，37 个有效 trials，38 次执行 attempts（含 1 次在模型调用前失败的 reset）。Infra 1；replacement 1。

Provider totals：{"input_tokens": 2196744, "output_tokens": 62904, "total_tokens": 2259648}。未运行 Holdout、40×3、Blind；未重跑有效失败。未根据估算冒称实际货币账单。所有日志和压缩 raw evidence、逐文件 SHA256、source freeze manifest 已归档到 `evals/dynamic-tool-loading/reports/`，包含 gzip 解压内容的配置 secret 扫描。

## 14. Git Status

分支 `feat/dynamic-tool-loading`，HEAD 仍为 `2f649d6cbc56cea1803ffa6e5a3561d48a53d776`。Phase 5 文件未暂存；用户原 Handler 修改独立保留，SHA256 `8F3EEA10DF78F9E8F5010A4D2CE72EE049962FDC4E3B4D98CFCD7518CE2E7F6D`。最终 status / diff-check / scope 核验见 `reports/final-scope.json`。无 commit / push / tag。

## 15. Scope

仅实现服务端业务状态驱动的 Tool Selection 与必要 Context schema 预留集成。没有新 Multi-Agent、Supervisor/Subagents、Memory redesign、Tool Search、LLM 选工具、Prompt/description 调优、grader 修改或额外 Context/Projection/Compaction 优化。历史源码中的已存在相关能力没有在本阶段扩展。
