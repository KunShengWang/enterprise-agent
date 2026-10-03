"""Render the deterministic offline audit outputs; never invokes a model."""
from pathlib import Path
import json
import statistics

ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).parent
read=lambda name:json.loads((HERE/name).read_text(encoding='utf-8'))
s=read('summary.json');paired=read('phase4-vs-phase5-by-task.json');ps=paired['tasks'];sequences=read('tool-sequences.json')['tasks']
lines=[]
def add(text=''):lines.append(text)
def table(headers,rows):
    add('| '+' | '.join(headers)+' |');add('| '+' | '.join('---' for _ in headers)+' |')
    for row in rows:add('| '+' | '.join(str(v) for v in row)+' |')
    add()
def fmt(v):return f'{v:,.2f}' if isinstance(v,float) and not v.is_integer() else f'{v:,.0f}'
def pm(v):return ('+' if v>0 else '')+fmt(v)

add('# Phase 5 Dynamic Tool Loading：纯离线归因审计\n')
add('## 1. Executive Conclusion\n')
add('**结论：INCONCLUSIVE。架构建议：FEATURE-FLAG / EXPERIMENTAL，暂不升级为后续默认 Harness。**\n')
add('已经证明的是 Schema 暴露减少，以及本次运行中少数任务有更多读取/更长调用轨迹；没有证明这些额外轨迹由 Selector 引起，也没有证据把它们断言为随机波动。因此不强行 KEEP，不据此 REVERT，也不认为已经证明“仅保留 RFQ”是最优配置。\n')
add('直接技术结果：在 Phase 5 实际 180 个模型请求上，候选集合与选中集合的同轮差额为 **91,203 个本地估算 Schema Tokens**，平均每轮 506.68、每任务 3,257.25。它不是 provider 计费节省，也不是 Phase4/5 总 usage 差。\n')
add('Input 在 **20/28** 个任务下降、8 个上升。SRC-02 / SRC-05 分别增加 134,959 / 92,186，合计 **+227,145**；其余 26 项合计 **−142,112**，抵消后全体净增 +85,033。Task Success 两侧均 28/28；五项不变量保持 0。\n')
add('仅读取既有 Phase 4/5 Dev ZIP、原始 request/result/events 和冻结任务；没有模型调用、测试集重跑或生产代码修改。排除 Phase 5 REQ-03-1-a1 的 reset infra attempt，配对其有效 a2。SHA256 校验档案及逐文件 provenance，按 task_id/trial1 唯一配对，核验相同 task payload、Prompt、model options、retry controls、resolved model 与单工具 contract。Gate：`COMPARABLE_FOR_OFFLINE_DESCRIPTIVE_AUDIT`，不等于随机对照因果 gate。模型缺少 immutable build。\n')
add('复现命令（纯离线）：\n```powershell\npython -B evals/dynamic-tool-loading/audits/analyze.py\npython -B evals/dynamic-tool-loading/audits/render_report.py\n```\n')
add('## 2. Task-Level Delta\n')
add('完整两侧 turns/calls/errors/input/output/total/peak/latency 与全部差值见 `evals/dynamic-tool-loading/audits/phase4-vs-phase5-by-task.json`。以下 Δ = Phase5 − Phase4；“改善”仅指成本数值下降，不是业务评分变化。\n')
table(['指标','下降 / 不变 / 上升','Median Δ','P25 Δ','P75 Δ','P95 Δ'],[
    [k,f"{v['improved']} / {v['unchanged']} / {v['regressed']}",fmt(v['median']),fmt(v['p25']),fmt(v['p75']),fmt(v['p95'])]
    for k,v in s['delta_statistics'].items() if k in ('turns','tool_calls','input_tokens','total_tokens','peak_context_tokens')])
add('差值分位数使用线性插值 `(n−1)q`，因此 calls/turns 的 P95 可为小数；原冻结全局 Peak P95 继续使用 nearest-rank `ceil(nq)`，两种口径不混算。\n')
table(['Task','Turns P4→P5','Calls P4→P5','Δ Input','Δ Total','Δ Peak','Δ latency ms','同轮 Schema 节省'],[
    [p['task_id'],f"{p['phase4']['turns']}→{p['phase5']['turns']}",f"{p['phase4']['tool_calls']}→{p['phase5']['tool_calls']}",pm(p['delta']['input_tokens']),pm(p['delta']['total_tokens']),pm(p['delta']['peak_context_tokens']),pm(p['delta']['task_latency_ms']),fmt(p['phase5']['direct_schema_saved'])] for p in ps])
