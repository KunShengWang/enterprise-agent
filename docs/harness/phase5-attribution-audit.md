# Phase 5 Dynamic Tool Loading：纯离线归因审计

## 1. Executive Conclusion

**结论：INCONCLUSIVE。架构建议：FEATURE-FLAG / EXPERIMENTAL，暂不升级为后续默认 Harness。**

已经证明的是 Schema 暴露减少，以及本次运行中少数任务有更多读取/更长调用轨迹；没有证明这些额外轨迹由 Selector 引起，也没有证据把它们断言为随机波动。因此不强行 KEEP，不据此 REVERT，也不认为已经证明“仅保留 RFQ”是最优配置。

直接技术结果：在 Phase 5 实际 180 个模型请求上，候选集合与选中集合的同轮差额为 **91,203 个本地估算 Schema Tokens**，平均每轮 506.68、每任务 3,257.25。它不是 provider 计费节省，也不是 Phase4/5 总 usage 差。

Input 在 **20/28** 个任务下降、8 个上升。SRC-02 / SRC-05 分别增加 134,959 / 92,186，合计 **+227,145**；其余 26 项合计 **−142,112**，抵消后全体净增 +85,033。Task Success 两侧均 28/28；五项不变量保持 0。

仅读取既有 Phase 4/5 Dev ZIP、原始 request/result/events 和冻结任务；没有模型调用、测试集重跑或生产代码修改。排除 Phase 5 REQ-03-1-a1 的 reset infra attempt，配对其有效 a2。SHA256 校验档案及逐文件 provenance，按 task_id/trial1 唯一配对，核验相同 task payload、Prompt、model options、retry controls、resolved model 与单工具 contract。Gate：`COMPARABLE_FOR_OFFLINE_DESCRIPTIVE_AUDIT`，不等于随机对照因果 gate。模型缺少 immutable build。

复现命令（纯离线）：
```powershell
python -B evals/dynamic-tool-loading/audits/analyze.py
python -B evals/dynamic-tool-loading/audits/render_report.py
```

## 2. Task-Level Delta

完整两侧 turns/calls/errors/input/output/total/peak/latency 与全部差值见 `evals/dynamic-tool-loading/audits/phase4-vs-phase5-by-task.json`。以下 Δ = Phase5 − Phase4；“改善”仅指成本数值下降，不是业务评分变化。

| 指标 | 下降 / 不变 / 上升 | Median Δ | P25 Δ | P75 Δ | P95 Δ |
| --- | --- | --- | --- | --- | --- |
| turns | 12 / 8 / 8 | 0 | -1 | 1 | 5.60 |
| tool_calls | 8 / 13 / 7 | 0 | -1 | 0.50 | 9.90 |
| input_tokens | 20 / 0 / 8 | -5,099.50 | -10,806.25 | 1,565.75 | 76,852.15 |
| total_tokens | 20 / 0 / 8 | -5,308 | -10,654.75 | 1,627.50 | 77,537.70 |
| peak_context_tokens | 21 / 0 / 7 | -904 | -1,196.50 | -179 | 4,181.40 |

差值分位数使用线性插值 `(n−1)q`，因此 calls/turns 的 P95 可为小数；原冻结全局 Peak P95 继续使用 nearest-rank `ceil(nq)`，两种口径不混算。

