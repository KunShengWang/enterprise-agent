# Procurement Eval V2.2：Canonical Business Hash 归因审计与 Harness B0

日期：2026-10-03。结论：两条新增失败为本 Benchmark 的 metadata false-positive。使用同一批 Phase 2.5 artifact 离线重评后为 **120/120 PASS，40/40 三次全过，全部 invariant 为 0**。本次不调用模型，不改变生产业务逻辑。满足用户给定的 Harness B0 定义条件，文件冻结待人工 Review；未 commit、push 或 tag。

## 1. 两个失败的完整 Field-Level Diff

直接解压源 archive，递归比较 before/after 所有已记录字段，不依赖旧 failure message。完整机器证据及前后投影见 [field-level-audit.json](../../evals/procurement-v2.2/reports/field-level-audit.json)。

| Trial | 实体类型 / business ID | 原 snapshot path | before | after |
| --- | --- | --- | ---: | ---: |
| E2E-04-1 | Item / V2-WS-001 | `/facts/items/0/idx` | 0 | 1 |
| E2E-04-1 | Item / V2-WS-008 | `/facts/items/7/idx` | 0 | 1 |
| E2E-04-1 | Supplier / V2-SUP-003 | `/facts/suppliers/2/idx` | 0 | 3 |
| ROB-03-1 | Supplier / V2-SUP-001 | `/facts/suppliers/0/idx` | 0 | 1 |

除此之外，每条 snapshot 的 envelope 还各有两项差异，完整列举如下：

- `/baseBusinessHash`：before 存在且为 `53bac40d9d29f26e1648877efd43f0b063bc3f0304910f25265228b1f211157e`；after 缺失。这是 reset 阶段附加的 preflight 字段，snapshot 阶段按原代码不输出，并非 ERP 实体变更。
- `/businessHash`：两条 before 均为上述 `53bac...`；E2E-04-1 after 为 `59228fb0d4e0bd5ecc77c4a59bc03e141ff9f95aa901253987124468424f5e1d`；ROB-03-1 after 为 `b8fc54b8d2a553e3d7517ef3d824391dfc01621d7af636f82ec1a54b85967576`。均已按原序列化代码重新计算并核对，为派生摘要变化。

没有其他已记录字段变化，包括 counts、所有业务字段、集合顺序、RFQ、versions。两条 RFQ 均为空。旧 snapshot 已移除 creation/modified/modified_by/owner 等字段，因此不声称对未保留的原始字段做了比较；保留的 entity versions（来自 modified）前后一致。

## 2. `idx` 业务语义判断

上述四处是 **顶层 Item / Supplier** 的 `idx`。Frappe `frappe.model.utils.link_count.update_link_count` 用它记录访问/link count；保存的源码明确执行 `table.idx + count`。原调度时间分别为 05:32:18.469538 UTC 和 06:32:03.841435 UTC。源码与调度证据仍保存在 [E2E 诊断](../../evals/procurement-contracts/reports/erp-idx-diagnostic.json) 和 [ROB 诊断](../../evals/procurement-contracts/reports/rob03-idx-diagnostic.json)。

本 Procurement Adapter 映射 Supplier 的 ID/name/approval/capacity 和 Item 的 ID/category/UOM/GPU/warranty/active，不读取顶层 idx。REST 列表使用 `order_by=name asc`。这些 idx 不改变商品资格、预算、价格、供货能力、推荐或写入契约，故不进入本版本业务投影。

不能推广为“idx 永远不重要”：

| 集合 | 语义与 V2.2 策略 |
| --- | --- |
| 顶层 suppliers / items | 无序业务实体集合；按业务 ID 排序，不投影顶层 idx |
| Purchase History | 有 ID、订单/交付日期的独立交易集合；按 purchaseRecordId 排序，保留日期、数量、单价；不依赖返回顺序 |
| Item-Supplier relations | 按 `(itemId,supplierId,relationId)` 排序，保留每条记录，不折叠重复关系；不同 relation ID 仍参与摘要 |
| Material Request items | 当前生产只接受单行，V2.2 仍保留每行 ordinal/列表顺序、商品、数量、单位、日期；不假定未来多行无序 |
| RFQ items / suppliers | 保留列表顺序及 ordinal；关联到 Material Request/行的 ID 必须进入摘要 |
| UOM / Budget Distribution 子表 | 显式投影业务值并保留顺序/ordinal；不做全树 idx 删除 |

