# 仍然存在的边界

> 当前实现基线：`b6207a4`，核对日期：2026-08-10。

这份清单约束简历和面试表述，不是要求继续无限扩展功能。

## 1. 生产身份与授权

- Workbench 已从服务端 Principal 获取 tenant/principal/roles，并在 Store 层做租户与所有权隔离；
- 本地 `LocalWorkbenchPrincipalProvider` 仍是开发演示身份，不是 Spring Security/OAuth2；
- 管理 Controller、Approval、Incident 运维和 API Lab 仍需可信网关或正式认证授权；
- 内部 Scope Discovery token 是固定服务间共享秘密，不是完整的 mTLS、轮换、审计和最小权限方案。

因此不能宣称“已完成生产级认证、租户治理和零信任服务认证”。

## 2. 执行隔离

- Capability/Profile/Host/路径白名单是应用层限制；
- 当前没有 OS/容器级 Sandbox、seccomp、低权限容器和正式出站网络隔离；
- 项目没有向 General Agent 暴露任意 SQL、任意 URL 或恢复管理写工具，但这不等于未来新增 Shell/File/Network Tool 后自动安全。

## 3. 数据库迁移与运维

- 当前使用 `CREATE TABLE IF NOT EXISTS` 和启动初始化器方便学习与测试；
- 正式部署缺 Flyway/Liquibase 版本、回滚、旧 JSON 数据迁移、蓝绿兼容和 schema owner 治理；
- 缺少正式备份恢复演练、容量规划、数据保留/删除策略和密钥轮换。

## 4. 模型协议与 Guardrail

- 默认已经是 Provider 原生 `tools/tool_calls`，旧“只有 JSON ToolCall”结论已过期；
- 当前原生网关围绕 Spring AI 2.0 / DeepSeek 实现，不等于已经拥有 OpenAI、Anthropic、Gemini 等多 Provider 完整适配矩阵；
- `JsonAgentModelGateway` 仍是兼容模式，需要继续防止 malformed ToolCall 和协议 Envelope 泄漏；
- 流式输出使用 holdback 和滚动 Guardrail，但任何有限窗口策略都应配合上游数据最小化，不能承诺绝对阻止所有跨 chunk 敏感组合；
- Prompt Injection 检测仍依赖确定性信号与模型语义判断，没有独立安全模型或外部内容安全服务。

## 5. Agent 与 Workflow 边界

- 单 Agent Model–Tool Loop、受控 SubAgent Tool 和 Incident 编排已经存在；
- 严格副作用链仍应由 Java 状态机控制，不应让模型自由决定 `preview → approval → execute → reconcile` 顺序；
- 当前不是通用 BPMN、任意 DAG、通用 Agent Mailbox 或跨组织 Agent 通信平台；
- Runtime 的并行 SubAgent batch 使用单进程有界线程池；Incident Task/Recovery Item 虽有 PostgreSQL lease/fencing，但不等于所有 Agent 调度都已跨节点队列化。

## 6. Incident Scope Discovery

- 自动时间表达是有限白名单：`前天`、`昨晚`、`今天`、1～24 小时相对范围、ISO start/end；
- `昨天`、`本周`、`上个月`、节假日、模糊班次等当前不应宣称自动解析；
- 自动范围最大 24 小时、候选最大 100，超出应澄清或缩小范围；
- 只覆盖当前明确建模的异常类型，不是任意 FlowOrder 事故搜索；
- queueName 只有从持久化死信/权威映射解析时才可信，不能把 RabbitMQ 队列总数与事故 requestId 数直接等值比较；
- 人工浏览器视觉验收曾受本地浏览器运行时故障阻塞，不能把 build/smoke 描述成完整人工 UX 证据。

## 7. Multi-Agent 与 Reviewer

- Commander/Specialist/Reviewer 已使用受控 Tool Calling 和结构化 Evidence；
- Reviewer 仍是概率模型，Java 校验 Schema、引用和覆盖，但不能证明根因一定正确；
- 当前领域角色和 ComparisonRule 是固定业务设计，不是通用自治团队；
- 外部告警平台、排班、升级通知和跨系统 Incident 生命周期尚未接入；
- Recovery Plan 逐项执行，不提供自动批量恢复写接口。

## 8. RAG 与 Memory

- 文档加载以 UTF-8 文本为主，未提供 PDF/DOCX/PPTX OCR、复杂表格和版面理解；
- rerank 使用通用 ChatModel，不是专用 cross-encoder；
- PostgreSQL 缓存有 TTL/容量裁剪，但没有 Redis 高吞吐与主动广播失效；
- 长期记忆是有损结构化提取，仍需要隐私策略、删除权、保留期和质量 Eval。

## 9. 测试与证据边界

- `b6207a4` 提交记录的默认 Maven 回归为 352 tests、0 failures、0 errors、11 skipped；这是该 checkpoint 的证据，不代表当前未提交工作区已重新通过；
- 外部 PostgreSQL/MySQL/RabbitMQ/FlowOrder/真实模型测试中有 opt-in 套件，默认跳过不能算本次执行；
- 已有真实故障 E2E、路由 Eval、Incident Phase 3 和 Scope Discovery 证据，但仍缺长时间 soak、网络分区、容量上限、P95/P99、灾备和正式安全渗透测试；
- 真实模型 Eval 通过不意味着模型确定性，危险路径必须继续 Java fail-closed。

## 10. 可以使用与禁止使用的表述

可以说：

> 实现了持久化 Agent Runtime、受控 Tool Calling、HITL、幂等与故障对账，并通过 Unified Workbench 编排 General、OrderCare 和事故 Multi-Agent 场景。

不要说：

- “生产级通用 Agent 平台”；
- “完全复刻 Codex/Claude Code”；
- “支持任意自然语言时间和任意事故”；
- “多 Agent 可以自由通信并自动处理所有故障”；
- “HTTP 超时后保证没有产生副作用”；
- “测试覆盖了所有多实例、容量和安全场景”。
