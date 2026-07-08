# V4.7 AgentOps Metrics Dashboard

这一版把分散在 Trace、RAG、Tool、Eval 里的运行数据聚合成一个 AgentOps 看板。

它解决的问题不是压测，而是回答面试里更常见的问题：

```text
这个 Agent 跑得准不准？
RAG 有没有命中？
工具调用成功率怎么样？
回答有没有幻觉风险？
一次 Agent Run 能不能回放？
模型调用消耗了多少 token 和成本？
出了问题能不能定位到具体阶段？
```

## 新增接口

### 1. AgentOps 总览

```http
GET /api/agent/ops/summary?limit=100
```

返回内容包含：

| 字段 | 含义 |
| --- | --- |
| `traceStats` | 最近 N 次 Agent Run 的成功、失败、阻断、平均耗时、token、成本 |
| `ragStats` | 最近 N 次 RAG 调用的命中率、平均耗时、平均召回文档数、检索模式分布 |
| `ragCacheStats` | RAG 缓存是否开启、缓存命中率、缓存容量、TTL |
| `toolStats` | 工具调用总数、成功数、失败数、成功率、按工具分组统计 |
| `latestEval` | 最近一次 Eval 报告的通过率、RAG 使用准确率、工具调用成功率、groundedness、幻觉风险 |
| `metricMeanings` | 每个关键指标的业务解释 |
| `endpoints` | 进一步排查时应该访问的接口 |
| `risks` | 根据当前指标自动生成的风险提示 |

### 2. AgentOps 证据

```http
GET /api/agent/ops/evidence?limit=20
```

返回最近的原始证据：

| 字段 | 含义 |
| --- | --- |
| `recentTraces` | 最近 Agent Run，包含 spans、events、replayEvents、token、成本 |
| `recentRagRuns` | 最近 RAG 检索记录，包含命中的 source、chunk、score |
| `recentToolCalls` | 最近工具调用记录，包含工具名、参数、成功状态、耗时 |
| `recentEvalReports` | 最近 Eval 报告 |

## 关键指标

### Trace 指标

`TraceRunStats` 用来回答“Agent 执行链路是否稳定”：

| 指标 | 说明 |
| --- | --- |
| `totalRuns` | 最近 N 次 Agent Run 数量 |
| `completedRuns` | 成功完成数量 |
| `failedRuns` | 执行失败数量 |
| `blockedRuns` | 被 Guardrail/HITL 阻断数量 |
| `averageDurationMs` | 平均耗时 |
| `estimatedPromptTokens` | 累计 prompt tokens |
| `estimatedCompletionTokens` | 累计 completion tokens |
| `estimatedCost` | 成本估算 |

token 统计的优先级：

```text
模型返回 usage
  -> 使用真实 prompt/completion tokens
模型未返回 usage
  -> 根据 prompt 和 answer 长度做估算
```

### RAG 指标

RAG 指标分两类。

第一类是运行指标，来自 `/api/agent/rag/runs/stats`：

| 指标 | 说明 |
| --- | --- |
| `hitRate` | RAG 实际运行时 enoughEvidence=true 的比例 |
| `averageRetrievedDocuments` | 平均召回文档数 |
| `averageDurationMs` | 平均检索耗时 |
| `runsByMode` | vector / hybrid 等检索模式分布 |

第二类是评测指标，来自 `/api/agent/rag/eval`：

| 指标 | 说明 |
| --- | --- |
| `sourceHitRate` | TopK 结果里是否出现期望 source |
| `keywordHitRate` | 召回内容是否包含期望关键词 |
| `recallAtK` | TopK 是否召回任一期望 source，本项目当前等价于 sourceHitRate |
| `meanReciprocalRank` | 期望 source 排名越靠前分越高，命中第 1 名为 1.0，第 2 名为 0.5 |
| `averageScore` | source 命中和关键词命中的综合得分 |

### Tool 指标

`ToolRunStats` 用来回答“工具调用是否可靠”：

| 指标 | 说明 |
| --- | --- |
| `totalCalls` | 工具调用次数 |
| `successCalls` | 成功次数 |
| `failedCalls` | 失败次数 |
| `successRate` | 工具调用成功率 |
| `callsByTool` | 按工具名称统计调用次数 |

### Eval 指标

`EvalReport` 用来回答“Agent 的回答质量是否可回归”：

| 指标 | 说明 |
| --- | --- |
| `passRate` | 整体通过率 |
| `keywordHitRate` | 回答是否包含预期关键词 |
| `toolCallSuccessRate` | 需要工具时是否正确完成工具调用 |
| `ragUsageAccuracy` | 需要 RAG 时是否真的走了 RAG |
| `groundednessRate` | 回答是否基于 RAG 或工具证据 |
| `hallucinationRiskRate` | 未 grounded 的比例，可作为幻觉风险代理指标 |
| `adversarialPassRate` | 对抗样本通过率，用于观察 Guardrail 稳定性 |

## 面试表达

可以这样讲：

```text
我没有只做一个能问答的 Agent，而是补了一套 AgentOps。
每次 Agent 执行会产生 TraceRun，里面有 Memory、RAG、Tool、LLM、Guardrail、Eval 等 span。
RAG 和 Tool 也有自己的运行记录，可以统计 RAG 命中率、召回质量、工具成功率。
Eval 会跑固定评测集，评估关键词命中、工具调用、RAG 使用准确率、groundedness 和对抗样本。
最后 /api/agent/ops/summary 会把这些指标聚合成一个统一看板，方便定位问题和做版本回归。
```

## 当前边界

- 目前 AgentOps 数据仍主要在内存 recorder 中，适合本地演示和学习，生产环境应落 PostgreSQL 或日志平台。
- RAG 压测不是当前重点，当前重点是命中率、召回率、groundedness、工具成功率和 Trace 回放。
- token 成本已经优先接入模型 usage，但不同模型是否返回 usage 取决于 Spring AI 与供应商响应。
- `hallucinationRiskRate` 是工程代理指标，不等价于绝对幻觉率，真实项目还需要人工抽检和 LLM-as-Judge 复核。
