# Harness Reliability V1：冻结与同源码 Smoke / Dev 验收

日期：2026-10-05。**Smoke 9/9；Dev 28/28；ADOPT Phase 4 + Harness Reliability。**

## Git freeze

- Commit：`60edafa29f2954ac305c421c8ad3473cd2d3e63e`
- Message：`增强：补充MCP工具契约与运行时重试`
- Branch：`feat/harness-reliability`；Tag：`procurement-harness-reliability-v1`。远端 branch HEAD / annotated tag peeled commit 已核验一致。
- 显式提交 27 个文件，保持 Phase 4 主线；没有移植 Workflow、Multi-Agent 或 Dynamic Tool Loading 实验代码。
- 依仓库已有惯例保留约 1.43 MB 的 ROB/确定性 evidence.zip。独立 source.patch 原样保留在本地与 ZIP 内，未作为独立文件提交；既有缓存和临时日志未提交。
- Tag 表示此前 deterministic + ROB 单点验收版本。本报告及本轮模型 artifacts 均在 commit/tag/push 之后产生，保持未提交。

## Source / controls / reuse

Production fingerprint：`23ccdfb58f455cb29c4817206efaccabe285d4a9a9d0866d7dd5147c05547d28`。

纯 Tag tree fingerprint：`e2da95052a743567b70f74bfc809d5a1a454cd588697195b4e6a05fe60a21ee4`。两者唯一文件差异为受保护的 Handler 原有注释，见 `tag-vs-working-tree.json`。本轮证明的是 Tag + 原有注释覆盖下的 same-source Smoke/Dev；并非纯 Tag checkout 的逐字节验收。ROB 复用满足历史与当前受测 working tree 内容相同，但不满足“与纯 Tag tree 字节完全一致”的字面条件，此偏差明确保留供 Review。

正式冻结清单包含生产源码（另存原始字节哈希）、编译 class、Prompt、tool schema、完整 MCP metadata/trust、RetryPolicy、Context policy、grader、seed 与实际模型配置；每个 trial 前后及 Smoke→Dev gate 均校验。只把 Git SHA 变化排除在 ROB 复用的内容比较之外；配置比较只规范化 Set/schema 顺序。
ROB-01 复用此前同源码 PASS artifact；Smoke 新执行 8 项，Dev 独立执行 28 项，均已完成。未重复付费运行 Smoke 的 ROB。
Working Tree State：dirty。评测源码包含一直保留的 Handler 用户注释覆盖；它不在 Tag 中，字节与此前 ROB 和本轮开始时完全一致。源码 fingerprint 标识实际受测 working tree，不能用 commit SHA 冒充包含该注释覆盖的完整字节快照。
模型 configured=deepseek-chat，实际 resolved model 见各 artifact；无不可变 provider build，不将别名相同解释为权重完全相同。

## Actual commands / stop policy

```text
git push -u origin feat/harness-reliability
git push origin procurement-harness-reliability-v1
python -B target/reliability-freeze-eval-20261005/run_acceptance.py readiness
python -B target/reliability-freeze-eval-20261005/run_acceptance.py smoke
python -B target/reliability-freeze-eval-20261005/run_acceptance.py dev
```

Smoke：任何 business/safety/infra 硬失败停止，不修复、不重跑。Dev：沿用 Phase 4 runner 对五项 safety 或基础设施失败停止的策略，普通 Business FAIL 保留并继续；另拒绝任何被观察到的 unsafe retry。没有边跑边改。

此前同源码确定性结果：MCP 9/9、Retry 9/9、Focused 33/33；Full Java 1238 PASS / 9 SKIP / 0 FAIL；Replay 380/380；Fixtures 5/5 unchanged。本轮未重复全套确定性测试。

## Smoke results

Business：9/9 PASS；completed=9；FAIL=0。
Frozen Safety violations：`all five zero`；StopReason：`{'COMPLETED': 7, 'WAITING_APPROVAL': 2}`。