add('7 个 calls 增长任务合计 +42，另外 8 个 calls 下降任务抵消 −24，净 +18。并非大多数任务都略增。Peak 21 项下降、7 项上升；平均值下降，但长尾换成 SRC-02 / ROB-01 / SRC-05。\n')
table(['Phase','Peak top 5（Task=value）'],[[phase,', '.join(f"{v['task_id']}={v['peak']:,}" for v in rows)] for phase,rows in s['peak_tail'].items()])
add('28 项的 nearest-rank P95 是第二高值：Phase4 的 GOV-02 20,479 → Phase5 的 ROB-01 22,732。这是不同任务的顺序统计量，不是同一个任务直接增了 2,253。\n')

add('## 3. Stage-Level Delta\n')
add('业务组来自冻结 task.group，保留原 `anomaly`，不将 ANOM 靠前缀改归 Requisition。业务组不等于运行中的 exposure label：非 RFQ 任务也会在 Finalize 后出现 RFQ_PREPARATION。工具数/schema 均值按组内实际 model turns 加权；Peak 均值按 task 加权。\n')
table(['Task group / N','Visible P4→P5','Schema/turn P4→P5','Calls P4→P5','Input P4→P5','Mean Peak P4→P5'],[
    [g+'/'+str(len(v['tasks'])),f"{fmt(v['phase4']['visible_tools_mean'])}→{fmt(v['phase5']['visible_tools_mean'])}",f"{fmt(v['phase4']['schema_mean'])}→{fmt(v['phase5']['schema_mean'])}",f"{v['phase4']['tool_calls']}→{v['phase5']['tool_calls']}",f"{v['phase4']['input_tokens']:,}→{v['phase5']['input_tokens']:,}",f"{fmt(v['phase4']['peak_context_mean'])}→{fmt(v['phase5']['peak_context_mean'])}"] for g,v in s['groups'].items()])
add('### RFQ / Approval 单独观察\n')
add('RFQ 4 项：turns 26→26，calls 54→49，累计 schema 50,824→37,349，total tokens 257,551→233,056。3/4 Input/Total 下降；RFQ-05 多一轮，Total +1,335，故不能说每个任务都一致获益。Governance 和 E2E 也各自聚合下降；这仍只是本批次方向性证据。\n')
add('RFQ_PREPARATION / POST_RFQ 同轮 schema 节省分别为 756 / 1,397。WAITING_APPROVAL 会暂停模型循环，档案中没有 PENDING_APPROVAL model request，不能虚构“4 个工具阶段”的模型成本节省。\n')
add('### Requisition / Sourcing 单独观察\n')
add('Requisition：turns 14→15、calls 15→17、Input 100,966→96,378，2 降 / 2 升，收益不一致。REQ-03/04 从直接澄清变为先读两个工具再澄清，增加 1 轮；相关只读工具两侧都可见，无法证明由隐藏引起。\n')
add('Sourcing：turns 39→58、calls 84→99、Input 371,014→562,739、Total 383,679→576,849；2 降 / 4 升。单轮 schema 1,963→1,475.59，但累计 schema 76,557→85,584，因为轮数增加。该组本次整体未受益，主要由 SRC-02/05 长轨迹驱动。\n')
add('不能只按 12/15、13/15 工具数量判断 schema 价值：分析阶段实际减少 518/1,963 ≈26.4%，寻源阶段减少 290/1,963 ≈14.8%。直接节省不是零；现有证据无法证明它足以抵消行为路径变化，也不能证明行为变化由该筛选引起。\n')

