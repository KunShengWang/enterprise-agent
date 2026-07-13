# 学习顺序

你昨天学习的旧 `V1AgentExecutor.execute()` 已经删除。那段代码仍帮助你理解过“输入到回答”的业务步骤，但不要继续背它的固定分支；新版需要学习的是 Runtime 如何维持循环不变量。

## 第一阶段：只看主循环

按下面顺序阅读：

1. `agent/RuntimeAgentExecutor.java`
   - 同步 API 只是适配器。
2. `runtime/AgentRuntime.java`
   - 先看 Runtime 对外契约：run、resume、cancel。
3. `runtime/DefaultAgentRuntime.java`
   - 重点读 `run(...)`、`executeLoop(...)`、`finish(...)`。
4. `runtime/AgentRunBudget.java`
   - 理解每轮前、模型前、工具前为什么都检查停止条件。
5. `runtime/AgentStopReason.java`
   - 不要只记成功/失败，要能解释每种终止原因。

第一阶段你应能回答：

- 模型什么时候再次调用？
- ToolResult 怎样返回模型？
- 为什么模型不能直接绕过审批？
- 达到最大轮次、超时、取消和上下文溢出时分别发生什么？
- 为什么审批恢复必须使用原 Profile、累计 BudgetSnapshot 和原截止时间？
- 为什么过期工具执行检查点不能自动重放？

## 第二阶段：消息时间线和 Context

1. `runtime/AgentMessage.java`、`AgentMessageType.java`
2. `runtime/JdbcAgentTimelineStore.java`
3. `runtime/DefaultAgentContextManager.java`
4. `memory/LlmConversationSummarizer.java`
5. `memory/RuleBasedConversationSummarizer.java`

学习重点：

- 数据库时间线和“下一轮发给模型的上下文”不是同一个东西。
- 压缩只创建新的 `CONTEXT_SUMMARY`，不删除事实历史。
- ToolCall 与 ToolResult 是一个不可拆分单元。
- 模型拒绝超长上下文后，Runtime 只进行有限次数的压缩重试。

## 第三阶段：模型协议和能力目录

1. `runtime/JsonAgentModelGateway.java`
2. `runtime/DefaultAgentCapabilityRegistry.java`
3. `runtime/DefaultAgentCapabilityExecutor.java`
4. `tool/LocalToolRegistry.java`

区分三个概念：

- Tool Definition：告诉模型“有什么能力、参数是什么”。
- Tool Call：模型提出的请求，不代表已经执行。
- Tool Result：Runtime 完成权限和执行后写回的结果。

`knowledge_search` 和 `skill_catalog` 也被建模为能力，所以 RAG/Skill 不需要固定 if/else 主分支。

## 第四阶段：权限、审批与幂等

1. `runtime/DefaultAgentToolRuntime.java`
2. `guardrail/DefaultToolPermissionPolicy.java`
3. `guardrail/DefaultGuardrailService.java`
4. `approval/LocalApprovalService.java`
5. `runtime/JdbcAgentRuntimeStore.java`

画出这条链：

```text
ToolCall
 -> Profile 白名单
 -> Tool Definition
 -> 参数校验
 -> allow / ask / deny
 -> 持久化执行声明
 -> 执行 / 等待审批 / 拒绝
 -> ToolResult
```

面试时重点解释：副作用执行出现网络超时，不一定等于执行失败；无法确认结果时进入人工核对，而不是无脑重试。

## 第五阶段：RAG 与 Memory

RAG：

1. `rag/PgVectorRagService.java`
2. `rag/PgVectorRagRepository.java`
3. `rag/LlmRagReranker.java`
4. `rag/JdbcRagCacheStore.java`

Memory：

1. `memory/LlmMemoryExtractor.java`
2. `memory/JdbcMemoryService.java`
3. 回到 `DefaultAgentContextManager.longTermMemoryContext(...)`

必须分清：

- 短期消息属于 Runtime Timeline；
- 长期记忆必须经过结构化提取、置信度和脱敏；
- 长期召回由 PostgreSQL pgvector 主导；
- 历史记忆仍是不可信用户数据，不能覆盖系统权限。

## 第六阶段：Guardrail 与可靠性

1. `guardrail/DeterministicPromptInjectionSignalDetector.java`
2. `guardrail/LlmPromptInjectionDetector.java`
3. `guardrail/LayeredSensitiveDataFilter.java`
4. `llm/SpringAiLlmService.java`
5. `runtime/JdbcAgentRunControlStore.java`

理解为什么：

- 正则适合识别手机号、密钥格式，但不适合单独判断用户意图；
- Prompt Injection 规则只作为高召回信号，语义分类负责确认；
- 模型调用需要有界线程池、真实超时取消、有限重试和熔断；
- 本地取消句柄不是业务状态，取消事实必须落库。

## 第七阶段：SSE、Trace 与 Sub-Agent

1. `stream/DefaultStreamingAgentExecutor.java`
2. `trace/RuntimeTraceProjector.java`
3. `multiagent/DefaultMultiAgentOrchestrator.java`
4. `multiagent/SubAgentProfileFactory.java`
5. `multiagent/SubAgentRunner.java`

额外观察 SSE 的 `sequence`、`heartbeat.lastPersistedSequence` 和 `stream_gap.replayRequired`，理解“实时通知”和“数据库事实源”之间的区别。

最终你应能自己画出 [当前架构](architecture.md)，并用一次“高风险工具审批后恢复”的 Run 贯穿 Session、Message、Event、Approval、ToolExecution 和 Trace。