| Task | Turns P4→P5 | Calls P4→P5 | Δ Input | Δ Total | Δ Peak | Δ latency ms | 同轮 Schema 节省 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ANOM-01 | 7→6 | 14→13 | -11,885 | -12,260 | -344 | -5,069.26 | 2,424 |
| ANOM-03 | 2→2 | 2→2 | -1,073 | -1,144 | -536 | +75.42 | 1,036 |
| ANOM-04 | 2→2 | 3→3 | -1,095 | -1,078 | -547 | +20.10 | 1,036 |
| ANOM-06 | 7→6 | 10→9 | -12,517 | -12,582 | -1,288 | -743.28 | 2,652 |
| E2E-01 | 8→7 | 14→14 | -10,781 | -10,517 | -946 | +286.93 | 4,287 |
| E2E-03 | 9→9 | 13→13 | -5,834 | -5,799 | -1,111 | +958.40 | 5,323 |
| E2E-04 | 6→5 | 12→12 | -8,376 | -8,392 | -281 | -1,341.44 | 2,134 |
| E2E-06 | 7→7 | 10→10 | -5,073 | -5,094 | -1,166 | -1,401.47 | 4,287 |
| GOV-01 | 7→6 | 14→14 | -9,504 | -9,436 | -940 | -1,716.27 | 2,890 |
| GOV-02 | 10→6 | 17→14 | -43,437 | -43,375 | -2,732 | -2,411.94 | 2,890 |
| GOV-03 | 8→7 | 14→14 | -10,882 | -10,705 | -914 | -166.89 | 4,287 |
| REQ-01 | 10→9 | 14→12 | -11,506 | -11,865 | -1,468 | -2,846.14 | 4,216 |
| REQ-03 | 1→2 | 0→2 | +3,679 | +3,893 | +127 | +499.84 | 1,036 |
| REQ-04 | 1→2 | 0→2 | +4,293 | +4,490 | +752 | +723.58 | 1,036 |
| REQ-06 | 2→2 | 1→1 | -1,054 | -1,095 | -529 | -1,266.67 | 1,036 |
| RFQ-01 | 7→6 | 14→14 | -9,764 | -9,670 | -946 | -1,872.95 | 2,890 |
| RFQ-02 | 7→7 | 8→7 | -10,676 | -10,638 | -2,121 | +2,509.54 | 4,287 |
| RFQ-04 | 6→6 | 18→14 | -5,126 | -5,522 | -1,806 | -2,327.56 | 2,890 |
| RFQ-05 | 6→7 | 14→14 | +1,381 | +1,335 | -1,129 | -1,289.76 | 3,408 |
| ROB-01 | 7→10 | 18→24 | +48,375 | +48,757 | +3,209 | +1,984.26 | 4,962 |
| ROB-02 | 2→2 | 3→3 | -1,072 | -1,111 | -536 | -771.04 | 1,036 |
| ROB-03 | 7→6 | 13→18 | -4,765 | -4,503 | +170 | -716.89 | 2,890 |
| SRC-01 | 7→6 | 17→13 | -15,118 | -15,261 | -1,867 | -1,554.21 | 2,662 |
| SRC-02 | 7→19 | 13→25 | +134,959 | +135,814 | +4,725 | +11,577.91 | 9,624 |
| SRC-03 | 9→7 | 21→13 | -39,082 | -40,131 | -4,198 | -5,540.21 | 3,408 |
| SRC-04 | 6→7 | 13→13 | +2,120 | +2,505 | -894 | +48.91 | 3,408 |
| SRC-05 | 6→13 | 13→25 | +92,186 | +93,035 | +4,705 | +7,756.11 | 6,516 |
| SRC-07 | 4→6 | 7→10 | +16,660 | +17,208 | +2,347 | +4,404.30 | 2,652 |

7 个 calls 增长任务合计 +42，另外 8 个 calls 下降任务抵消 −24，净 +18。并非大多数任务都略增。Peak 21 项下降、7 项上升；平均值下降，但长尾换成 SRC-02 / ROB-01 / SRC-05。

| Phase | Peak top 5（Task=value） |
| --- | --- |
| phase4 | SRC-04=20,625, GOV-02=20,479, ROB-01=19,523, SRC-01=19,344, RFQ-04=19,318 |
| phase5 | SRC-02=23,042, ROB-01=22,732, SRC-05=22,545, SRC-04=19,731, ROB-03=18,553 |

28 项的 nearest-rank P95 是第二高值：Phase4 的 GOV-02 20,479 → Phase5 的 ROB-01 22,732。这是不同任务的顺序统计量，不是同一个任务直接增了 2,253。

## 3. Stage-Level Delta

业务组来自冻结 task.group，保留原 `anomaly`，不将 ANOM 靠前缀改归 Requisition。业务组不等于运行中的 exposure label：非 RFQ 任务也会在 Finalize 后出现 RFQ_PREPARATION。工具数/schema 均值按组内实际 model turns 加权；Peak 均值按 task 加权。