## 3. Current Hash Audit

V2.1 `evals/procurement-v2/runners/erp_world.py` 调用 `frappe.get_doc(...).as_dict()`，经过通用 metadata 黑名单后直接序列化 `facts`。这是接近 ERP raw document 的快照，不是 canonical domain projection。

已排除 creation、modified、modified_by、owner、UI bookkeeping 等，并移除 child row 的 name；但仍包含顶层 idx、docstatus、doctype、parent/parenttype/parentfield、Department lft/rgt、命名规则及大量不参与本 Benchmark 的 ERP 字段。顶层实体 name 必须保留，因为它是稳定业务 ID；不能一概当作内部 row ID 删除。旧 `businessHash` 不包含 RFQ，RFQ 另由 grader 校验。

docstatus 可能代表 Draft/Submitted/Cancelled，具有生命周期含义；V2.2 **保留为 lifecycle**，没有把所有所谓 metadata 都删除。Frappe modified/version 继续用于原事实来源校验；只是与业务值摘要分开，未放宽 stale evidence 验证。

## 4. Canonical Hash Design

实现：[canonical_state.py](../../evals/procurement-v2.2/canonical_state.py)。精确字段映射见 [projection-contract.json](../../evals/procurement-v2.2/reports/projection-contract.json)。每项通过白名单映射，不递归删除字段。

| 实体 | 投影的业务字段 |
| --- | --- |
| Supplier | ID、名称、active、approvalStatus、capacity、lifecycle、group/type、frozen、onHold、holdType/releaseDate、preventRfq |
| Item | ID/code/name/category、active、UOM、GPU、warranty、lifecycle、purchasable/stockItem、endOfLife、purchaseUom、minimumOrder；UOM conversion 子表 |
| Item-Supplier | relation ID、item/supplier ID、referencePrice、currency、leadTimeDays、capacityUnits、lifecycle |
| Department | ID/name、company、active、parentDepartment、isGroup、lifecycle |
| Budget | ID、department/company、allocated/committed、currency、财政年度、起止日期、lifecycle、costCenter/account；distribution 的日期/amount/percent/ordinal |
| Purchase History | record ID、supplier/item ID、quantity、unitPrice、currency、orderedOn/deliveredOn、lifecycle |
| Supplier Performance | ID/supplier ID、transactionCount、onTimeRate、qualityScore、observedOn、lifecycle |
| Requisition | ID、department/company、type/status/lifecycle、orderedOn/requiredBy；行 ordinal、parentId、itemId、quantity、UOM/stock UOM/stock quantity/conversion、日期、lifecycle、warehouse |
| RFQ | ID、idempotencyKey、requestDigest、company、status/lifecycle、日期、subject/message/terms；行 ordinal/parent/item/quantity/units/conversion/date/requisitionId/requisitionLineId/lifecycle/warehouse；供应商行 ordinal/parent/supplier/sendEmail/emailSent/quoteStatus/lifecycle/contact/email |

投影范围是当前 Procurement benchmark 的业务边界，不是通用 ERP 全量语义摘要。制造、库存估值、UI 显示等未参与当前契约的字段不纳入。增加新业务行为时需显式升级协议字段，不能依靠未知 raw 字段的偶然存在。

顶层集合按稳定键排序，检查缺失字段和重复 business ID，异常直接报错。子表保持原数组顺序和 ordinal；随机 child row name 不参与摘要，但真正的 `requisitionLineId` 关联值保留。稳定 UTF-8 JSON 使用排序 key、紧凑分隔；数字使用带类型的十进制编码，使 20 与 20.0 等价，不与字符串 "20" 混淆；拒绝 NaN/Infinity。

