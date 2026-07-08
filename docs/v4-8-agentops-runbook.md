# V4.8 AgentOps Runbook

这份手册用于本地演示 AgentOps 指标。目标是先拿到可解释的证据，而不是做压测。

## 1. 启动前准备

真实模型：

```powershell
$env:DEEPSEEK_API_KEY="你的 DeepSeek API Key"
$env:DEEPSEEK_CHAT_MODEL="deepseek-chat"
```

RAG：

```powershell
$env:EMBEDDING_API_KEY="你的智谱 Embedding API Key"
$env:RAG_POSTGRES_URL="jdbc:postgresql://localhost:5432/enterprise_agent"
$env:RAG_POSTGRES_USERNAME="postgres"
$env:RAG_POSTGRES_PASSWORD="1234"
```

启动：

```powershell
mvn spring-boot:run
```

健康检查：

```powershell
curl.exe http://localhost:8080/api/agent/health
```

## 2. 产生 RAG 数据

导入文档：

```powershell
curl.exe -X POST http://localhost:8080/api/agent/rag/ingest
```

执行一次检索：

```powershell
curl.exe -X POST http://localhost:8080/api/agent/rag/search `
  -H "Content-Type: application/json" `
  -d "{\"query\":\"退款审批流程是什么？\",\"topK\":3}"
```

查看 RAG 运行统计：

```powershell
curl.exe "http://localhost:8080/api/agent/rag/runs/stats?limit=100"
```

关注：

```text
hitRate
averageRetrievedDocuments
averageDurationMs
runsByMode
```

## 3. 跑 RAG Eval

运行默认 RAG 评测集：

```powershell
curl.exe -X POST http://localhost:8080/api/agent/rag/eval `
  -H "Content-Type: application/json" `
  -d "{}"
```

自定义评测：

```powershell
curl.exe -X POST http://localhost:8080/api/agent/rag/eval `
  -H "Content-Type: application/json" `
  -d "{\"cases\":[{\"id\":\"refund-rag\",\"question\":\"退款审批流程是什么？\",\"topK\":3,\"expectedSources\":[\"refund-policy.md\"],\"expectedContentKeywords\":[\"客服主管\",\"财务复核\"]}]}"
```

重点看：

| 指标 | 怎么解释 |
| --- | --- |
| `sourceHitRate` | 是否召回期望资料 |
| `recallAtK` | TopK 是否包含期望资料 |
| `meanReciprocalRank` | 期望资料排得越靠前越好 |
| `keywordHitRate` | 召回内容是否覆盖关键事实 |
| `averageScore` | 检索整体质量 |

## 4. 产生 Agent Trace

调用 Agent：

```powershell
curl.exe -X POST http://localhost:8080/api/agent/chat `
  -H "Content-Type: application/json" `
  -d "{\"conversationId\":\"ops-demo-1\",\"userId\":\"user-1\",\"question\":\"退款审批流程是什么？\"}"
```

查看最近 Trace：

```powershell
curl.exe "http://localhost:8080/api/agent/traces?limit=5"
```

查看 Trace 统计：

```powershell
curl.exe "http://localhost:8080/api/agent/traces/stats?limit=100"
```

拿到 `traceId` 后回放：

```powershell
curl.exe "http://localhost:8080/api/agent/traces/{traceId}/replay"
```

Trace 回放重点看：

```text
memory.load
guardrail.input
skill.select
intent.route
query.rewrite
rag.retrieve / tool.execute
prompt.assemble
llm.call
guardrail.output
conversation.save
eval.record
```

## 5. 产生 Tool 数据

调用工具类问题：

```powershell
curl.exe -X POST http://localhost:8080/api/agent/chat `
  -H "Content-Type: application/json" `
  -d "{\"conversationId\":\"ops-demo-2\",\"userId\":\"user-1\",\"question\":\"帮我查询工单 T1001 的状态\"}"
```

查看工具运行统计：

```powershell
curl.exe http://localhost:8080/api/agent/tools/runs/stats
```

重点看：

```text
totalCalls
successCalls
failedCalls
successRate
callsByTool
```

## 6. 跑 Agent Eval

运行回归评测：

```powershell
curl.exe -X POST http://localhost:8080/api/agent/evals/regression
```

运行对抗评测：

```powershell
curl.exe -X POST http://localhost:8080/api/agent/evals/adversarial
```

查看最近报告：

```powershell
curl.exe "http://localhost:8080/api/agent/evals/reports?limit=10"
```

重点看：

```text
passRate
keywordHitRate
toolCallSuccessRate
ragUsageAccuracy
groundednessRate
metrics.hallucinationRiskRate
metrics.adversarialPassRate
```

## 7. 查看统一 AgentOps 看板

总览：

```powershell
curl.exe "http://localhost:8080/api/agent/ops/summary?limit=100"
```

证据：

```powershell
curl.exe "http://localhost:8080/api/agent/ops/evidence?limit=20"
```

建议演示顺序：

```text
先展示 /api/agent/ops/summary
  -> 说明整体成功率、RAG 命中率、Tool 成功率、Eval 质量、Token 成本
再展示 /api/agent/ops/evidence
  -> 说明每个指标背后都有原始 Trace、RAG Run、Tool Run、Eval Report
最后展示 /api/agent/traces/{traceId}/replay
  -> 证明一次 Agent 执行可以按步骤复盘
```

## 8. 如何判断问题

| 现象 | 优先排查 |
| --- | --- |
| `ragStats.hitRate` 低 | 文档是否入库、query rewrite、TopK、minSimilarity、切块质量 |
| `meanReciprocalRank` 低 | 相关资料召回靠后，检查 rerank 权重和关键词融合 |
| `toolStats.successRate` 低 | 参数 Schema、工具异常、重试、降级、权限 |
| `groundednessRate` 低 | Prompt 是否要求基于证据、RAG/Tool 结果是否进入上下文 |
| `hallucinationRiskRate` 高 | 强化证据过滤、禁止编造、增加 LLM-as-Judge 或人工抽检 |
| `failedRuns` 高 | 看 Trace replay 的失败 span 和 failureReason |
| token 成本异常 | 看 prompt context 是否过长、Memory/RAG 是否需要裁剪 |

## 9. 面试时的简洁说法

```text
我把 AgentOps 拆成三层：
第一层是 Trace，记录一次 Agent Run 的完整链路和 replay。
第二层是指标，统计 RAG 命中率、Tool 成功率、Eval 通过率、groundedness、token 成本。
第三层是证据，把最近 Trace、RAG Run、Tool Run、Eval Report 聚合到 /api/agent/ops/evidence。
这样不是只看最终回答，而是能定位一次回答为什么对、为什么错，以及优化前后指标有没有变好。
```