| Task group / N | Visible P4→P5 | Schema/turn P4→P5 | Calls P4→P5 | Input P4→P5 | Mean Peak P4→P5 |
| --- | --- | --- | --- | --- | --- |
| anomaly/4 | 15→12.31 | 1,963→1,516.25 | 29→27 | 121,103→94,533 | 7,170.75→6,492 |
| e2e/4 | 14.90→11.11 | 1,941.60→1,367.54 | 49→49 | 251,715→221,651 | 13,361.50→12,485.50 |
| governance/3 | 14.96→11.32 | 1,954.44→1,421.89 | 45→42 | 247,546→183,723 | 19,161→17,632.33 |
| requisition/4 | 15→11.93 | 1,963→1,474.73 | 15→17 | 100,966→96,378 | 7,781→7,501.50 |
| rfq/4 | 14.96→11.42 | 1,954.77→1,436.50 | 54→49 | 251,717→227,532 | 18,214.50→16,714 |
| robustness/3 | 15→11.78 | 1,963→1,469.22 | 34→45 | 143,851→186,389 | 14,233→15,180.67 |
| sourcing/6 | 15→11.88 | 1,963→1,475.59 | 84→99 | 371,014→562,739 | 16,805.33→17,608.33 |

### RFQ / Approval 单独观察

RFQ 4 项：turns 26→26，calls 54→49，累计 schema 50,824→37,349，total tokens 257,551→233,056。3/4 Input/Total 下降；RFQ-05 多一轮，Total +1,335，故不能说每个任务都一致获益。Governance 和 E2E 也各自聚合下降；这仍只是本批次方向性证据。

RFQ_PREPARATION / POST_RFQ 同轮 schema 节省分别为 756 / 1,397。WAITING_APPROVAL 会暂停模型循环，档案中没有 PENDING_APPROVAL model request，不能虚构“4 个工具阶段”的模型成本节省。

### Requisition / Sourcing 单独观察

Requisition：turns 14→15、calls 15→17、Input 100,966→96,378，2 降 / 2 升，收益不一致。REQ-03/04 从直接澄清变为先读两个工具再澄清，增加 1 轮；相关只读工具两侧都可见，无法证明由隐藏引起。

Sourcing：turns 39→58、calls 84→99、Input 371,014→562,739、Total 383,679→576,849；2 降 / 4 升。单轮 schema 1,963→1,475.59，但累计 schema 76,557→85,584，因为轮数增加。该组本次整体未受益，主要由 SRC-02/05 长轨迹驱动。

不能只按 12/15、13/15 工具数量判断 schema 价值：分析阶段实际减少 518/1,963 ≈26.4%，寻源阶段减少 290/1,963 ≈14.8%。直接节省不是零；现有证据无法证明它足以抵消行为路径变化，也不能证明行为变化由该筛选引起。

## 4. Tool Sequence Analysis

原始 executions 容器顺序并非执行顺序。分析用 model request 顺序、TOOL_REQUESTED.modelToolCallId→内部 toolCallId、trace timestamp 与 execution result 连接。`tool-sequences.json` 保存完整 28×2 序列、arguments、版本/实体证据、前序调用编号、上下文可见事实重叠、工具是否被隐藏。

跨 phase 先配对同名同参数，再按同名出现次数配对；剩余为“工具名称计数盈余”，不是因果一一配对。7 个增长任务有 P5 盈余 44、P4 盈余 2，净 +42；不能把盈余 44 全部称为 Selector 的新增调用。

