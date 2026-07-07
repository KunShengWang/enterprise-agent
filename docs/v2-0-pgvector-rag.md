# V2.0 PostgreSQL + pgvector RAG

## 这一版做什么

V2.0 开始把 RAG 从内存演示升级为真实工程链路：

```text
本地文档
  -> 加载
  -> 切分 chunk
  -> 调用真实 Embedding API
  -> 写入 PostgreSQL + pgvector
  -> 用户问题向量化
  -> TopK 相似度检索
  -> 拼入 Agent Prompt
```

## 必要准备

需要 PostgreSQL，并安装 pgvector 扩展。

项目默认配置：

```yaml
enterprise-agent:
  rag:
    mode: pgvector
    document-dir: ${RAG_DOCUMENT_DIR:data/rag-docs}
    datasource:
      url: ${RAG_POSTGRES_URL:jdbc:postgresql://localhost:5432/enterprise_agent}
      username: ${RAG_POSTGRES_USERNAME:postgres}
      password: ${RAG_POSTGRES_PASSWORD:postgres}
    embedding:
      base-url: ${EMBEDDING_BASE_URL:https://open.bigmodel.cn/api/paas/v4}
      path: ${EMBEDDING_PATH:/embeddings}
      api-key: ${EMBEDDING_API_KEY:}
      model: ${EMBEDDING_MODEL:embedding-3}
      dimension: ${EMBEDDING_DIMENSION:1024}
```

`EMBEDDING_API_KEY` 必须是真实 embedding 服务的 Key。当前实现使用 OpenAI-compatible `/embeddings` 协议，因此可以接 OpenAI 或兼容该协议的服务。

## 验证入口

加载并写入知识库：

```text
POST /api/agent/rag/ingest
```

单独验证检索：

```text
POST /api/agent/rag/search
Content-Type: application/json

{
  "query": "退款审批流程是什么？",
  "topK": 3
}
```

查看知识库统计：

```text
GET /api/agent/rag/stats
```

检索结果会返回：

```text
query
documents
enoughEvidence
requestedTopK
effectiveTopK
minSimilarity
durationMs
retrievalMode
```

每个 `RetrievedDocument` 的 `metadata` 会包含：

```text
rank
source
chunkIndex
distance
similarity
contentHash
retrievalMode
```

这些字段的作用是让 RAG 结果可解释：你能知道这次问题命中了哪个文件、哪个 chunk、相似度是多少、是否低于阈值被过滤。

Agent 主链路中的 RAG 分支也会调用同一个 `PgVectorRagService`。

## V2.1 做了什么优化

V2.1 没有改变 RAG 主流程，而是在 V2.0 的基础上补了可观测性：

```text
RagResult 记录 topK / minSimilarity / durationMs / retrievalMode
RetrievedDocument metadata 记录 rank / source / chunkIndex / distance / similarity
Agent trace 记录 RAG 命中的文件、chunk 和 score
Prompt 中显式带上 source 和 chunkIndex，方便模型按依据回答
新增 /api/agent/rag/stats，用于确认知识库实际入库规模
```

这一版学习重点是：真实项目里的 RAG 不能只返回几段文本，还要能解释“为什么这些文本被召回”。

## V2.2 做了什么优化

V2.2 解决文档重复导入和文档更新的问题。

之前的 chunk id 由 `source + chunkIndex + contentHash` 组成。这个设计可以保证 chunk 全局唯一，但也有一个问题：

```text
同一个文件内容发生变化后，新的 chunk id 会变化。
如果只做 INSERT / UPDATE，旧 chunk 不会自动删除。
最终知识库里会同时存在旧内容和新内容。
```

V2.2 的处理方式是 source 级重建：

```text
加载文档
  -> 切块
  -> 调 embedding
  -> embedding 全部成功
  -> 在一个数据库事务中删除这些 source 的旧 chunk
  -> 插入新 chunk
```

这里特意把“删除旧 chunk”放在 embedding 成功之后。原因是：如果 embedding API 半路失败，旧知识库仍然可用，不会因为一次失败导入把线上资料删掉。

`POST /api/agent/rag/ingest` 的返回会增加：

```text
deletedChunks
savedChunks
embeddingDurationMs
databaseDurationMs
sourceReports
```

可以单独删除某个 source：

```text
DELETE /api/agent/rag/source
Content-Type: application/json

{
  "source": "refund-policy.md"
}
```

这一版学习重点是：RAG 不只是“把文档写进向量库”，还要考虑文档更新、重复导入、失败恢复和数据库事务。

## V2.3 做了什么优化

V2.3 增加 Hybrid Retrieval，也就是：

```text
向量检索
  -> 找语义相似的 chunk

关键词检索
  -> 找精确命中的 chunk

融合排序
  -> vectorScore * vectorWeight + keywordScore * keywordWeight
```

为什么需要它：

```text
向量检索适合语义相似，但对工单编号、流程名、专有名词、短关键词不一定稳定。
关键词检索适合精确匹配，但不理解语义相似。
Hybrid Retrieval 把两者结合，提升召回稳定性。
```

默认配置：

```yaml
enterprise-agent:
  rag:
    hybrid:
      enabled: true
      vector-candidate-multiplier: 4
      keyword-candidate-limit: 20
      vector-weight: 0.7
      keyword-weight: 0.3
```

`/api/agent/rag/search` 的命中结果里会新增：

```text
vectorScore
keywordScore
finalScore
matchedByVector
matchedByKeyword
```

这些字段可以解释每个 chunk 是靠语义召回、关键词召回，还是两者同时命中。