add('## 4. Tool Sequence Analysis\n')
add('原始 executions 容器顺序并非执行顺序。分析用 model request 顺序、TOOL_REQUESTED.modelToolCallId→内部 toolCallId、trace timestamp 与 execution result 连接。`tool-sequences.json` 保存完整 28×2 序列、arguments、版本/实体证据、前序调用编号、上下文可见事实重叠、工具是否被隐藏。\n')
add('跨 phase 先配对同名同参数，再按同名出现次数配对；剩余为“工具名称计数盈余”，不是因果一一配对。7 个增长任务有 P5 盈余 44、P4 盈余 2，净 +42；不能把盈余 44 全部称为 Selector 的新增调用。\n')
table(['类别','增长任务 P5 盈余 calls','解释'],[
    ['A Exact Repeat',0,'同工具同参数、成功返回相同版本事实；两侧均未发现'],
    ['B 前序跨工具事实重叠',s['growth_task_surplus_categories'].get('B_EQUIVALENT_FACT_OVERLAP',0),'SRC-02 六次 supplier detail；SRC-07 一次部分重叠 list_suppliers'],
    ['B 同一 model turn 的结果重叠',s['growth_task_surplus_categories'].get('B_SAME_BATCH_FACT_OVERLAP',0),'SRC-05 六次 detail 与同批 list；ROB-01 新 list 与同批 detail。不是已看过结果后的重查'],
    ['C 阶段切换后事实重叠',s['growth_task_surplus_categories'].get('C_STAGE_TRANSITION_FACT_OVERLAP',0),'SRC-07 Search，仍执行新 Eligibility，不等于整次调用无用'],
    ['D 明确注入/同请求恢复',0,'本批盈余没有匹配此类别；不能将 ROB-01 全部额外调用当恢复成本'],
    ['E 新事实获取 proxy',s['growth_task_surplus_categories'].get('E_ADDITIONAL_FACT_ACQUISITION',0),'没有已获取标量事实重叠；证明获得新事实，不证明必要性或 reasoning 质量'],
    ['UNKNOWN',s['growth_task_surplus_categories'].get('UNKNOWN',0),'SRC-07 新增 Case Patch；不猜其额外必要性']])
add('事实比对键为 `(entityType, entityId, version, field, value)`；剔除纯标识字段，显式映射 Offer 与 canonical record 的同义字段。只在同一 trial 内使用 version 相等证明事实重叠，跨 reset 的版本不可直接相等比较。Search/Finalize 的 canonical 事实重叠不抹掉其 Eligibility、Evidence/绑定等额外语义。E 类“必要 reasoning”仍为 UNKNOWN。\n')
add('### 关键轨迹\n')
add('- **SRC-02**：P4 7 turns / 13 calls → P5 19 / 25。P4 第 3 轮一次请求 6 家 performance；P5 第 4–9 轮逐家读取。P5 额外增加 6 次 supplier detail、6 次 history；Patch 从第 4 轮推迟到第 16 轮。第 2 轮 list_suppliers 的相同版本 name/approved/capacity 在第 3 轮 Context 中仍可见（已解码 recordsTable），6 次 detail 各重复这 3 个事实。工具从未隐藏，且重查发生在同一 REQUEST_ANALYSIS 阶段。\n- **SRC-05**：6 / 13 →13 /25。新增加 6 次 supplier detail（与 list 同一模型批次，不能称为已知后重查）和6次逐轮 history；Patch 从第3轮推迟到第10轮。\n- **ROB-01**：7 /18 →10 /24。两侧都有同一个首读 503 注入；P4 随后重试 get_item，P5 未同参数重试，通过后续 Search 获得商品事实。P5 增加 list_suppliers、supplier-006 performance、5 次 history，同时少1次重试，净+6。不是多了6次故障重试。\n- **ROB-03**：7 /13 →6 /18。list_suppliers 换成6次 get_supplier，净+5，但同一模型批次请求，轮数反而少1。不是“更多调用必然更多轮”。\n- **SRC-07**：4 /7 →6 /10。额外 list_suppliers、Patch、Search；Search 复用了部分已读商品/预算/履约事实，同时完成 Eligibility。\n')
add('### 重点事实再获取\n')
table(['Fact family','P4 read calls / 含同版本重叠','P5 read calls / 含同版本重叠'],[
    [g,f"{v['phase4']['read_calls']} / {v['phase4']['same_version_overlap_calls']}",f"{v['phase5']['read_calls']} / {v['phase5']['same_version_overlap_calls']}"] for g,v in s['fact_family_reacquisition'].items()])
add('表中一个 Search 可涉及多个 family，不可跨行求和。原始 history 行与 performance 汇总不是同一事实；history 读取从11→22，但没有已读历史明细的同版本重查。Supplier 的先前事实重叠从1→8。标准 recordsTable 已无损展开；其他无法解析的投影/文本不能用“未检出”推断事实不存在。\n')