| 类别 | 增长任务 P5 盈余 calls | 解释 |
| --- | --- | --- |
| A Exact Repeat | 0 | 同工具同参数、成功返回相同版本事实；两侧均未发现 |
| B 前序跨工具事实重叠 | 7 | SRC-02 六次 supplier detail；SRC-07 一次部分重叠 list_suppliers |
| B 同一 model turn 的结果重叠 | 7 | SRC-05 六次 detail 与同批 list；ROB-01 新 list 与同批 detail。不是已看过结果后的重查 |
| C 阶段切换后事实重叠 | 1 | SRC-07 Search，仍执行新 Eligibility，不等于整次调用无用 |
| D 明确注入/同请求恢复 | 0 | 本批盈余没有匹配此类别；不能将 ROB-01 全部额外调用当恢复成本 |
| E 新事实获取 proxy | 28 | 没有已获取标量事实重叠；证明获得新事实，不证明必要性或 reasoning 质量 |
| UNKNOWN | 1 | SRC-07 新增 Case Patch；不猜其额外必要性 |

事实比对键为 `(entityType, entityId, version, field, value)`；剔除纯标识字段，显式映射 Offer 与 canonical record 的同义字段。只在同一 trial 内使用 version 相等证明事实重叠，跨 reset 的版本不可直接相等比较。Search/Finalize 的 canonical 事实重叠不抹掉其 Eligibility、Evidence/绑定等额外语义。E 类“必要 reasoning”仍为 UNKNOWN。

### 关键轨迹

- **SRC-02**：P4 7 turns / 13 calls → P5 19 / 25。P4 第 3 轮一次请求 6 家 performance；P5 第 4–9 轮逐家读取。P5 额外增加 6 次 supplier detail、6 次 history；Patch 从第 4 轮推迟到第 16 轮。第 2 轮 list_suppliers 的相同版本 name/approved/capacity 在第 3 轮 Context 中仍可见（已解码 recordsTable），6 次 detail 各重复这 3 个事实。工具从未隐藏，且重查发生在同一 REQUEST_ANALYSIS 阶段。
- **SRC-05**：6 / 13 →13 /25。新增加 6 次 supplier detail（与 list 同一模型批次，不能称为已知后重查）和6次逐轮 history；Patch 从第3轮推迟到第10轮。
- **ROB-01**：7 /18 →10 /24。两侧都有同一个首读 503 注入；P4 随后重试 get_item，P5 未同参数重试，通过后续 Search 获得商品事实。P5 增加 list_suppliers、supplier-006 performance、5 次 history，同时少1次重试，净+6。不是多了6次故障重试。
- **ROB-03**：7 /13 →6 /18。list_suppliers 换成6次 get_supplier，净+5，但同一模型批次请求，轮数反而少1。不是“更多调用必然更多轮”。
- **SRC-07**：4 /7 →6 /10。额外 list_suppliers、Patch、Search；Search 复用了部分已读商品/预算/履约事实，同时完成 Eligibility。

### 重点事实再获取

| Fact family | P4 read calls / 含同版本重叠 | P5 read calls / 含同版本重叠 |
| --- | --- | --- |
| Budget | 42 / 19 | 45 / 20 |
| Procurement Purchase History | 11 / 0 | 22 / 0 |
| Supplier | 43 / 1 | 47 / 8 |
| Procurement Supplier Performance | 123 / 18 | 125 / 22 |
| Material Request | 3 / 0 | 3 / 0 |
| Item | 45 / 21 | 45 / 19 |

表中一个 Search 可涉及多个 family，不可跨行求和。原始 history 行与 performance 汇总不是同一事实；history 读取从11→22，但没有已读历史明细的同版本重查。Supplier 的先前事实重叠从1→8。标准 recordsTable 已无损展开；其他无法解析的投影/文本不能用“未检出”推断事实不存在。

## 5. Token Composition

`turn-composition.json` 保存同 task 的逐轮双方数据、ordinal Δ 和缺失尾轮。第 k 轮未必是同一业务动作，ordinal 对齐仅供人工检查，不是相同状态的反事实实验。下表是实际请求的累计本地估算（每轮重计），不是 provider usage 分项。

| Component | Phase4 | Phase5 | Δ |
| --- | --- | --- | --- |
| instructions | 284,424 | 304,740 | +20,316 |
| tool_schema | 328,714 | 261,067 | -67,647 |
| business_state | 9,899 | 9,646 | -253 |
| run_state | 16,469 | 17,635 | +1,166 |
| conversation | 22,690 | 26,015 | +3,325 |
| tool_result | 413,353 | 482,733 | +69,380 |
| other | 26,802 | 31,721 | +4,919 |
| total_estimated | 1,102,351 | 1,133,557 | +31,206 |