| Metric | Observed |
|---|---:|
| provider_calls | 94 |
| agent_calls | 85 |
| guardrail_calls | 9 |
| input_tokens | 886731 |
| output_tokens | 17357 |
| total_tokens | 904088 |
| model_turns | 85 |
| logical_tool_calls | 146 |
| physical_attempts | 146 |
| model_physical_attempts | 145 |
| server_physical_attempts | 1 |
| model_tool_errors | 1 |
| server_tool_errors | 1 |
| retry_attempts | 1 |
| recovered_retryable_errors | 1 |
| retry_exhaustions | 0 |
| retryable_errors | 1 |
| external_generic_retries | 0 |
| Peak context avg / median / P95 | 17508.333333333332 / 18443 / 23299 |
| Latency ms avg / median / P95 | 22567.28238888889 / 18300.901 / 43728.7994 |

ToolArgumentValidationError：count=0；rate=0.0（逻辑调用分母）；同实体恢复=0，后续同工具成功=0；同实体 recovery rate=None；无效额外调用=0。缺少可匹配实体时不把同工具成功冒充已证明同实体恢复。

InvalidRecommendationProposal：0/7，rate=0.0。

UnsupportedExecutionClaim：窄范围显式 RFQ 完成声明候选=2；无先行成功 RFQ receipt=0。已检查 trace 时间顺序；这不是所有自然语言操作声明的穷举验证，零候选的 rate 为 N/A。

实际 MCP 调用契约统计：`{'count': 376, 'trusted_read_only': 374, 'fail_closed': 2, 'denominator': 'All MCP transport calls including internal Provider bridge reads, not model logical calls'}`。分母包括内部 Provider bridge，不等于模型逻辑调用数；source/trust/raw hint/effect 逐次保存。
ToolEffect physical-history 分布：`{'READ_ONLY': 135, 'EXTERNAL_SIDE_EFFECT': 11}`；Outcome：`{'RETRYABLE_ERROR': 1, 'SUCCESS': 144, 'BUSINESS_ERROR': 1}`。

## Dev results

Business：28/28 PASS；completed=28；FAIL=0。
Frozen Safety violations：`all five zero`；StopReason：`{'COMPLETED': 23, 'WAITING_APPROVAL': 5}`。

| Metric | Observed |
|---|---:|
| provider_calls | 212 |
| agent_calls | 184 |
| guardrail_calls | 28 |
| input_tokens | 1712902 |
| output_tokens | 45998 |
| total_tokens | 1758900 |
| model_turns | 184 |
| logical_tool_calls | 336 |
| physical_attempts | 339 |
| model_physical_attempts | 335 |
| server_physical_attempts | 4 |
| model_tool_errors | 9 |
| server_tool_errors | 4 |
| retry_attempts | 4 |
| recovered_retryable_errors | 1 |
| retry_exhaustions | 3 |
| retryable_errors | 7 |
| external_generic_retries | 0 |
| Peak context avg / median / P95 | 14311.035714285714 / 15495.5 / 23297 |
| Latency ms avg / median / P95 | 16780.29150357143 / 16670.84005 / 26221.2742 |

ToolArgumentValidationError：count=0；rate=0.0（逻辑调用分母）；同实体恢复=0，后续同工具成功=0；同实体 recovery rate=None；无效额外调用=0。缺少可匹配实体时不把同工具成功冒充已证明同实体恢复。

InvalidRecommendationProposal：0/18，rate=0.0。

UnsupportedExecutionClaim：窄范围显式 RFQ 完成声明候选=3；无先行成功 RFQ receipt=0。已检查 trace 时间顺序；这不是所有自然语言操作声明的穷举验证，零候选的 rate 为 N/A。

实际 MCP 调用契约统计：`{'count': 680, 'trusted_read_only': 675, 'fail_closed': 5, 'denominator': 'All MCP transport calls including internal Provider bridge reads, not model logical calls'}`。分母包括内部 Provider bridge，不等于模型逻辑调用数；source/trust/raw hint/effect 逐次保存。
ToolEffect physical-history 分布：`{'READ_ONLY': 308, 'EXTERNAL_SIDE_EFFECT': 31}`；Outcome：`{'SUCCESS': 326, 'BUSINESS_ERROR': 6, 'RETRYABLE_ERROR': 7}`。

## Phase 4 directional comparison

Gate：`COMPARABLE_FOR_DIRECTIONAL_DEV`；control mismatches：`[]`。

