# Unified Agent Workbench V1 — M1-B 缺口矩阵

> 日期：2026-07-19 CST  
> 蓝图：V0.2.3 / FINAL  
> 前置门禁：M1-A PASSED（`efa0bcd`，证据修正 `359f2a5`）

| 蓝图要求 | 当前代码事实 | 缺失内容 | 计划修改 | 测试/故障证据 | 风险 | 设计分支 |
|---|---|---|---|---|---|---|
| 输入先分类，命令不创建 WorkItem | M1-A 只有已确认 NormalGoal 的创建入口 | 原始输入状态、Command 类型和分类审计 | M1-B migration、输入/命令模型与 Store | deterministic/model 分类、命令零 WorkItem、唯一 EFFECTIVE | 把命令误当目标 | 无；严格拆分 Classifier/Router |
| WorkCommand 独立审计 | 无 command decision | deterministic/model attempt、Token/延迟/digest/失败 | `agent_work_command_decision` | PostgreSQL 唯一生效、失败保留、并发测试 | 重试产生两个有效决定 | 无；部分唯一索引 |
| 仅四个 ExecutionTarget | 无 Registry | 固定 Catalog、启用条件和权限过滤 | `workbench.target` | catalog/隐藏目标/General 最小权限测试 | 模型构造隐藏 Target | 无；代码注册清单 |
| 强类型路由输出 | 只有通用字符串 LLM API | ExecutionDecision Schema、严格 JSON parser | `UnifiedTaskRouter` 与模型/解析模型 | parser、真实 DeepSeek 结构化输出 | fallback 被伪装成结果 | 无；fallback 明确失败 |
| Java 路由策略 | 无 Validator | IdentifierSource、ValidatedExecutionInput、RouteDisposition | `RoutePolicyValidator` | MODEL_INFERRED 危险 ID 拒绝、Incident 固定确认 | confidence 放行危险路径 | 无；confidence 仅审计 |
| WorkItem-before-Router | M1-A 已满足 | claim/attempt/Decision 与状态推进 | `JdbcRoutingStore`、`RoutingCoordinator` | WorkItem 外键、原 routingRequestId、状态事件 | 模型调用后落库崩溃 | 无；RESULT_UNKNOWN + 新 attempt |
| 最多一个 EFFECTIVE | 无 routing decision 表 | attempt 状态和部分唯一索引 | M1-B migration/Store | 并发完成、重复扫描、唯一约束 | 双有效决定 | 无；DB 门禁 |
| stale ROUTING 恢复 | 无扫描器 | 单实例 CAS、有界重试、退出 ROUTING | `RoutingRecoveryScanner`、配置 | Router 前/后故障注入、重试耗尽 | 与 Dispatch 恢复混淆 | 无；本阶段绝不 Dispatch |
| Router 可观测性 | 现有 TraceRecorder 会进入 Agent Run 统计 | 独立 Router traceId、模型、Token、延迟、failure | routing/command decision 字段与查询 | usage、digest、failure code、查询测试 | Router 被计为业务 Agent | 无；不写 agent_run_state/TraceRun |
| 真实模型证据 | DeepSeek Provider 与 key 已配置 | Router/Classifier 可用性和结构化输出验证 | real-model IT | 禁用 fallback 后真实调用 | Provider/余额不可用则硬停止 | 无 |

## 允许范围

- M1-B Schema、model、target、classifier、router、validator、routing coordinator/recovery、查询与测试。
- 为原始输入分类和从既有 input 创建 WorkItem，对 M1-A Store 做向后兼容演进。

## 禁止范围

- ExecutionAdapter、DispatchCoordinator、DispatchReconciler、Incident Preview 实现；
- 统一页面、SSE、跨源 Projector、M2/M3；
- 修改 `DefaultAgentRuntime.run()`；
- 本机 HTTP 反调 Controller；
- 新 ExecutionTarget 或外部 Agent 框架。