Schema 累计减少 **67,647**，但 tool-result 上下文累计增加 **69,380**，仅这一项已超过 schema 的跨轨迹减少；instructions 因多12轮增加20,316，conversation 增加3,325，run state 增加1,166，call framing/other 增加4,919，business state 减少253。合计本地估算净增31,206。

Provider Input 净增85,033：Agent model 1,482,533→1,567,566，input guardrail 两侧都5,379。Provider Output 43,995→45,821（+1,826），其中 Agent model 43,009→44,825，guardrail 986→996。Total 净增86,859。不能把本地字符估算净增31,206强行凑成 provider 净增85,033；provider 编码、消息包装及计量边界不同。

assistantText 的本地输出估算20,080→20,797；每轮 provider model output 256.01→249.03，未见每轮输出变长。所有 reasoningContent 都为空，因此不能声称隐藏 reasoning token 增加或不存在；只有可观察输出和轮数。

## 6. Turn-normalized Metrics

| 指标 | Phase4 | Phase5 |
| --- | --- | --- |
| Model turns | 168 | 180 |
| Provider Agent Input / turn | 8,824.60 | 8,708.70 |
| Provider Input（含 guardrail）/ model turn | 8,856.62 | 8,738.58 |
| Schema estimate / turn | 1,956.63 | 1,450.37 |
| Tool-result estimate / turn | 2,460.43 | 2,681.85 |
| Tool calls / turn | 1.85 | 1.82 |
| Provider Agent Output / turn | 256.01 | 249.03 |

每轮 Agent Input 略低，但轮数168→180；tool-result/turn反而升高。纯算术分解：`12 × P4每轮Input = +105,895.21`，`180 × (P5每轮Input − P4每轮Input) = −20,862.21`，合计+85,033。这是描述性分解，不将某一项解释成 Selector 的独立因果效应。

## 7. Direct Schema Saving

用 Phase5 每一实际轮的 `candidate_schema_estimated_tokens − tool_schema_estimated_tokens` 计算；候选已应用白名单与 singleUse，POST_RFQ 比较14个候选而非15个。已核验实际 selected ToolDefinitions 与事件一致。

| Exposure label | Turns | Visible | Candidate / selected schema per turn | Saved / turn | Total saved |
| --- | --- | --- | --- | --- | --- |
| POST_RFQ | 5 | 5 | 1,749 / 352 | 1,397 | 6,985 |
| REQUEST_ANALYSIS | 110 | 12 | 1,963 / 1,445 | 518 | 56,980 |
| RFQ_PREPARATION | 18 | 8 | 1,963 / 1,207 | 756 | 13,608 |
| SOURCING | 47 | 13 | 1,963 / 1,673 | 290 | 13,630 |

总计 **91,203**；每轮 **506.68**；每任务平均 **3,257.25**。每任务数值在第2节表，逐轮数值在配对 JSON / turn JSON。PENDING_APPROVAL 没有模型样本。大部分累计节省来自110次 REQUEST_ANALYSIS，不能说直接收益只来自RFQ。

跨 phase 累计 schema 差为−67,647，而同轮直接节省91,203：P5候选累计352,270 − P4实际328,714 = +23,556；352,270 − 91,203 = 261,067。额外轮数/候选权重抵消了一部分直接节省。

## 8. Selector-induced Cost

**可证明的 INDIRECT_SELECTION_COST：0 个；“Selector 不影响规划”仍无法证明。** 本次无 hidden-tool attempt 或 tool-not-found，且这些零值不被当作零隐性成本的充分证据。

扩大检索覆盖全28项：当前工具返回的事实，与本 run 某个被隐藏的只读工具的实际结果相交，得到116个候选调用。所有候选的隐藏替代都是 `procurement_supplier_search`，当前 canonical context 均为 UNCREATED；Search 即使在 Phase4 暴露，也会因 Case not found 被拒绝（`ProcurementToolHandler.currentCase/search`）。未找到一个当时可执行、仅因 selector 被隐藏、迫使模型用 B→C 替代的直接工具。该检索只覆盖档案中真实观察到的替代工具，未知工具组合保持 UNKNOWN。