add('## 5. Token Composition\n')
add('`turn-composition.json` 保存同 task 的逐轮双方数据、ordinal Δ 和缺失尾轮。第 k 轮未必是同一业务动作，ordinal 对齐仅供人工检查，不是相同状态的反事实实验。下表是实际请求的累计本地估算（每轮重计），不是 provider usage 分项。\n')
table(['Component','Phase4','Phase5','Δ'],[[k,fmt(v),fmt(s['composition']['phase5'][k]),pm(s['composition']['phase5'][k]-v)] for k,v in s['composition']['phase4'].items()])
add('Schema 累计减少 **67,647**，但 tool-result 上下文累计增加 **69,380**，仅这一项已超过 schema 的跨轨迹减少；instructions 因多12轮增加20,316，conversation 增加3,325，run state 增加1,166，call framing/other 增加4,919，business state 减少253。合计本地估算净增31,206。\n')
add('Provider Input 净增85,033：Agent model 1,482,533→1,567,566，input guardrail 两侧都5,379。Provider Output 43,995→45,821（+1,826），其中 Agent model 43,009→44,825，guardrail 986→996。Total 净增86,859。不能把本地字符估算净增31,206强行凑成 provider 净增85,033；provider 编码、消息包装及计量边界不同。\n')
add('assistantText 的本地输出估算20,080→20,797；每轮 provider model output 256.01→249.03，未见每轮输出变长。所有 reasoningContent 都为空，因此不能声称隐藏 reasoning token 增加或不存在；只有可观察输出和轮数。\n')

add('## 6. Turn-normalized Metrics\n')
table(['指标','Phase4','Phase5'],[[label,fmt(s['turn_normalized']['phase4'][key]),fmt(s['turn_normalized']['phase5'][key])] for label,key in [
    ('Model turns','turns'),('Provider Agent Input / turn','model_input_per_turn'),('Provider Input（含 guardrail）/ model turn','input_including_guardrail_per_model_turn'),
    ('Schema estimate / turn','schema_per_turn'),('Tool-result estimate / turn','tool_results_per_turn'),('Tool calls / turn','tool_calls_per_turn'),('Provider Agent Output / turn','output_per_turn')]])
add('每轮 Agent Input 略低，但轮数168→180；tool-result/turn反而升高。纯算术分解：`12 × P4每轮Input = +105,895.21`，`180 × (P5每轮Input − P4每轮Input) = −20,862.21`，合计+85,033。这是描述性分解，不将某一项解释成 Selector 的独立因果效应。\n')

add('## 7. Direct Schema Saving\n')
add('用 Phase5 每一实际轮的 `candidate_schema_estimated_tokens − tool_schema_estimated_tokens` 计算；候选已应用白名单与 singleUse，POST_RFQ 比较14个候选而非15个。已核验实际 selected ToolDefinitions 与事件一致。\n')
table(['Exposure label','Turns','Visible','Candidate / selected schema per turn','Saved / turn','Total saved'],[
    [k,v['turns'],fmt(v['visible_tools_mean']),f"{fmt(v['candidate_schema_mean'])} / {fmt(v['selected_schema_mean'])}",fmt(v['saved_per_turn']),fmt(v['direct_schema_saved'])] for k,v in s['exposure_stages'].items()])
add('总计 **91,203**；每轮 **506.68**；每任务平均 **3,257.25**。每任务数值在第2节表，逐轮数值在配对 JSON / turn JSON。PENDING_APPROVAL 没有模型样本。大部分累计节省来自110次 REQUEST_ANALYSIS，不能说直接收益只来自RFQ。\n')
add('跨 phase 累计 schema 差为−67,647，而同轮直接节省91,203：P5候选累计352,270 − P4实际328,714 = +23,556；352,270 − 91,203 = 261,067。额外轮数/候选权重抵消了一部分直接节省。\n')