这一版学习重点是：RAG 检索质量不是只调 TopK，还要组合不同召回策略，并保留可解释的融合分数。

## V2.4 做了什么优化

V2.4 增加 RAG Eval。它不评估最终 LLM 回答，而是专门评估检索阶段：

```text
问题是否召回了期望 source
召回内容是否包含期望关键词
每个用例是否通过
整体 sourceHitRate / keywordHitRate / averageScore
```

默认评估入口：

```text
POST /api/agent/rag/eval
```

自定义评估入口：

```text
POST /api/agent/rag/eval
Content-Type: application/json

{
  "cases": [
    {
      "id": "refund",
      "question": "退款审批流程是什么？",
      "topK": 3,
      "expectedSources": ["refund-policy.md"],
      "expectedContentKeywords": ["客服主管", "财务复核"]
    }
  ]
}
```

返回结果会包含：

```text
totalCases
passedCases
passRate
averageScore
sourceHitRate
keywordHitRate
results
```

逐用例结果会包含：

```text
expectedSources
foundSources
expectedContentKeywords
foundContentKeywords
retrievedDocuments
durationMs
```

这一版学习重点是：RAG 优化不能只靠感觉，必须有可重复的评估集。后续做 Rerank、切块优化、权重调参时，都可以用同一组 eval cases 对比效果。

## V2.5 做了什么优化

V2.5 增加轻量 Rerank。它发生在召回之后：

```text
向量检索 / 混合检索
  -> 得到候选 chunks
  -> Rerank 二次排序
  -> 返回最终 topK
```

当前不是接入外部 rerank 模型，而是先实现可解释的启发式 rerank：

```text
rerankScore =
  originalScore * baseScoreWeight
  + queryCoverage * queryCoverageWeight
  + sourceMatch * sourceMatchWeight
```

默认配置：

```yaml
enterprise-agent:
  rag:
    rerank:
      enabled: true
      base-score-weight: 0.75
      query-coverage-weight: 0.2
      source-match-weight: 0.05
```

`/api/agent/rag/search` 的 metadata 会新增：

```text
originalScore
rerankScore
queryCoverage
sourceMatch
reranked
rerankRank
```

这一版学习重点是：RAG 一般不是“召回即最终结果”，而是先扩大候选集，再对候选结果进行二次排序。当前实现保留了 `RagReranker` 接口，后续可以替换成真实 rerank 模型。

## V2.6 做了什么优化

V2.6 增加 RAG AgentOps 运行记录。每次调用 `RagService.retrieve()` 后，系统会记录一次 RAG 运行摘要：

```text
query
retrievalMode
enoughEvidence
requestedTopK / effectiveTopK
minSimilarity
retrievedDocuments
durationMs
hits
createdAt
```

每个 hit 会记录：

```text
rank
documentId
source
chunkIndex
score
metadata
```

查询最近运行记录：

```text
GET /api/agent/rag/runs?limit=20
```

查询近 N 次聚合统计：

```text
GET /api/agent/rag/runs/stats?limit=100
```

返回指标：

```text
totalRuns
hitRuns
hitRate
averageDurationMs
averageRetrievedDocuments
runsByMode
```

清空运行记录：

```text
DELETE /api/agent/rag/runs
```

这一版学习重点是：AgentOps 不是只记日志，而是把每次 RAG 的输入、召回结果、耗时和命中状态结构化记录下来，方便调参、排查和面试展示。

## V2.7 做了什么优化

V2.7 增加 pgvector 向量索引创建入口，用于性能优化。

默认配置：

```yaml
enterprise-agent:
  rag:
    index:
      type: hnsw
      ivfflat-lists: 100
```

手动创建索引：

```text
POST /api/agent/rag/index
```

支持两类索引：

```text
hnsw
ivfflat
```

当前没有把向量索引创建放进启动时自动执行，原因是：

```text
HNSW / IVFFlat 依赖 pgvector 版本
创建索引可能比较耗时
生产环境通常需要明确的 DBA / 迁移脚本控制
有些数据库用户没有 CREATE INDEX 权限
```

这一版学习重点是：RAG 性能优化不能只说“加索引”，还要知道索引何时创建、谁来创建、失败如何暴露、配置如何控制。

## V2.8 做了什么优化

V2.8 增加 RAG 运行报告持久化。它会把最近 N 次 RAG 检索记录导出为 Markdown 文件。

默认报告目录：

```yaml
enterprise-agent:
  rag:
    report-dir: ${RAG_REPORT_DIR:data/rag-reports}
```

生成报告：

```text
POST /api/agent/rag/runs/report?limit=100
```

报告内容包括：

```text
reportId
createdAt
totalRuns
hitRate
averageDurationMs
averageRetrievedDocuments
runsByMode
逐次 RAG run
每次 run 的 source / chunkIndex / score / documentId
```

这一版学习重点是：AgentOps 的输出应该能沉淀成报告，作为调参前后对比、问题复盘和简历项目证据，而不是只停留在接口响应里。

## 为什么不用 Spring AI pgvector starter

当前项目使用 Spring AI 2.0.0。你本地仓库里的 pgvector starter 主要是 1.1.x，直接混用容易出现版本冲突。

所以这一版先手写 pgvector 访问层，让关键步骤显式可控：

```text
schema 初始化
chunk 入库
vector 字面量转换
TopK 检索 SQL
相似度阈值过滤
来源 metadata 返回
```

后续如果 Spring AI 2.x 的 pgvector starter 稳定可用，再评估是否替换底层存储实现。