| Metric | Phase 4 | Phase 4 + Reliability | Δ |
|---|---:|---:|---:|
| Dev Success | 28.00 | 28.00 | +0.00 |
| Input Tokens | 1,487,912.00 | 1,712,902.00 | +224,990.00 |
| Total Tokens | 1,531,907.00 | 1,758,900.00 | +226,993.00 |
| Logical Tool Calls | 310.00 | 336.00 | +26.00 |
| Physical Attempts (all actors) | 309.00 | 339.00 | +30.00 |
| Physical Attempts (model) | 305.00 | 335.00 | +30.00 |
| Physical Attempts (environment) | 4.00 | 4.00 | +0.00 |
| Tool Errors (model attempts) | 6.00 | 9.00 | +3.00 |
| Model Turns | 168.00 | 184.00 | +16.00 |
| Avg Peak Context | 13,825.89 | 14,311.04 | +485.14 |
| P95 Peak Context | 20,479.00 | 23,297.00 | +2,818.00 |
| Avg Latency ms | 16,339.73 | 16,780.29 | +440.56 |
| P95 Latency ms | 22,704.96 | 26,221.27 | +3,516.32 |
| Frozen Safety | all zero | all zero | 0 |

Dev×1 directional observation only。schema 比较保留名称、描述、完整输入 schema 与其他 metadata，只将本阶段预期的 MCP trust/annotations/effect 分开；任务、seed、grader、model options、Prompt 同源对照通过才展示 Δ。没有用单次数据声称稳定因果效率或成功率提升。

## Actual new-run cost / reliability attribution

排除复用 ROB，本轮新增 Provider calls=299，input=2536479，output=60977，total=2597456。

Retry recovery rate 按被重试的逻辑调用计：Smoke 1/1 = 100%；Dev 1/4 = 25%。Dev 的 4 次 retry decision 对应 4 次额外物理 attempt；其中 ROB-01 恢复 1 次，ROB-02 三个不同逻辑读各重试一次后耗尽。Dev 的 7 个 RETRYABLE_ERROR 是物理失败 attempt 数，不是恢复率分母。两组 external-side-effect generic retry 均为 0。

逻辑调用包含审批暂停等未派发调用，物理 attempt 只按实际执行记录计，retry 会增加物理次数。Phase 4 frozen grader 的 tool_attempts=310 对未派发调用保留 minimum 1，不能作为实际物理执行分母；从原始 evidence 核算为 309（model 305 + environment 4）。本轮 Dev 为 339（model 335 + environment 4）。

Dev model tool errors 6→9 的 +3 对应持续故障下额外的失败 retry attempts；环境治理探针 errors 仍为 4，单独统计。当前 9 个 model errors 包括 2 个 ERP_NOT_FOUND、ROB-01 的 1 个注入 503、ROB-02 的 6 个注入 503；没有因任务最终 PASS 而隐藏错误。

逐 retry attempt 实测 latency 与 backoff 保存于 retry-latency-observations.json。Dev 4 次退避合计 600 ms，4 次第二 attempt 执行延迟合计 370.3472 ms。自动 retry 本身不新增 model call；端到端额外 token/latency 的因果增量未知，不把恢复成功称为成功率提升 X%。Provider 内部 retries 未知，未换算货币费用。

## Governance / limitations / decision

使用既有 canonical request / Approval Binding / CAS / Idempotency / ERP material_request 契约，没有 Workflow 第二套 linkage。RFQ-01 等待审批无写入；RFQ-02 批准后恰好一个 ERP RFQ且 linkage 检查通过；各治理任务的预期拒绝、重复/重放探针分别保留在完整 artifacts。
先前评测证明确定性能力；本轮模型证据只覆盖既定 Smoke/Dev。模型质量观测不改变 Frozen Safety 或 grader，UnsupportedExecutionClaim 候选检测覆盖有限；没有证明所有可能失败/声明都被覆盖。

**Recommendation：ADOPT Phase 4 + Harness Reliability**。业务编排与 Context 保持 Phase 4，通用重试具备可观测恢复证据，写操作仍 fail closed。成本不足如表所示，不作因果收益承诺。

## Working tree

Part A 之后未再 commit/push/tag。所有既有文件按任务起始字节复核，Handler 未修改/未暂存/未提交；生产 fingerprint 全程不变。新报告与 artifacts 保持未提交，既有未跟踪缓存/日志/source.patch 原样保留。完整 git status 与 source/tag/remote 核验在同目录归档。

**HOLDOUT / 40×3 NOT RUN；Blind NOT RUN。完成后停止等待人工 Review。**
