# Unified Agent Workbench V1 — M1-D-S0 Tenant Isolation Security Gate Evidence

> 日期：2026-07-20 CST  
> 基线：M0、M1-A、M1-B、M1-C 均为 PASSED  
> 结论：PASSED；M1-D 状态可转为 READY

## 1. 风险与根因

M1-D 审计发现，`JdbcWorkbenchStore.readConversation()` 曾仅按 `conversation_id` 查询，再由 Java 校验 tenant 和 owner。相同 conversationId 被不同租户或 Principal 使用时，数据库可能先返回不属于调用者的行，形成错误 Focus、存在性侧信道和越权数据泄漏风险。

进一步全链路审计还发现：

- `agent_conversation_work_state` 虽有复合主键，却同时存在 `UNIQUE(conversation_id)`，与 tenant/principal 作用域冲突；
- Input、WorkItem、WorkEvent、WorkRelation、WorkLink、CommandDecision、RoutingDecision、RoutePreview 的部分查询依赖“先按裸 ID 读取，再由 Java 判断”；
- RoutingAttempt 未强制绑定当前 WorkItem；DispatchAttempt 未强制绑定当前 WorkItem，存在同一 Principal 下拼接其他任务 ID 的风险；
- Focus 和 abandon 的 CAS 更新需要把权威身份条件放进 SQL。

## 2. 受影响调用链与修复

| 调用链 | 修复方式 |
|---|---|
| `findConversationState` / `switchFocus` / WorkItem 创建时 Focus 锁 | `tenant_id + owner_principal_id + conversation_id` 在 SQL 中共同限定 |
| `findInput` / persisted input 建单 | Input 查询在 SQL 中限定 tenant 和 owner |
| `findWorkItem` / parent relation / event append / abandon | WorkItem 查询和 CAS 更新在 SQL 中限定 tenant 和 owner |
| `loadEvents` / `listRelations` / `listLinks` | 联表 `agent_work_item` 并限定 tenant 和 owner |
| Command 分类完成、失败、列表 | CommandDecision 查询直接限定 tenant 和 owner |
| Routing 完成、失败、列表 | RoutingDecision 联表 WorkItem 限定 tenant 和 owner，并校验 decision 与 WorkItem 绑定 |
| Incident Preview 查询与确认 | Preview 联表 WorkItem 限定 tenant 和 owner；无权与不存在统一为 Not Found |
| DispatchAttempt / WorkLink | Attempt、Link 联表 WorkItem，并同时绑定 workItemId、tenant、owner |

所有面向应用层的 Store 接口继续显式接收 `AuthenticatedPrincipal`。未新增 `readConversation(String conversationId)`、`listWorkItemsByConversation` 或 `listInputsByConversation` 等裸身份接口。

## 3. 关键 SQL 前后对比

修复前：

```sql
SELECT *
FROM agent_conversation_work_state
WHERE conversation_id = ?;
```

修复后：

```sql
SELECT *
FROM agent_conversation_work_state
WHERE conversation_id = ?
  AND tenant_id = ?
  AND owner_principal_id = ?;
```

Focus CAS：

```sql
UPDATE agent_conversation_work_state
SET focused_work_item_id = ?, version = version + 1, updated_at = ?
WHERE conversation_id = ?
  AND tenant_id = ?
  AND owner_principal_id = ?
  AND version = ?;
```

已有数据库兼容迁移：

```sql
ALTER TABLE agent_conversation_work_state
DROP CONSTRAINT IF EXISTS agent_conversation_work_state_conversation_id_key;
```

`conversation_id` 不再全局唯一；权威唯一键是 `(tenant_id, owner_principal_id, conversation_id)`。

## 4. 18 项真实 PostgreSQL 安全场景

`WorkbenchTenantIsolationPostgresIT` 使用真实 PostgreSQL 和真实 JDBC Store，不使用 mock。三个测试方法覆盖以下场景：

| # | 场景 | 证据 |
|---:|---|---|
| 1 | 两个 tenant 使用相同 conversationId | 同一 ID 成功持久化 4 条不同所有权行 |
| 2 | tenant-A/principal-A 只读自己的 Focus | Focus 等于 A 的 WorkItem |
| 3 | tenant-B/principal-B 只读自己的 Focus | Focus 等于 B 的 WorkItem |
| 4 | A 不能读取 B 的 Focus | A 始终返回 A；无本地状态的身份返回 empty |
| 5 | 同 tenant 不同 Principal 相互隔离 | alice/bob 分别返回自己的 Focus |
| 6 | 跨租户查询不返回任意第一行 | unknown 重复查询始终 empty |
| 7 | 多行同 conversationId 不泄漏其他行 | 4 行并存且读取稳定 |
| 8 | Focus CAS 不能更新其他 tenant | 外租户切换到 victim WorkItem 返回 Not Found |
| 9 | Focus CAS 不能更新同 tenant 其他 Principal | 其他 Principal 切换返回 Not Found |
| 10 | 跨租户无法列出 WorkItem | 无 conversation 列表裸接口；显式 WorkItem 查询 empty |
| 11 | 跨租户无法列出 Input | 无 conversation 列表裸接口；显式 Input 查询 empty |
| 12 | 跨租户无法读取 WorkEvent | 返回统一 Not Found |
| 13 | 跨租户无法读取或确认 Preview | 查询、确认均返回统一 Not Found |
| 14 | 显式 workItemId 不能绕过所有权 | WorkItem/Event/Relation/Link/Preview 全部拒绝 |
| 15 | 相同 conversationId 的不同所有者 Focus 稳定 | 多次读取结果不漂移 |
| 16 | 安全拒绝不修改 version | 前后 WorkItem 与 Focus version 快照一致 |
| 17 | 安全拒绝不产生 WorkEvent | 前后事件计数一致 |
| 18 | 安全拒绝不改变 Run/Incident/Plan/WorkLink | active target 字段与 Link 计数前后一致 |

测试还检查 `WorkbenchStore` 不暴露 `listWorkItemsByConversation` 或 `listInputsByConversation` 裸接口。

## 5. 可重复命令与结果

```powershell
$env:WORKBENCH_POSTGRES_IT = "true"
mvn.cmd '-Dtest=*PostgresIT' test
```

结果：44 tests，0 failures，0 errors，11 个既有环境条件跳过；其中 Workbench PostgreSQL 为原有 30/30，加 S0 3/3，共 33/33。

```powershell
$env:WORKBENCH_REAL_MODEL_IT = "true"
mvn.cmd -Dtest=M1BRealModelRoutingIT test
```

结果：3/3，0 failures，0 errors。

```powershell
Remove-Item Env:WORKBENCH_POSTGRES_IT -ErrorAction SilentlyContinue
Remove-Item Env:WORKBENCH_REAL_MODEL_IT -ErrorAction SilentlyContinue
mvn.cmd test
```

结果：156 tests，0 failures，0 errors，11 skipped；跳过项均为既有环境门禁，无新增不可解释跳过。

## 6. 未修改范围

- 未修改 `DefaultAgentRuntime.run()`；
- 未实现 M1-D Controller、页面或查询功能；
- 未修改 ExecutionTarget 集合；
- 未提前实现 M2/M3；
- 未修改、amend 或 rebase M1-C checkpoint；
- 未执行 push。

## 7. 安全结论

Conversation、Focus 和 Workbench 控制面对象的用户访问已在数据库查询边界绑定 tenant 与 Principal。无权记录不会先被读取再由 Java 过滤；对外使用统一 Not Found 语义。真实 PostgreSQL 下的跨租户、同租户跨 Principal、CAS、Preview 和副作用不变性门禁均通过。
