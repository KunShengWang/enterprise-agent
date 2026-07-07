# 自研 Agent 项目执行路线

## 项目定位

自研项目定位为：

```text
企业知识库 + 智能工单 Agent 平台
```

它不是一个简单 demo，也不是照搬 `super-agent` 的复杂工程。

目标是：

```text
有完整 Agent 闭环
有真实但不过度复杂的业务场景
核心链路可控、可观测、可评估
能覆盖大厂 Agent JD 高频能力
能在面试中清楚讲出执行流程、权衡和验证证据
```

复杂度边界：

```text
保留：
- 知识库问答
- 工单查询
- 工单创建
- 故障排查
- 高风险操作人工确认
- 权限控制
- Trace / Eval / AgentOps

暂不做重：
- 复杂后台管理系统
- 完整多租户
- 复杂前端
- 大规模文档治理
- 复杂权限组织架构
- 过早引入太多中间件
```

## 项目路径

项目目录暂定：

```text
D:\JDK\IDEA\java_reinforcement_learning\enterprise-agent
```

`agent_learning` 继续作为学习 demo 仓库。

`super-agent` 继续作为参考项目，不作为主开发项目。

## 核心原则

### 1. 框架负责能力，项目负责编排

使用 Spring AI，但不把核心 Agent 流程完全交给黑盒 Advisor 或框架 Agent。

Spring AI 负责：

```text
ChatClient / ChatModel
流式输出
Tool Calling 基础能力
VectorStore
ChatMemory 可参考或部分使用
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

### 2. 主链路必须显式

核心执行流程必须能在代码里看清楚：

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

### 3. 每个版本都服务同一个项目主干

版本不是孤立 demo，而是同一个项目持续演进：

```text
V0  搭骨架
V1  跑通单 Agent 主链路
V1.5 接入 MCP + Skills
V2  增强 RAG 和 AgentOps
V3  扩展 Multi-Agent
V4  工程化和简历包装
```

后续版本不能推翻前面版本，而是挂到同一条主链路上。

## 总体版本路线

### V0：项目骨架

目标：

```text
搭建一个干净、可扩展、不过度复杂的 Spring Boot + Spring AI 项目。
```

交付：

```text
Spring Boot 项目
统一响应结构
全局异常处理
基础配置
目录结构
README
核心接口定义
```

建议模块：

```text
com.agent.platform.agent
com.agent.platform.llm
com.agent.platform.memory
com.agent.platform.rag
com.agent.platform.tool
com.agent.platform.guardrail
com.agent.platform.approval
com.agent.platform.trace
com.agent.platform.eval
com.agent.platform.mcp
com.agent.platform.skill
com.agent.platform.web
```

V0 不追求功能多，重点是把扩展点留好。

### V1：单 Agent 核心闭环

目标：

```text
跑通一个完整的单 Agent 执行链路。
```

必须包含：

```text
用户问题入口
短期 Memory
输入 Guardrail
IntentRouter
QueryRewrite
RAG 问答
本地工单工具调用
PromptAssembler
LLM 流式输出
输出 Guardrail
TraceRecorder
基础 Eval
```

业务场景：

```text
1. 用户问知识库问题，Agent 走 RAG。
2. 用户查询工单状态，Agent 调用工单查询工具。
3. 用户创建工单，Agent 调用工单创建工具。
4. 用户问题不清楚，Agent 发起澄清。
5. 用户请求高风险操作，Agent 进入人工确认。
```

核心链路：

```text
ChatController
  -> AgentExecutor
  -> TraceRecorder.start()
  -> MemoryService.load()
  -> InputGuardrail.check()
  -> IntentRouter.route()
  -> QueryRewriteService.rewrite()
  -> RagService.retrieve() / ToolExecutor.execute() / ClarificationService.ask()
  -> PromptAssembler.assemble()
  -> LlmService.stream()
  -> OutputGuardrail.check()
  -> ConversationService.save()
  -> EvalEventRecorder.record()
