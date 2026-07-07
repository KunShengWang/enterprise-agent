# RAG 检索流程

RAG 是 Retrieval-Augmented Generation 的缩写，核心思想是先从外部知识库中检索相关资料，再把资料作为上下文交给大模型生成回答。它不是让模型凭记忆回答，而是让模型优先依据企业内部文档、流程规范、工单记录和业务知识生成结果。企业知识库问答必须优先依据检索到的资料回答。如果资料不足，Agent 应该明确说明资料不足，而不是编造结论。

一条完整的 RAG 主流程包括文档加载、文本切分、Embedding 向量化、向量库写入、问题向量化、TopK 检索、证据拼接和模型回答。文档加载阶段负责读取 markdown、txt 或其他业务文档。文本切分阶段把长文档拆成多个 chunk，每个 chunk 需要有全局唯一 ID、来源 source、chunkIndex 和正文 content。Embedding 阶段把文本转换成向量，方便后续根据语义相似度检索。

向量库写入阶段需要考虑重复导入和文档更新。不能只做简单 insert，因为同一个文件内容变化后，旧 chunk 可能仍然留在数据库里。更稳妥的方式是以 source 为单位重建：先完成新文档的切块和 embedding，确认全部成功后，再在事务中删除同 source 的旧 chunk，然后写入新 chunk。这样即使 embedding 接口失败，旧知识库仍然可用。

检索阶段通常先把用户问题向量化，然后在 pgvector 中按余弦距离召回 TopK 个 chunk。仅靠向量检索有时不够稳定，特别是遇到工单编号、流程名称、专有名词或金额阈值时，关键词命中也很重要。因此可以使用 Hybrid Retrieval，把向量检索和关键词检索结合起来，再对候选结果进行融合排序。融合结果中应该保留 vectorScore、keywordScore、finalScore、matchedByVector 和 matchedByKeyword，方便排查排序原因。

Rerank 是召回之后的二次排序。第一阶段召回的目标是尽量找全，第二阶段 rerank 的目标是把更可信、更贴近问题的 chunk 排到前面。当前系统使用轻量启发式 rerank，根据原始分数、查询词覆盖率和来源匹配程度计算 rerankScore。后续如果接入专业 rerank 模型，也应该保留相同的接口和可观测字段，避免系统变成黑盒。

一次可观测的 RAG 调用至少要记录 query、召回 chunk、相似度、来源文档、耗时和最终回答。更进一步，还应该记录 retrievalMode、effectiveTopK、minSimilarity、hitRate、averageDurationMs 和 averageRetrievedDocuments。这样在调参时可以比较不同 chunkSize、TopK、向量模型、混合检索权重和 rerank 策略的效果。

Agent 在拼 Prompt 时，不能把检索资料简单拼成一大段无结构文本。更好的方式是保留每个 chunk 的 source、chunkIndex、score 和 content，让模型知道资料来自哪里。回答知识库问题时，应该优先引用检索资料。如果资料里没有答案，应明确说明资料不足。如果问题涉及业务结论、审批状态、工单状态或系统数据，不能只靠模型常识回答。

RAG Eval 用来评估检索效果，不等同于最终回答评估。一个基础评估集可以包含 question、expectedSources 和 expectedContentKeywords。评估时检查召回结果是否命中期望文档，内容是否包含关键证据，并统计 sourceHitRate、keywordHitRate、averageScore 和 passRate。没有评估集的 RAG 优化很容易变成凭感觉调参数。

RAG 的工程价值不只是回答问题，还包括可控、可解释和可复盘。入库要能重复执行，检索要能解释分数，回答要能引用来源，运行记录要能导出报告。这样当用户质疑回答依据时，系统可以回放当时检索到了哪些 chunk、分数是多少、为什么选中这些资料。