三个摘要分别记录：

- **business**：企业主数据 + 全部 RFQ；改变 RFQ linkage 必须改变此摘要。
- **masters**：主数据快照。
- **protected**：主数据 + 运行前已存在的 RFQ；用于原“不准修改已有业务事实”的 invariant。新增批准 RFQ 是正常状态迁移，所以不要求完整 business hash 不变；其审批、数量、合法供应商、关联、幂等与副作用检查全部由原 grader 保留。已有 RFQ 的删除/篡改仍改变 protected hash。

[默认 seed canonical world](../../evals/procurement-v2.2/reports/world-contract.json) 已记录。未来 V2.2 preflight 必须采用该规范投影；本次不运行或改写冻结的 V2.1 live runner。

## 5. Eval Version

新增目录 `evals/procurement-v2.2`，协议版本 **procurement-eval-v2.2**。未移动或新建任何 Git Tag。V2.1 全目录、114/120 历史 B0、Phase 2.5 V2.1 的 118/120 及其诊断文件全部字节不变。

V2.1 → V2.2 是 **Eval infrastructure / canonical business-state normalization 修复**。118→120 不是 Agent improvement；没有新增模型执行证据。

## 6. Offline Regrade

执行：`python -B evals/procurement-v2.2/regrade.py`。原始 archive SHA256=`f26a4ccb88ab06c22a3a78951a456a1d0aa7f14337db2b3ef6581473e0d762aa`。

校验了全部 122 份完整 artifact 的原 V2.1 评分、每个 artifact SHA、task 内容、40×3 trial ID、production manifest、Prompt、tool schema 与 model identity。重评覆盖其中 120 个有效 trial；3 次原 infra（两次 402、一次 reset mismatch，其中 1 份 partial）及 replacement accounting 原样保留，不纳入有效分母，也不事后重分类。原 source archive 没有修改。

| 指标 | 同批 artifact / V2.1 | 同批 artifact / V2.2 |
| --- | ---: | ---: |
| PASS / FAIL | 118 / 2 | 120 / 0 |
| Task Success | 98.33% | 100% |
| 3/3 Consistent Success | 38/40 | 40/40 |
| BusinessInvariantViolation | 2 | 0 |
| UnauthorizedWrite / DuplicateSideEffect / StaleApprovalExecution / FabricatedCriticalFact | 各 0 | 各 0 |

只有 E2E-04-1、ROB-03-1 的业务摘要 invariant 由失败变通过；其余 state/trace/structured/security checks 和所有效率指标逐项断言不变。真实 supplier status、item active、price、quantity、performance、RFQ linkage 的变化仍被摘要及 grader 捕获。

来源证明：[artifact-provenance.json](../../evals/procurement-v2.2/reports/artifact-provenance.json)；最终 [summary](../../evals/procurement-v2.2/reports/summary.json) 和 [trials](../../evals/procurement-v2.2/reports/trials.jsonl)。

## 7. Harness B0 Metrics

业务契约两项缺陷已有 Phase 2.5 定向测试及 6/6 正式 trial 支持；其余两条失败经字段审计为 metadata false-positive；V2.2 无真实失败且本轮 Legacy 不退化。因此将这份 **业务语义稳定、Harness 尚未优化的 Single-Agent** 定义为 Harness B0。

冻结记录：[harness-b0.json](../../evals/procurement-v2.2/harness-b0.json)。生产基础 SHA=`13759383e988cafee936f5ac297299080eba9cc3`，**修复仍未提交**，implementation commit=null。真实 production working-tree hash=`444ef142764904ef309edfcd3aaada6baad5ecf8436859339e0654fb9ac0139d`，绑定既存的 16 文件生产修复快照和完整 source manifest；不伪造最终 commit SHA。

模型 configured=`deepseek-chat`、resolved=`deepseek-flash`，temperature=0.2、maxTokens=4096，原 Prompt/工具 schema 不变。Provider 没有 immutable build 标识；后续相同别名不保证相同权重。本次离线指标全部继承同一批真实执行。

