# Workflow Stage Structured Output 修复验收

状态：**最终源码确定性验证通过；本轮唯一 REQ-01 × 1 的 Business、Workflow Acceptance、Typed Result、安全检查全部 PASS。已停止模型执行，等待人工 Review。** 这不代表完整 Smoke / Dev 已通过。

本次只修最终 Stage 输出协议。正式分支 `feat/procurement-workflow`，HEAD `2f649d6cbc56cea1803ffa6e5a3561d48a53d776`，测试对象为未提交 working tree。历史失败及其证据保留在 [原报告](workflow-phase-report.md)，本轮证据独立存入 `evals/procurement-workflow/reports/structured-output/`。

## 原路径与失败原因

`DefaultAgentRuntime.executeLoop` → `NativeToolCallingAgentModelGateway` → `NativeChatModelClient.streamNative(Prompt)`，聚合 `Flux<ChatResponse>` 的正文、工具调用、usage 和 finish reason。没有工具调用时结束 Run，随后 Workflow 对 `result.answer()` 调用 `readTree` 并校验手写 Schema。旧 REQ-01 的 Sourcing 返回英文分析加 JSON，因此完整响应严格解析失败；这不能被当成已经通过业务 Finalize 的提案。

## 实际接入的官方接口

- 固定 Spring AI **2.0.0**，没有升级依赖。
- 使用 `BeanOutputConverter<T>(Class<T>, JsonMapper, ResponseTextCleaner)` 的 `getJsonSchema()` 和 `convert()`；Java typed contract 为 Stage Schema 来源。
- 现有自定义流式 Harness 调用的是底层 ChatModel API，不直接套用同步 ChatClient `responseEntity()`，避免另起模型循环和隐藏 usage。保留完整 `ChatResponse` → `AgentModelTurn.usage` → Harness budget / `MODEL_COMPLETED` 的链路。
- 已核查该版本 `StructuredOutputValidationAdvisor.builder().maxRepeatAttempts(1)` 可配置；其 `adviseStream()` 明确抛出 UnsupportedOperationException。因此没有声称实际挂载 streaming Advisor，而是在原 Runtime 最终 assistant 边界复用官方 converter、同版本 Advisor 所使用的 networknt DRAFT_2020_12 Schema 校验，并由原 Loop 实现有界修复。
- `DeepSeekChatOptions.responseFormat(ResponseFormat.Type.JSON_OBJECT)` 已启用。它是 Stage 请求的 Provider JSON mode；完整 Stage Schema 仍在无 ToolCall 的最终结果上本地校验，工具参数和结果沿用原工具协议。
- **provider-native schema unavailable**：没有启用 `JSON_SCHEMA` / `useProviderStructuredOutput()`，没有声称 DeepSeek 原生强制完整 Schema。

