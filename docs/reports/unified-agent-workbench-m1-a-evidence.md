# Unified Agent Workbench V1 — M1-A 验收证据

> 日期：2026-07-19 CST  
> 权威蓝图：V0.2.3 / FINAL  
> 里程碑：M1-A  
> 结论：PASSED，停止于 M1-A，未开始 M1-B

## 1. 开工事实审计与缺口矩阵

| 蓝图要求 | 开工时代码事实 | 缺失内容 | 本轮落点 | 验收证据 | 风险与处理 |
|---|---|---|---|---|---|
| 输入先持久化且幂等 | 旧 HTTP 请求直接进入 Run，没有 WorkInput | `agent_work_input` 与客户端幂等键 | `agent_work_input`、`WorkInputService`、`JdbcWorkbenchStore` | 重复及并发重复提交 PostgreSQL IT | 请求摘要不同则 fail-closed，不复用旧结果 |
| 稳定 WorkItem 根 | 只有 Run/Incident 等执行对象 | 产品控制态、执行态、结果三维模型 | `AgentWorkItem` 与三组枚举 | 创建、重启后读取、归属测试 | 不把 Run 状态混入控制状态 |
| Relation / Link | 无统一 WorkItem 关系和执行引用 | 关系与链接表、查询接口 | `agent_work_relation`、`agent_work_link` | 父子关系双向查询、跨 tenant 拒绝 | M1-A 不实现 Recovery Plan 路由或 Dispatch |
| Conversation Focus | 无独立、可 CAS 的任务焦点 | Focus 表和 version CAS | `agent_conversation_work_state`、`ConversationFocusService` | CAS 回滚、跨会话拒绝 | Focus 不等于 running；切换不停止旧任务 |
| 最小 WorkEvent | 旧 Run Event 不能作为统一产品事件 | 正式表、游标、源事件幂等 | `agent_work_event`、`LocalWorkEventAppender` | 并发 16 事件、双唯一约束、分页测试 | 行锁分配，禁止 `MAX(sequence)+1` |
| 单事务创建 | 旧 Store 多为独立操作 | input/work/relation/focus/event 原子提交 | `JdbcWorkbenchStore#createWorkItem` | CAS、跨 tenant、事件注入三类回滚 | 复用项目 raw JDBC 显式事务约定 |
| 可信身份 | 项目尚无 Spring Security Principal | 请求体不能携带 tenant/owner/server IDs | `AuthenticatedPrincipal` 应用边界、request-safe command | 反射边界测试、越权 PostgreSQL IT | M1-A 不新增未认证 HTTP Controller |
| 真实 PostgreSQL | 既有测试按环境变量启用 | Workbench 真实库并发/故障证据 | `JdbcWorkbenchStorePostgresIT` | PostgreSQL 17 上 18/18 通过 | 不以 H2、Mock Store 或 skip 代替 |

## 2. Schema 与持久化对象

迁移文件：`docs/sql/unified-agent-workbench-m1-a.sql`

新增正式表：

1. `agent_work_input`
2. `agent_work_item`
3. `agent_conversation_work_state`
4. `agent_work_relation`
5. `agent_work_link`
6. `agent_work_event`

关键约束：

- `UNIQUE(tenant_id, owner_principal_id, client_input_id)`；
- `UNIQUE(source_input_id)` 与 `UNIQUE(routing_request_id)`；
- Conversation 三元归属主键及单一 `conversation_id`；
- Relation 禁止自引用；
- `UNIQUE(work_item_id, sequence)`；
- `UNIQUE(work_item_id, source_type, source_id, source_event_id)`。

## 3. 核心本地事务

`JdbcWorkbenchStore#createWorkItem` 在同一 JDBC Connection 与 PostgreSQL 事务内完成：

```text
校验可信 Principal 并锁定 Conversation Focus
→ 按 tenant/principal/clientInputId 幂等写入 input
→ 校验 requestDigest，重复请求返回原结果
→ 校验 expectedFocusVersion
→ 校验可选父 WorkItem 的 tenant/principal/conversation
→ 创建 ROUTING WorkItem 和不可替换 routingRequestId
→ 可选写入 WorkRelation
→ version CAS 更新 Focus
→ 锁定 WorkItem 游标分配 sequence=nextEventSequence
→ 写入 WORK_ITEM_CREATED
→ 一次提交
```

任一步抛错都会 rollback。故障注入点覆盖 input、WorkItem、Relation、Focus、Event 前后与 commit 前。