```

### V1.5：MCP + Skills

目标：

```text
把工具能力从本地 Java 方法扩展到可插拔工具生态。
```

MCP 交付：

```text
最小 MCP Server
MCP Client
通过 MCP 暴露工单查询工具
通过 MCP 暴露工单创建工具
统一 ToolRegistry 接入本地工具和 MCP 工具
```

Skills 交付：

```text
Skill 描述模型
Skill 注册表
SkillSelector
工单处理 Skill
知识库问答 Skill
故障排查 Skill
```

Skill 内容：

```text
skillName
description
适用场景
prompt 模板
可用工具列表
输入输出 Schema
few-shot 示例
riskLevel
```

设计要求：

```text
AgentExecutor 不直接关心工具来自本地还是 MCP。
AgentExecutor 只通过 ToolRegistry / ToolExecutor 统一调用。
```

### V2：RAG 增强和 AgentOps

目标：

```text
让 Agent 不只是能回答，还能被评估、观测和优化。
```

RAG 增强：

```text
文档解析
切分策略
Embedding
向量检索
关键词检索
Hybrid Retrieval
Rerank
引用溯源
检索失败兜底
```

AgentOps：

```text
评测集
工具调用成功率
RAG 命中率
幻觉率
平均响应时间
Token 成本
TraceId
Prompt 日志
检索日志
工具调用日志
失败原因分类
执行回放
```

关键指标：

```text
answer_accuracy
rag_hit_rate
tool_success_rate
hallucination_rate
avg_latency_ms
prompt_tokens
completion_tokens
total_cost
guardrail_block_count
approval_pass_rate
```

### V3：Multi-Agent

目标：

```text
把单 Agent 能力拆成可控的角色化协作，而不是做多个 Agent 随便聊天。
```

Agent 角色：

```text
OrchestratorAgent   总控，维护任务状态和执行顺序
PlannerAgent        任务拆解和计划生成
RetrieverAgent      RAG 检索和证据整理
ToolAgent           工具选择和执行
ReviewerAgent       检查回答、风险和证据一致性
```

核心能力：

```text
任务分工
Agent 通信协议
共享状态
并行调度
结果聚合
Reviewer 复核
失败兜底
Trace 统一串联
```

设计要求：

```text
Multi-Agent 复用 V1/V2 的 Memory、Tool、RAG、Guardrail、Trace、Eval。
不能另起一套孤立实现。
```

### V4：工程化和简历包装

目标：

```text
把项目从“能跑”打磨成“能讲、能测、能展示”。
```

工程化：

```text
工具调用 requestId 幂等
超时控制
重试
降级
权限控制
操作审计
统一错误码
关键接口压测
核心链路日志
配置化开关
```

简历证据：

```text
README 架构图
核心执行流程图
Trace 示例
Eval 报告
RAG 命中率数据
工具调用成功率数据
Token 成本统计
Guardrail 拦截案例
MCP 工具调用案例
Multi-Agent 协作案例
```

## 第一版最小可用目标

第一版不要贪多，先完成：

```text
Spring Boot 项目启动
一个 ChatController
一个 AgentExecutor
一个模拟 Memory
一个输入 Guardrail
一个 IntentRouter
一个本地知识库 RAG
一个工单查询工具
一个工单创建工具
一个 PromptAssembler
一个 LlmService
一个 TraceRecorder
一个 EvalRunner
```

能演示这 3 条链路即可：

```text
知识库问答
工单查询
高风险动作人工确认
```

## 面试主叙事

项目描述：

```text
基于 Spring Boot + Spring AI 自研企业知识库与智能工单 Agent 平台，
支持 RAG 问答、Tool Calling、Memory、MCP 工具接入、Skills 能力选择、
Guardrails/HITL、Trace/Eval/AgentOps 和 Multi-Agent 协作。
项目没有完全依赖框架黑盒 Agent，而是自研 Agent 编排链路，
实现意图路由、查询改写、检索增强、工具执行、人工确认、流式输出和自动评测，
提升 Agent 在企业问答与工单处理场景下的可控性和可解释性。
```

面试重点：

```text
1. Agent 不是一次 LLM 调用，而是一条可控执行链路。
2. RAG 不是简单向量检索，还要关注召回、引用、评估和兜底。
3. Tool Calling 不是模型直接执行工具，而是模型提出调用意图，程序做权限、参数、幂等、审计和执行。
4. Guardrails 不能只靠 Prompt，必须在输入、工具、输出阶段做程序级控制。
5. AgentOps 的价值是用数据解释 Agent 为什么成功或失败。
6. Multi-Agent 的重点不是数量，而是角色分工、共享状态和结果聚合。
```

## 当前执行顺序

```text
1. 创建 enterprise-agent 项目骨架。已完成。
2. 写 README 和基础包结构。已完成。
3. 实现 V1 主链路的核心接口。已完成。
4. 使用轻量 RAG / 本地工具先跑通流程。已完成。
5. 增加 Spring AI LLM 适配层。已完成。
6. 默认切换为 DeepSeek 真实模型。已完成。
7. V1.3 地基加固：模型异常、耗时记录、路由预览和 WebFlux 线程切换。已完成。
8. V2.0 PostgreSQL + pgvector RAG：文档加载、切分、Embedding、入库和 TopK 检索。已完成。
9. V2.1 RAG 可观测优化：检索参数、命中 chunk、相似度、来源、耗时和知识库统计。已完成。
10. V2.2 RAG 入库幂等优化：source 级删除重建、事务化保存、入库分段耗时和按 source 删除接口。已完成。
11. V2.3 Hybrid Retrieval：向量检索 + 关键词检索 + 融合排序，并记录 vectorScore / keywordScore / finalScore。已完成。
12. V2.4 RAG Eval：默认/自定义评估集、sourceHitRate、keywordHitRate、averageScore 和逐用例报告。已完成。
13. V2.5 RAG Rerank：召回候选后二次排序，并记录 originalScore、rerankScore、queryCoverage 和 sourceMatch。已完成。
14. V2.6 RAG AgentOps：RAG 运行记录、近 N 次命中率、平均耗时、平均召回数量和按模式统计。已完成。
15. V2.7 pgvector 性能入口：支持手动创建 HNSW / IVFFlat 向量索引。已完成。
16. V2.8 RAG 运行报告持久化：将近 N 次 RAG run 导出为 Markdown 报告。已完成。
17. V3.2 Memory 增强：短期窗口、摘要压缩、长期记忆、用户画像、历史召回、JDBC 持久化和 Memory 查询接口。已完成。
18. V4.0 AgentOps / Trace：完整 Run、统一 Span、耗时统计、Token/成本估算、失败原因、回放能力和 Trace 查询接口。已完成。
19. V4.1 Agent Eval：评测集管理、Agent 回答评估、工具调用评估、RAG 使用评估、groundedness、LLM-as-Judge 和回归测试接口。已完成。
20. 后续增强更完整的性能压测报告。
```
