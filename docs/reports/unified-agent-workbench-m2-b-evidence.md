# Unified Agent Workbench V1 - M2-B 统一 SSE / Replay Evidence

> 日期：2026-07-20 CST
> 蓝图：V0.2.3 / FINAL
> 前置门禁：M2-A PASSED（`30c7b9f`）

## 1. 交付结论

M2-B 已建立 WorkItem 级统一 SSE，并以两个独立、单调的 cursor 传输持久化 WorkEvent 与 PRIMARY RUN 的 `MODEL_DELTA`：

```text
workSequence       -> agent_work_event 产品投影顺序
primaryRunSequence -> PRIMARY RUN timeline 顺序
Last-Event-ID      -> w:<workSequence>;r:<primaryRunSequence>
```

SSE 只读取已有 WorkItem、WorkLink、WorkEvent 和 Runtime timeline，不会 dispatch、resume、创建 Run 或改变底层执行。结构化状态继续以 `agent_work_event` 为准；模型增量不复制到 WorkEvent。

## 2. 协议与边界

- `GET /api/agent/work-items/{workItemId}/events/stream` 同时接受 query cursor 与 `Last-Event-ID`，每一维取单调最大值。
- 连接建立后先从数据库 replay，再以有界批量串行轮询；空闲时发送 heartbeat。
- 服务端发现 WorkEvent sequence 不连续时发送 `gap`，不推进 work cursor。
- SSE event id 与 payload 的 `resumeToken` 使用同一个复合 cursor。
- 只解析 `PRIMARY + RUN` WorkLink，并要求其与 WorkItem 的 activeRunId 不冲突。
- Incident child Run 和 Recovery Planner child Run 不进入主回答增量通道。
- principal-scoped Store 查询用于每次 poll；跨租户访问按 not-found 处理，不暴露目标数据。

## 3. 前端恢复协议

统一工作台使用 EventSource 消费四类事件：

- `work-event`：按 eventId 去重并连续追加统一时间线；
- `model-delta`：按 Runtime eventId 去重，只追加主回答正文；
- `heartbeat`：仅推进已确认的复合 cursor；
- `gap`：立即关闭当前流，从 JSON 历史接口分页重建时间线，验证连续性后再以新 cursor 重连。

断线重连 URL 始终携带当前 `workSequence` 与 `primaryRunSequence`。重连过程不调用输入、派发、继续或目标创建接口。原 1.5 秒整页轮询降为 5 秒状态刷新，时间线和主回答由 SSE 驱动。

## 4. Schema 与事务

本里程碑没有新增 migration，也没有修改：

- `agent_work_event` Schema；
- `agent_work_item.next_event_sequence`；
- M1 本地事件和 M2-A 跨源投影语义；
- Runtime timeline 的权威持久化语义。

SSE 是只读产品投影，不是新的权威 Store。

## 5. 自动化证据

### 5.1 SSE/Controller 专项

```powershell
mvn.cmd '-Dtest=UnifiedWorkEventStreamServiceTests,UnifiedWorkControllerTests' test
```

结果：9 tests，0 failures，0 errors，0 skipped。覆盖：

- query cursor 与 Last-Event-ID 单调合并；
- SSE id 使用复合 resume token；
- WorkEvent replay；
- 非 delta Runtime event 只推进 cursor；
- PRIMARY/CHILD Run 隔离；
- 显式 gap 且 cursor 不跳跃；
- Controller cursor 投影。

### 5.2 真实 PostgreSQL SSE 故障场景

```powershell
$env:WORKBENCH_POSTGRES_IT = 'true'
$env:AGENT_STORAGE_POSTGRES_URL = 'jdbc:postgresql://127.0.0.1:5432/enterprise_agent'
$env:AGENT_STORAGE_POSTGRES_USERNAME = 'postgres'
$env:AGENT_STORAGE_POSTGRES_PASSWORD = '1234'
mvn.cmd '-Dtest=UnifiedWorkEventStreamPostgresIT' test
```

结果：3 tests，0 failures，0 errors，0 skipped。证明：

- 首次读取后断线，断线期间写入的 sequence 1/2 在新服务实例中从 cursor 0 完整补齐；
- 已推进 cursor 再 poll 返回 0 条，数据库仍只有 3 条权威 WorkEvent；
- 其他租户即使持有 workItemId，也无法进入订阅读取路径；
- 真实 PostgreSQL Runtime timeline 同时写入 PRIMARY/CHILD delta，正文只得到 `primary answer`，child 混入数为 0。

### 5.3 PostgreSQL 全门禁

```powershell
$env:WORKBENCH_POSTGRES_IT = 'true'
$env:INCIDENT_POSTGRES_IT = 'true'
mvn.cmd '-Dtest=*PostgresIT' test
```

结果：55 tests，0 failures，0 errors，0 skipped。

### 5.4 全量与前端

```powershell
mvn.cmd clean test
cd frontend
npm.cmd run build
```

结果：后端 174 tests，0 failures，0 errors，11 skipped；11 条均为仓库既有、由真实 FlowOrder/RabbitMQ/模型等显式环境变量控制的外部 E2E，不是 M2-B 新增 skip。前端 `vue-tsc -b` 与 Vite production build 通过。

本地后端 `18083` 与前端 `15173` 健康检查均返回 HTTP 200。应用内浏览器自动化因本机浏览器运行时资源路径错误未能初始化，因此没有伪造视觉截图；此限制不影响上述协议、PostgreSQL 和 production build 证据。

## 6. 故障注入结果

| 故障 | 期望 | 结果 |
|---|---|---|
| 客户端断线期间新增事件 | 从旧 cursor 全量补齐 | PASSED |
| 相同 cursor 重放 | eventId 稳定，活动 cursor 不重复追加 | PASSED |
| WorkEvent sequence gap | 显式 GAP，不推进 cursor | PASSED |
| child Run 存在 MODEL_DELTA | 主回答混入数为 0 | PASSED |
| 跨租户订阅 | 不可见，按 not-found 处理 | PASSED |
| SSE 读取异常 | 通用 sync-error，不改变 WorkItem | PASSED |

## 7. 范围审计

- `DefaultAgentRuntime.run()`：未修改；
- `MODEL_DELTA` 写入 WorkEvent：未实现；
- M2-C Multi-Agent 执行树：未实现；
- M3 命令、预算、claim/lease：未提前实现；
- migration：无；
- push：未执行；
- `git diff --check`：通过。

## 8. 未完成项与风险

- Multi-Agent 角色、Attempt、Evidence、Conflict 和 Assessment 的聊天内执行树属于 M2-C。
- 完整历史页面回放和服务重启后的前端回归属于 M2-D。
- 当前 SSE 使用数据库有界轮询；高并发连接容量与多实例推送优化不在 M2-B 冻结范围。
- 浏览器视觉验收需待 Codex 应用内浏览器运行时恢复后补做，但不是 M2-B 硬门禁。

最终判定：`M2-B PASSED`。Checkpoint 为本报告所在的本地提交。