## 4. 幂等、Focus 与事件协议

- 输入幂等键包含 tenant 和 Principal；同键同摘要返回原 input、WorkItem、routingRequestId 和首事件。
- 同键不同 payload/conversation 触发 `WorkbenchIdempotencyConflictException`。
- Conversation 首次并发创建使用 `INSERT ... ON CONFLICT DO NOTHING` 后 `SELECT ... FOR UPDATE`，并统一做所有权校验。
- Focus 更新使用 expected version CAS；目标必须属于相同 tenant、Principal 与 Conversation。
- 本地事件固定 `sourceType=WORK_ITEM`、`sourceId=workItemId`。
- sequence 在 WorkItem 行锁内读取 `next_event_sequence`，写事件后推进游标；没有使用 `SELECT MAX(sequence)+1`。
- 相同 source event 重放返回原事件，不重复推进游标。

## 5. 可重复测试命令

前置条件：PostgreSQL 监听 `localhost:5432`，数据库 `enterprise_agent` 可用。默认账号与本地开发环境一致；也可显式设置以下变量。

```powershell
$env:AGENT_STORAGE_POSTGRES_URL = 'jdbc:postgresql://localhost:5432/enterprise_agent'
$env:AGENT_STORAGE_POSTGRES_USERNAME = 'postgres'
$env:AGENT_STORAGE_POSTGRES_PASSWORD = '1234'
$env:WORKBENCH_POSTGRES_IT = 'true'

mvn.cmd '-Dtest=WorkbenchModelAndServiceTests,JdbcWorkbenchStorePostgresIT' test
```

结果：

```text
WorkbenchModelAndServiceTests: 3 passed
JdbcWorkbenchStorePostgresIT: 19 passed
Total: 22 passed, 0 failed, 0 errors, 0 skipped
```

全量回归：

```powershell
Remove-Item Env:WORKBENCH_POSTGRES_IT -ErrorAction SilentlyContinue
mvn.cmd test
```

结果：

```text
Tests run: 146, Failures: 0, Errors: 0, Skipped: 11
BUILD SUCCESS
```

11 条 skipped 均为项目原有的环境条件测试；M1-A PostgreSQL IT 已在上一条命令中强制启用并 19/19 通过。

## 6. 并发与故障注入证据

- 两个线程并发提交相同 clientInputId：数据库最终仅 1 条 input 和 1 条 WorkItem，两端拿到同一结果。
- 16 个线程向同一 WorkItem 追加事件：sequence 为连续 `0..16`，无重复、无缺口。
- Focus CAS 冲突：新 input、WorkItem、Relation、Event 全部不存在。
- 跨 tenant Relation：子 input 与子 WorkItem 不落库。
- `BEFORE_EVENT_APPEND` 注入异常：Conversation Focus、input、WorkItem、Event 全部回滚。
- stale abandon CAS：控制状态、version 与事件流均保持原值。
- WorkLink 在服务端尚未绑定权威 dispatchRequestId 时 fail-closed，拒绝客户端伪造执行引用。
- 新建 Store 实例模拟进程重启：仍能从 PostgreSQL 读取 WorkItem 与事件。

## 7. 范围审计

- `DefaultAgentRuntime.run()`：无修改。
- 未新增 `UnifiedTaskRouter`、`WorkCommandClassifier`、`RoutingCoordinator`、`RoutingRecoveryScanner`、`RoutePolicyValidator`、`ExecutionTargetRegistry`、`ExecutionAdapter`、Dispatch Coordinator/Reconciler。
- 未实现 Incident Preview、统一 SSE、跨源 Projector、Vue 页面或 M2/M3 能力。
- 未引入 OpenAI Agents SDK、LangGraph、Deep Agents 等依赖。
- 未执行 Git commit 或 push。

## 8. M1-A DoD

| 条件 | 结论 |
|---|---|
| 输入幂等落库 | PASS |
| WorkItem 创建 | PASS |
| Relation 可选写入 | PASS |
| Focus CAS | PASS |
| routingRequestId 固化且唯一 | PASS |
| WORK_ITEM_CREATED 同事务落库 | PASS |
| WorkEvent sequence 并发安全 | PASS |
| 事务失败无孤立数据 | PASS |
| 权限边界测试 | PASS |
| 真实 PostgreSQL 集成测试 | PASS |
| 现有回归测试 | PASS |

M1-A 已满足 Definition of Done。按照里程碑门禁在此停止，等待人工验收后再决定是否进入 M1-B。