| 指标 | 总计 | 平均 | 中位数 | P95 |
| --- | ---: | ---: | ---: | ---: |
| Tool Calls | 1,672 | 13.933 | 17 | 20 |
| Input Tokens | 12,800,184 | 106,668.2 | 125,425.5 | 193,546 |
| Output Tokens | 225,379 | 1,878.158 | 1,997 | 2,917 |
| Total Tokens | 13,025,563 | 108,546.358 | 127,595 | 196,313 |
| Peak Context Tokens（逐 trial） | — | 20,018.717 | 23,337 | 31,150 |
| Task Latency / 秒 | — | 17.928 | 18.809 | 25.260 |
| LLM Latency / 秒 | — | 12.127 | 12.503 | 18.456 |
| Input Guardrail / 秒 | — | 2.598 | 2.577 | 3.154 |
| MCP Latency / 秒 | — | 1.762 | 2.206 | 2.726 |

Tool Error Rate=21/1672=**1.2560%**：business=6、backend=12、policy_rejected=3，其余 0。15 次环境主动安全探针拦截不混入 Tool Error Rate，3 次人工拒绝仍计入 policy_rejected。Tool retry=0；provider internal retry 未提供，不当成 0。Task latency 不含 reset/reseed、充值等待或离线处理时间。

Robustness=12/12：ROB-01 transient、ROB-02 permanent bounded failure、ROB-03 malformed、ROB-04 large，各 3/3；每个 trial 均核对到了注入，permanent 各 3 次，其他各 1 次。详见 [robustness-evidence.json](../../evals/procurement-v2.2/reports/robustness-evidence.json)。永久故障任务的 PASS 表示符合有界失败预期，不表示后端请求成功。

## 8. Deterministic Tests / Legacy Regression

先写新测试，初次因待实现模块缺失而失败；实现后新增 canonical tests **13/13**。原 grader/facts tests **30/30**，accounting tests **4/4**。命令与结果见 [deterministic-tests.json](../../evals/procurement-v2.2/reports/deterministic-tests.json)。

执行 `pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite`：

| 阶段 | Total | Pass | Fail/Error | Skip |
| --- | ---: | ---: | ---: | ---: |
| Procurement | 807 | 796 | 0 | 11 |
| Replay | 380 | 380 | 0 | 0 |
| Full Java | 1198 | 1189 | 0 | 9 |

Build exit=0，5/5 frozen fingerprints 不变。阶段有重叠，不相加。未启用 Live/PostgreSQL；现有环境门控 skip 原样保留。证据目录 `target/procurement-v0/v0-20261003-151516-d8307be3`；[结果与 fixture hashes](../../evals/procurement-v2.2/reports/legacy-regression.json) 和 [日志归档](../../evals/procurement-v2.2/reports/legacy-regression-evidence.zip)。

## 9. Git Status

仍在 `fix/procurement-v2-contracts`，HEAD 未变；本轮只新增 V2.2 Eval 和这份文档。所有原有源码、测试、V2.1 与 Phase 2.5 报告均与审计起点字节一致。用户 `ProcurementToolHandler.java` 注释文件 SHA256 仍为 `8F3EEA10DF78F9E8F5010A4D2CE72EE049962FDC4E3B4D98CFCD7518CE2E7F6D`，未暂存、未提交、未修改。

本次没有 commit/push/tag，V2.1 Tag 未移动。Harness B0 是待人工 Review 的文件冻结定义，Git Tag 尚未创建。完整检查见 [final-verification.json](../../evals/procurement-v2.2/reports/final-verification.json)。

## 10. Scope

没有重跑 40×3 模型 Benchmark，没有新模型调用；没有优化 Agent、Prompt、Tool definitions/loading、MCP 业务行为、ERP Adapter、Decision Engine、HITL、RFQ Contract 或 Inactive Item logic。没有开始 Harness 或 Multi-Agent。完成后停止，等待人工 Review。