add('## 8. Selector-induced Cost\n')
add('**可证明的 INDIRECT_SELECTION_COST：0 个；“Selector 不影响规划”仍无法证明。** 本次无 hidden-tool attempt 或 tool-not-found，且这些零值不被当作零隐性成本的充分证据。\n')
add('扩大检索覆盖全28项：当前工具返回的事实，与本 run 某个被隐藏的只读工具的实际结果相交，得到116个候选调用。所有候选的隐藏替代都是 `procurement_supplier_search`，当前 canonical context 均为 UNCREATED；Search 即使在 Phase4 暴露，也会因 Case not found 被拒绝（`ProcurementToolHandler.currentCase/search`）。未找到一个当时可执行、仅因 selector 被隐藏、迫使模型用 B→C 替代的直接工具。该检索只覆盖档案中真实观察到的替代工具，未知工具组合保持 UNKNOWN。\n')
add('7个增长任务的44个名称盈余调用中，**43个在 REQUEST_ANALYSIS，只有1个在 SOURCING**；SRC-02/05 的额外 supplier/history 与更串行的 performance 发生在首次阶段切换前。不能解释成“进入新阶段后以为事实丢失”。SRC-02 的旧supplier事实在模型请求中仍可见，排除了该例的实际 Context 丢失；不能读取模型心理。\n')
table(['P5 exposure transitions','Task count','Call Δ sum / median','Turn Δ sum'],[[k,len(v['tasks']),f"{pm(v['call_delta_sum'])} / {fmt(v['call_delta_median'])}",pm(v['turn_delta_sum'])] for k,v in s['transition_groups'].items()])
add('不呈现单调的“切换越多成本越高”：3次切换的5项合计calls−1/turns−2，2次切换包含长尾也包含改善任务。实际持久 Case status 转换数另存每任务 JSON，不能把 Finalize 后的 exposure label 当第二套业务状态。Phase4没有selector阶段事件，不能将其 C=0 与Phase5 C作直接增量比较。\n')
add('观测到的是局部重复获取与规划路径扩展；是否由“少了 Search/Finalize/RFQ schema 的提示作用”导致、是否为模型随机路径，两个单次样本均不足判定。本文不将这一可能机制写成已证实 production bug；未修任何生产逻辑。\n')

add('## 9. Recommended Architecture Decision\n')
add('建议保留实现供实验，通过现有受信任 profile flag 控制，**在获得配对 A/B 证据前，不将 Phase5 默认启用作为后续 Harness 的固定前提**。本任务没有改 flag、默认配置或回退源码。\n')
add('RFQ 当前聚合有益，但RFQ-05反向，且Governance/E2E/Anomaly也改善，证据不足以直接定为 KEEP BUT NARROW。已证明的重复事实读取不足以证明 Selector 诱发，不满足 REVERT 的因果证据要求；也不把局部改善及已投入工作量当 KEEP 的理由。最终研究判断仍为 INCONCLUSIVE，实验状态建议不是新增因果结论。\n')

add('## 10. Optional Minimal A/B Plan\n')
add('**仅方案，未执行。** 建议8项 ×2配置 ×2 trials = **32 valid trials**：SRC-02、SRC-05（长尾）；SRC-01（同类改善对照）；ROB-01（注入与读取扩展）；REQ-03（额外预读）；RFQ-02（当前改善）；RFQ-05（RFQ反向）；E2E-04（跨业务对照）。这是定向诊断集，不是全体泛化 benchmark。\n')
add('使用同一最终 Phase5 源码，唯一配置差异为 stageToolSelectionEnabled 开/关；两侧保持 Phase4 projection/compaction、Prompt、单工具contract、业务权限、预算和retry相同。flag 当前会同时影响 schema 预留预算，因此先登记为既有“Dynamic Loading bundle”的 A/B，不冒称仅测可见名称单变量。按 task/trial 成对交错 AB / BA，固定模型 options，冻结世界后每次reset，保留所有有效结果；infra replacement单列，不重跑有效失败。\n')
add('预登记：安全不变量任一违规立即停止；报告全部配对 task/trial 的调用/轮数/usage差，SRC-02/05 的串行化、list→detail重叠、history扩展是否重复出现，以及同轮schema节省。2次试次仍不支撑强统计因果或最终质量认证；不足则保持实验状态，不自动扩大到120。是否花这笔成本由人工决定。\n')
add('### 文件与范围核验\n')
add('新增分析脚本及数据位于 `evals/dynamic-tool-loading/audits/`；原始Phase4/5 reports与archives保持不变。开始时对既有src/docs/evals文件记录SHA256，结束比对见 `audits/scope-verification.json`。分支保持 `feat/dynamic-tool-loading`；原Handler字节不变、未暂存、未提交。没有commit/push/tag，没有模型、Smoke/Dev/Holdout/40×3运行。\n')
(ROOT/'docs/harness/phase5-attribution-audit.md').write_text('\n'.join(lines)+'\n',encoding='utf-8',newline='\n')
print('Wrote docs/harness/phase5-attribution-audit.md')
