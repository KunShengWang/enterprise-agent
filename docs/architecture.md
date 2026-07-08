# Enterprise Agent 架构说明

本文用于解释 Enterprise Agent 的整体架构、主执行链路和关键模块关系。

## 总体架构

```mermaid
flowchart TB
    Client["Client / Frontend / Apifox"]
    Controller["Web Controllers"]
    Executor["V1AgentExecutor"]
    StreamExecutor["StreamingAgentExecutor"]

    Memory["Memory Service\n短期记忆 / 长期记忆 / 用户画像"]
    Guardrail["Guardrails / HITL\n输入检查 / 输出脱敏 / 工具审批"]
    Skill["Skills\n能力注册 / 描述检索 / 工具绑定"]
    Router["Intent Router\nCHAT / RAG / TOOL / CLARIFY"]
    QueryRewrite["Query Rewrite"]
    Rag["RAG Service\npgvector / Hybrid Retrieval / Rerank"]
    Tool["Tool Layer\nLocal Tools / MCP Tools"]
    Prompt["Prompt Assembler"]
    LLM["LLM Service\nSpring AI / DeepSeek"]
    Ops["AgentOps\nTrace / Eval / Workflow / Replay"]

    Pg["PostgreSQL + pgvector"]
    Mcp["MCP Servers\nfilesystem / ticket"]

    Client --> Controller
    Controller --> Executor
    Controller --> StreamExecutor
    Executor --> Memory
    Executor --> Guardrail
    Executor --> Skill
    Executor --> Router
    Executor --> QueryRewrite
    QueryRewrite --> Rag
    Router --> Rag
    Router --> Tool
    Tool --> Mcp
    Rag --> Pg
    Memory --> Pg
    Executor --> Prompt
    Prompt --> LLM
    LLM --> Executor
    Executor --> Ops
    StreamExecutor --> LLM
    StreamExecutor --> Ops
```

## Agent 主执行链路

```mermaid
sequenceDiagram
    participant U as User
    participant A as AgentController
    participant E as V1AgentExecutor
    participant T as TraceRecorder
    participant M as MemoryService
    participant G as GuardrailService
    participant R as IntentRouter
    participant Q as QueryRewriteService
    participant K as RagService
    participant Tool as ToolExecutor
    participant P as PromptAssembler
    participant L as LlmService
    participant Eval as EvalEventRecorder

    U->>A: POST /api/agent/runs
    A->>E: execute(request)
    E->>T: start run
    E->>M: load conversation memory
    E->>G: check input
    E->>R: route intent
    E->>Q: rewrite query
    alt RAG route
        E->>K: retrieve evidence
    else TOOL route
        E->>Tool: execute local or MCP tool
    else CHAT route
        E->>E: fallback to direct chat
    end
    E->>P: assemble prompt
    E->>L: call model
    L-->>E: answer
    E->>G: check output
    E->>M: save assistant message
    E->>Eval: record eval event
    E->>T: finish run
    E-->>A: AgentResponse
    A-->>U: ApiResponse
```

## RAG 链路

```mermaid
flowchart LR
    Docs["Markdown / TXT Documents"]
    Loader["LocalDocumentLoader"]
    Splitter["Chunk Splitter"]
    Embedding["Embedding Client\n智谱 embedding-3"]
    Store["PgVector Repository"]
    Query["User Query"]
    Rewrite["Query Rewrite"]
    VectorSearch["Vector Search"]
    KeywordSearch["Keyword Search"]
    Merge["Hybrid Merge"]
    Rerank["Rerank"]
    Context["Context Blocks"]
    Prompt["Prompt"]

    Docs --> Loader --> Splitter --> Embedding --> Store
    Query --> Rewrite
    Rewrite --> VectorSearch
    Rewrite --> KeywordSearch
    VectorSearch --> Merge
    KeywordSearch --> Merge
    Merge --> Rerank
    Rerank --> Context --> Prompt
    Store --> VectorSearch
    Store --> KeywordSearch
```

## Tool / MCP 链路

```mermaid
flowchart TB
    Planner["ToolCallPlanner\nLLM 规划 + 规则降级"]
    Registry["ToolRegistry"]
    Local["Local Tools\n工单查询 / 创建 / 优先级 / 关闭"]
    McpTools["MCP Tools\nfilesystem / ticket"]
    Guard["Tool Guardrail"]
    Approval["Approval Service"]
    Executor["ToolExecutor"]
    Recorder["ToolRunRecorder"]

    Planner --> Registry
    Registry --> Local
    Registry --> McpTools
    Planner --> Guard
    Guard --> Approval
    Approval --> Executor
    Guard --> Executor
    Executor --> Local
    Executor --> McpTools
    Executor --> Recorder
```

## AgentOps 闭环

```mermaid
flowchart LR
    Run["Agent Run"]
    Trace["Trace / Span"]
    Workflow["Workflow Checkpoint"]
    Eval["Eval Report"]
    GuardAudit["Guardrail Audit"]
    ToolRun["Tool Run Record"]
    Replay["Replay"]

    Run --> Trace
    Run --> Workflow
    Run --> Eval
    Run --> GuardAudit
    Run --> ToolRun
    Trace --> Replay
```

## 当前边界

- Workflow 已记录 checkpoint，但还没有完整恢复执行逻辑。
- Multi-Agent 已有角色协作，但目前是轻量顺序编排。
- Streaming 已支持 LLM token 流式输出，RAG / Tool 阶段是事件化输出。
- 部分 AgentOps 数据仍是内存存储，后续需要 JDBC 持久化。