7个增长任务的44个名称盈余调用中，**43个在 REQUEST_ANALYSIS，只有1个在 SOURCING**；SRC-02/05 的额外 supplier/history 与更串行的 performance 发生在首次阶段切换前。不能解释成“进入新阶段后以为事实丢失”。SRC-02 的旧supplier事实在模型请求中仍可见，排除了该例的实际 Context 丢失；不能读取模型心理。

| P5 exposure transitions | Task count | Call Δ sum / median | Turn Δ sum |
| --- | --- | --- | --- |
| 0 | 6 | +4 / 0 | +2 |
| 1 | 4 | +1 / -0.50 | -1 |
| 2 | 13 | +14 / 0 | +13 |
| 3 | 5 | -1 / 0 | -2 |

不呈现单调的“切换越多成本越高”：3次切换的5项合计calls−1/turns−2，2次切换包含长尾也包含改善任务。实际持久 Case status 转换数另存每任务 JSON，不能把 Finalize 后的 exposure label 当第二套业务状态。Phase4没有selector阶段事件，不能将其 C=0 与Phase5 C作直接增量比较。

观测到的是局部重复获取与规划路径扩展；是否由“少了 Search/Finalize/RFQ schema 的提示作用”导致、是否为模型随机路径，两个单次样本均不足判定。本文不将这一可能机制写成已证实 production bug；未修任何生产逻辑。

## 9. Recommended Architecture Decision

建议保留实现供实验，通过现有受信任 profile flag 控制，**在获得配对 A/B 证据前，不将 Phase5 默认启用作为后续 Harness 的固定前提**。本任务没有改 flag、默认配置或回退源码。

RFQ 当前聚合有益，但RFQ-05反向，且Governance/E2E/Anomaly也改善，证据不足以直接定为 KEEP BUT NARROW。已证明的重复事实读取不足以证明 Selector 诱发，不满足 REVERT 的因果证据要求；也不把局部改善及已投入工作量当 KEEP 的理由。最终研究判断仍为 INCONCLUSIVE，实验状态建议不是新增因果结论。

## 10. Optional Minimal A/B Plan

**仅方案，未执行。** 建议8项 ×2配置 ×2 trials = **32 valid trials**：SRC-02、SRC-05（长尾）；SRC-01（同类改善对照）；ROB-01（注入与读取扩展）；REQ-03（额外预读）；RFQ-02（当前改善）；RFQ-05（RFQ反向）；E2E-04（跨业务对照）。这是定向诊断集，不是全体泛化 benchmark。

使用同一最终 Phase5 源码，唯一配置差异为 stageToolSelectionEnabled 开/关；两侧保持 Phase4 projection/compaction、Prompt、单工具contract、业务权限、预算和retry相同。flag 当前会同时影响 schema 预留预算，因此先登记为既有“Dynamic Loading bundle”的 A/B，不冒称仅测可见名称单变量。按 task/trial 成对交错 AB / BA，固定模型 options，冻结世界后每次reset，保留所有有效结果；infra replacement单列，不重跑有效失败。

预登记：安全不变量任一违规立即停止；报告全部配对 task/trial 的调用/轮数/usage差，SRC-02/05 的串行化、list→detail重叠、history扩展是否重复出现，以及同轮schema节省。2次试次仍不支撑强统计因果或最终质量认证；不足则保持实验状态，不自动扩大到120。是否花这笔成本由人工决定。

### 文件与范围核验

新增分析脚本及数据位于 `evals/dynamic-tool-loading/audits/`；原始Phase4/5 reports与archives保持不变。开始时对既有src/docs/evals文件记录SHA256，结束比对见 `audits/scope-verification.json`。分支保持 `feat/dynamic-tool-loading`；原Handler字节不变、未暂存、未提交。没有commit/push/tag，没有模型、Smoke/Dev/Holdout/40×3运行。

