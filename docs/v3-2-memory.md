# V3.2 Memory

这一版把 Memory 从“最近几条消息”升级成一个可解释的 Agent 子系统。

## 学习目标

Memory 不是简单把历史消息全塞进 prompt，而是分层管理上下文：

1. 短期记忆：最近 N 条消息，保留对话连续性。
2. 摘要记忆：把旧消息压缩成 summary，控制上下文长度。
3. 长期记忆：沉淀用户明确要求记住的事实、偏好、业务信息。
4. 用户画像：按 userId 记录姓名、部门、偏好、指令等稳定信息。
5. 历史召回：根据当前问题从摘要、消息、长期记忆、用户画像中召回相关内容。

## 主流程

```text
用户问题
  -> MemoryService.load(conversationId, userId, question)
  -> 加载短期窗口
  -> 加载摘要
  -> 加载长期记忆
  -> 加载用户画像
  -> 根据 question 召回相关历史
  -> Agent 执行 RAG / Tool / Chat
  -> MemoryService.append(user message)
  -> MemoryExtractor 抽取长期事实和用户画像
  -> ConversationSummarizer 触发摘要压缩
  -> MemoryService.append(assistant answer)
```

## 关键类

- `MemoryService`
  - Memory 的统一门面。
  - Agent 主流程只依赖这个接口，不关心底层是内存还是 PostgreSQL。

- `ConversationMemory`
  - 一次加载出来的上下文包。
  - 包含 `messages`、`summary`、`longTermMemories`、`userProfile`、`recalledMemories`。

- `InMemoryMemoryService`
  - 本地学习和快速调试使用。
  - 不依赖数据库，进程重启后数据丢失。

- `JdbcMemoryService`
  - PostgreSQL 持久化版本。
  - 使用普通表保存消息、摘要、长期记忆、用户画像。

- `RuleBasedConversationSummarizer`
  - 规则版摘要压缩。
  - 不依赖 LLM，后续可以替换成 LLM 总结。

- `RuleBasedMemoryExtractor`
  - 规则版事实抽取。
  - 识别“我叫...”“我的部门是...”“记住...”“以后...”等信息。

- `MemoryRecallScorer`
  - 规则版历史召回打分。
  - 根据当前问题和历史内容的关键词重合度排序。

- `MemoryController`
  - 提供 Memory 查询、召回、画像更新、清理接口。

## 配置

默认使用 PostgreSQL 持久化：

```yaml
enterprise-agent:
  memory:
    mode: ${MEMORY_MODE:jdbc}
    window-size: 12
    summary-trigger-messages: 8
    summary-max-chars: 1200
    recall-limit: 8
    long-term-limit: 20
    profile-item-limit: 30
    datasource:
      url: ${MEMORY_POSTGRES_URL:${RAG_POSTGRES_URL:jdbc:postgresql://localhost:5432/enterprise_agent}}
      username: ${MEMORY_POSTGRES_USERNAME:${RAG_POSTGRES_USERNAME:postgres}}
      password: ${MEMORY_POSTGRES_PASSWORD:${RAG_POSTGRES_PASSWORD:1234}}
```

如果只想本地快速跑：

```text
MEMORY_MODE=memory
```

## PostgreSQL 表

JDBC 版启动使用 Memory 时会自动建表：

- `agent_memory_message`
- `agent_memory_summary`
- `agent_long_term_memory`
- `agent_user_profile`

这几个表不需要 pgvector，因为 Memory 当前做的是结构化历史、摘要和画像，不是向量文档检索。

## API

查看会话记忆：

```http
GET /api/agent/memory/conversations/{conversationId}?userId=user-1&query=工单&limit=30
```

手动追加一条记忆消息：

```http
POST /api/agent/memory/conversations/{conversationId}/messages
Content-Type: application/json

{
  "userId": "user-1",
  "role": "user",
  "content": "我叫张三，我的部门是平台工程，记住我喜欢简洁回答"
}
```

按问题召回历史记忆：

```http
GET /api/agent/memory/conversations/{conversationId}/recall?userId=user-1&query=平台工程 简洁回答&limit=8
```

查看用户画像：

```http
GET /api/agent/memory/users/user-1/profile
```

手动更新用户画像：

```http
POST /api/agent/memory/users/user-1/profile
Content-Type: application/json

{
  "key": "department",
  "value": "平台工程",
  "source": "manual"
}
```

清理会话记忆：

```http
DELETE /api/agent/memory/conversations/{conversationId}
```

清理用户画像和用户长期记忆：

```http
DELETE /api/agent/memory/users/user-1
```

## 面试解释

这版 Memory 的重点不是“存历史消息”，而是解决上下文工程里的三个问题：

1. 上下文长度有限，所以旧消息要压缩成 summary。
2. Agent 要跨轮次记住稳定事实，所以需要长期记忆和用户画像。
3. 不是所有历史都该进 prompt，所以要按当前问题做召回。

工程取舍：

- 规则摘要和规则抽取可控、便宜、容易调试。
- JDBC 持久化保证服务重启后 Memory 不丢。
- MemoryService 屏蔽存储差异，后续可以把摘要器替换成 LLM，把召回替换成 embedding 检索。
