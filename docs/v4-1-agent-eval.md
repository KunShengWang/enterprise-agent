# V4.1 Agent Eval

这一版把 Eval 从“只评估 RAG 检索”扩展为 Agent 级自动评测。

## 学习目标

Agent Eval 不是只判断回答里有没有关键词，而是评估一次 Agent 执行是否符合预期：

1. 回答是否包含关键事实。
2. 是否调用了应该调用的工具。
3. 是否在需要知识库时使用 RAG。
4. 回答是否 grounded，也就是是否基于检索或工具结果。
5. 是否出现禁用词或明显编造风险。
6. 是否能形成回归测试报告。

## 核心类

- `EvalCase`
  - 评测用例。
  - 包含问题、期望关键词、禁用词、期望工具、是否期望 RAG、是否期望工具调用、最低分。

- `DefaultAgentEvalRunner`
  - Agent 级评测运行器。
  - 会真正调用 `AgentExecutor.execute()`，再从回答、步骤和 trace 中计算指标。

- `AnswerJudge`
  - 裁判接口。
  - 当前实现 `LlmAsJudgeAnswerJudge` 优先使用 LLM-as-Judge，失败时回退到 `HeuristicAnswerJudge`。

- `EvalReport`
  - 一次评测报告。
  - 包含通过率、平均分、关键词命中率、工具调用成功率、RAG 使用准确率、groundedness 率。

- `EvalReportRecorder`
  - 保存最近的评测报告，方便回归对比。

## API

查看评测集：

```http
GET /api/agent/evals/cases
```

新增或更新评测用例：

```http
POST /api/agent/evals/cases
Content-Type: application/json

{
  "id": "agent-tool-ticket-status",
  "name": "工具调用查询工单",
  "question": "查询工单 T1001 的状态",
  "expectedKeywords": ["T1001"],
  "forbiddenKeywords": ["我猜", "可能是"],
  "expectedTools": ["ticket_status"],
  "expectRag": false,
  "expectToolCall": true,
  "minScore": 0.75
}
```

运行默认评测集：

```http
POST /api/agent/evals/regression
```

运行自定义评测：

```http
POST /api/agent/evals/run
Content-Type: application/json

{
  "cases": [
    {
      "id": "custom-rag",
      "question": "退款审批流程是什么？",
      "expectedKeywords": ["客服主管", "财务复核"],
      "expectRag": true,
      "minScore": 0.7
    }
  ]
}
```

查看评测报告：

```http
GET /api/agent/evals/reports?limit=10
GET /api/agent/evals/reports/{runId}
```

查看 Agent 运行事件：

```http
GET /api/agent/evals/events
```

## 面试解释

这一版 Eval 的价值是把 Agent 的质量变成可重复验证的指标：

- RAG 类问题看是否命中资料和关键词。
- Tool 类问题看是否调用正确工具。
- 普通聊天看是否避免误调用工具或编造业务数据。
- LLM-as-Judge 用来补充语义判断，规则评分用于稳定兜底。

当前评测集存储是内存版，适合学习和本地回归。后续可以扩展成 PostgreSQL 表，和 TraceRun 关联形成完整 AgentOps 报告。