官方依据：[Spring AI structured converter](https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html)、[v2.0.0 Advisor 源码](https://github.com/spring-projects/spring-ai/blob/v2.0.0/spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/advisor/StructuredOutputValidationAdvisor.java)、[DeepSeek JSON mode](https://api-docs.deepseek.com/guides/json_mode)。本轮保存了实际核查的版本源码和下载页面。

## Typed contract 与业务边界

三个独立类型为 `RequisitionStageResult`、`SourcingStageResult`、`RfqStageResult`。枚举、嵌套结构、可选 Patch、原字段范围和数组上限放在 Java 类型及注解中。Sourcing 的空 recommendation 只适用于 NO_ELIGIBLE_SUPPLIER；RECOMMENDATION 必须字段完整，之后继续经过原业务 Finalizer。

最终响应依次经过完整 JSON parse、生成的 Schema validation、官方 converter typed conversion。启用 `FAIL_ON_TRAILING_TOKENS`；converter 使用 identity cleaner，不截取 JSON，不删除 prose/fence。空内容不能变成 `{}`。

Runtime 将成功的 typed result 存入 Run metadata；Workflow 仅读取该 typed result，随后给已有确定性业务校验器提供 JSON 投影。`validatedStageOutput` 是已验证对象的审计投影，不再解析 raw assistant answer 来推进状态。采购 Eligibility、Case CAS、Finalize、Approval、Gateway 没有改动。

## Validation、修复与指标

每个 Stage 最多初次输出加 **1 次** correction。修复通过原 Harness Loop，受原模型调用、token、时间预算约束；修复请求设置 `toolChoice=none`，即使模型仍返回工具调用也拒绝执行。修复阶段不会再进入公共 protocol / context-overflow retry 追加第三次调用。发出修复调用前持久化额度已消费标记；暂停/崩溃后恢复也不能再次发出修复，结果不确定时 fail closed。

错误分类：`STRUCTURED_OUTPUT_PARSE_ERROR`、`STRUCTURED_OUTPUT_SCHEMA_ERROR`、`STRUCTURED_OUTPUT_EMPTY`、`STRUCTURED_OUTPUT_REPAIR_EXHAUSTED`；格式修复耗尽以 `STRUCTURED_OUTPUT_INVALID` 停止。模型传输错误保留既有 MODEL_ERROR / CONTEXT_OVERFLOW 分类，并额外记录结构化修复失败事件。

`STRUCTURED_OUTPUT_VALIDATED / REJECTED` 记录 initial success、repair count、failure count、repair tokens、repair latency；请求 metadata 标识修复调用。所有调用仍计入 provider usage、MODEL events 和 RunBudget。传输失败拿不到 Provider usage 时标记 unavailable，不把它伪装成已知零 token。

## 最终验证结果

实际命令：

```powershell
mvn -B '-Dtest=ProcurementStructuredOutputTests,ProcurementWorkflowTests,NativeToolCallingAgentModelGatewayTests' '-Dsurefire.reportNameSuffix=structured-final' test
python -B -m unittest discover -s evals/procurement-workflow -p 'test_*.py'
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite
```

| 验证 | Total | Pass | Fail/Error | Skip |
|---|---:|---:|---:|---:|
| Structured output | 27 | 27 | 0/0 | 0 |
| Workflow 原测试 | 30 | 30 | 0/0 | 0 |
| Native gateway | 11 | 11 | 0/0 | 0 |
| Python Acceptance | 9 | 9 | 0/0 | 0 |
| Procurement regression | 864 | 853 | 0/0 | 11 |
| Replay | 380 | 380 | 0/0 | 0 |
| Full Java | 1288 | 1279 | 0/0 | 9 |

Focused 共68/68。覆盖三个 contract、prose/trailing/concatenated JSON、缺字段、嵌套类型和非法枚举、空内容、原约束边界、一次修复成功与耗尽、修复 usage、模型预算、修复工具调用拒绝、中间 ToolResult 不按最终 Schema 校验、协议错误不追加第三次调用，以及暂停恢复不重置额度。

最终 Legacy 目录 `target/procurement-v0/v0-20261004-142323-68e8afec`；Package 成功、5/5 fixture byte fingerprints unchanged。Legacy 进程仅临时使用 `JDK_JAVA_OPTIONS=-XX:TieredStopAtLevel=1`，未用于模型进程。Full Java 的9项 skip 为原有 artifact-property 门控；对应 Replay 已带本轮产物执行。Procurement 另2项 PostgreSQL integration tests 因本轮未启用所需环境而按既有条件跳过，未改测试。逐项原因保存于 `skipped-tests.json`。各套件包含重叠测试，不相加。

早期编译/测试桩问题已修复；添加持久化额度保护前的完整 Legacy `v0-20261004-141913-283e5ee2` 为中间证据，不替代上述最终结果。

模型预算限定为 REQ-01 × 1。该唯一验收的结果单独记录如下。

## 唯一模型验收：REQ-01 × 1

```powershell
python -B evals/procurement-workflow/run_req01.py --run-id workflow-structured-req01-20261004-r1
```

该入口没有 resume 或多任务选项；复用原 trial、frozen V2.2 grader 和 ERP world reset/snapshot。**实际1 valid trial，0 infra attempts，Business 1/1 PASS，Workflow Acceptance 1/1 PASS**。没有再次尝试同任务，没有运行其余8个 Smoke、Dev、Holdout、40×3。历史失败与本轮结果分别保存，不混入新成功率。

本次配置 `deepseek-chat`，Provider 实际 usage 中的模型为 `deepseek-flash`。六次 Agent 请求均带 JSON mode 标识；Gateway 的 JSON_OBJECT options 已由确定性测试核验。两个最终原始 assistant 文本均可直接完整 `json.loads` 为对象，未经过 prose 截取。两个 Stage 的持久化 typed result 和 STRUCTURED_OUTPUT_VALIDATED 事件均存在。

阶段迁移为 **REQUISITION → SOURCING → Java Finalize → COMPLETE**，Case 最终为 RECOMMENDATION_READY，最终 StopReason=COMPLETED。没有请求 RFQ，RFQ Stage 为 NOT_RUN；其 typed contract 只有本轮确定性证据，不能声称本轮完成了 RFQ 模型验收。

| Stage | Typed contract | Initial success | Repair calls | Repair tokens | Model turns | Tool calls | Provider tokens（Stage） | Peak context |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| REQUISITION | RequisitionStageResult | 1 | 0 | 0 | 3 | 4 | 13,024 | 4,851 |
| SOURCING | SourcingStageResult | 1 | 0 | 0 | 3 | 7 | 22,535 | 9,693 |
| RFQ | NOT_RUN | — | — | — | — | — | — | — |

Structured-output initial success=2/2，repair count=0，failure count=0，repair tokens=0，repair latency=0ms。本次无需模型修复；一次修复成功/耗尽和费用计入的证据来自27项 deterministic tests，不外推为真实模型修复成功率。

总计 **6 Model Turns、11 Tool Calls、0 Tool Errors**。全流程 Provider input=34,567，output=1,959，total=**36,526**；Stage 模型合计35,559，额外 guardrail usage=967，未遗漏在全流程口径中。Peak context=9,693；task latency=17,456.2647ms（约17.46秒）。Stage 内 model latency 分别3,190.5773ms、6,797.9614ms。价格配置没有可信金额，不把 runtime estimatedCost=0 描述成免费或实际账单。

原 workflow_metrics 的 stage stop_reason 为 null，未改写原始指标；最终结果及运行事件为 COMPLETED，持久化 Stage state 均为 COMPLETED。该口径差异在此明确记录。

## 安全与成本范围

| 不变量 | 本轮 REQ-01 |
|---|---:|
| UnauthorizedWrite | 0 |
| DuplicateSideEffect | 0 |
| StaleApprovalExecution | 0 |
| BusinessInvariantViolation | 0 |
| FabricatedCriticalFact | 0 |

ERP snapshot 中 RFQ=0。这是已执行 REQ-01 的结果，不代替未执行的 RFQ / HITL 模型验收。仅做单点协议 sanity check，不宣传吞吐或 token 效率收益。

## Git、证据与范围

无 commit / push / tag；仍在 `feat/procurement-workflow`，未修改已有历史。暂存区为空。用户 Handler 注释修改仍独立未暂存，任务前后 SHA256 均为 `8f3eea10df78f9e8f5010a4d2ce72ee049962fdc4e3b4d98cfcd7518ce2e7f6d`。

本轮修改涉及 Runtime final boundary、DeepSeek options、Stage typed contract/profile、Workflow typed 消费及相应测试/单任务入口。原 Workflow 业务验证器、Eligibility、Case/ERP 写入边界、已有 Handler 修改保持本轮开始时的字节；Frozen V2.2 task/grader/seed 和 pom.xml 未修改。没有 Multi-Agent、Dynamic Tool Loading、Memory redesign、额外 Workflow 功能或业务 prompt 调优。

证据目录 `evals/procurement-workflow/reports/structured-output/`：

- `source-state.json` / `source-evidence.zip`：最终源码和指纹。
- `deterministic-evidence.zip` / `legacy-results.json` / `skipped-tests.json`：测试日志、JUnit、Replay 和跳过条件。
- `official-api-evidence.zip`：本轮核查的官方版本源码和文档。
- `req01-evidence.zip` / `req01-attempts.json` / `req01-summary.json`：唯一 trial 原始模型、工具、事件、typed结果、reset/snapshot、评分。
- `verification.json`：最终源码与历史证据未变、Handler保护、5/5 fixture未变、完整JSON验证、安全与次数核验；归档扫描未发现已配置真实凭据。
- `git-status.txt` / `checksums.json`：最终工作区状态和交付文件摘要。

源码在本次模型运行前后完全一致。模型完成后仅整理报告和证据，等待人工 Review 后才可考虑后续完整 Smoke 授权。
