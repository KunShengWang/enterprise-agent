# Enterprise Agent

轻量级自研 Agent 项目，定位是：

```text
企业知识库 + 智能工单 Agent 平台
```

这个项目不是简单 demo，也不照搬 `super-agent` 的复杂工程。目标是用 Spring Boot + Spring AI 提供底层能力，同时手写可控、可观测、可评估的 Agent 编排主链路。

## 核心原则

```text
框架负责能力，项目负责编排。
```

Spring AI 负责：

```text
ChatClient / ChatModel
流式输出
Tool Calling 基础能力
VectorStore
MCP 集成能力
```

项目自己负责：

```text
AgentExecutor
IntentRouter
SkillSelector
PromptAssembler
ToolRegistry
ToolExecutor
GuardrailService
ApprovalService
TraceRecorder
EvalRunner
```

## 主执行链路

```text
用户问题
  -> Trace 开始
  -> 加载 Memory
  -> 输入 Guardrail
  -> 意图识别 / 路由
  -> Skill 选择
  -> Query Rewrite
  -> RAG / Tool / MCP / Clarify
  -> Prompt 组装
  -> LLM 调用
  -> SSE 流式输出
  -> 输出 Guardrail
  -> 保存会话
  -> Trace / Eval / AgentOps
```

## 当前阶段

当前处于 V0：项目骨架。

已包含：

```text
Spring Boot 项目
基础 WebFlux 入口
统一响应结构
全局异常处理
Agent 核心接口
Trace 基础模型
Guardrail / Tool / RAG / Memory / Skill / MCP / Eval 扩展点
```

## 路线文档

[自研 Agent 项目执行路线](docs/self-agent-roadmap.md)

## 本地启动

```powershell
mvn spring-boot:run
```

健康检查：

```text
GET http://localhost:8080/api/agent/health
```

V0 骨架调用：

```text
POST http://localhost:8080/api/agent/runs
Content-Type: application/json

{
  "conversationId": "demo-conversation",
  "userId": "u1001",
  "question": "查询工单 T1001 的状态"
}
```
