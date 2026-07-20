# Unified Agent Workbench V1 - M2-D 历史回放与前端回归 Evidence

> 日期：2026-07-20 CST
> 蓝图：V0.2.3 / FINAL
> 前置门禁：M2-C PASSED（`77f7a50`）

## 1. 交付结论

M2-D 已证明历史 WorkItem 页面不依赖旧 JVM 内存：新建 JDBC Store、Runtime Timeline、SSE Service 和 Execution Tree Service 后，可以从 PostgreSQL 重建 WorkEvent、主回答和执行树，且不会创建第二个 WorkItem、Run 或业务副作用。

前端修复了 Conversation 切换时旧 selectedId/detail/tree/SSE 残留问题，并用 refresh generation 阻止旧会话的慢响应覆盖新会话。

## 2. 历史重建协议

### 2.1 WorkEvent

Fixture 持久化 sequence `0..520` 共 521 条 WorkEvent：

- Detail/首个历史页读取 `0..499` 共 500 条；
- cursor 499 后读取 `500..520` 共 21 条；
- sequence 连续且 eventId 唯一；
- 不重置 `next_event_sequence`。

### 2.2 主回答

PRIMARY RUN timeline 持久化 25 条 `MODEL_DELTA`。两个全新 `UnifiedWorkEventStreamService` 实例都从 `primaryRunSequence=-1` 重放：

- 25 条 delta；
- eventId 顺序完全相同；
- 拼接正文完全相同；
- child Run 不参与；
- 不调用 dispatch/resume/create Run。

### 2.3 执行树

新建 Runtime Store、Timeline Store、RuntimeTraceProjector 和 UnifiedWorkExecutionTreeService 后，仍能得到：

- `treeType=SINGLE_AGENT`；
- 原 runId；
- 非空 Trace；
- 数据库中仍只有 1 个 WorkItem 和 1 个 Run。

Multi-Agent 树的权威重建已由 M2-C PostgreSQL Fixture 覆盖，两者组合形成页面刷新/服务重启证据。

## 3. SSE 背压缺陷与修复

历史 Fixture 首次运行暴露真实生产缺陷：

```text
Flux.interval
-> 数据库 poll 比 interval 慢
-> tick 无法消费
-> OverflowException
-> SSE 返回 SYNC_ERROR
```

原实现虽使用 `concatMap` 串行 poll，但 `Flux.interval` 仍会继续产生 tick。修复为：

```text
defer(poll)
-> poll 完成
-> delay pollInterval
-> repeat
```

因此下一轮只会在上一轮完成后调度，不存在 interval tick 积压。修复后 M2-B SSE 单元与 M2-D 521 事件组合回放同时通过。

## 4. Conversation 与前端状态隔离

新增前端协议：

1. Conversation ID 变化时立即关闭旧 EventSource；
2. 清空 selectedId、detail、executionTree、timeline、answer、seen IDs 和两个 cursor；
3. 持久化新 Conversation ID；
4. 立即加载新会话；
5. 每次 refresh 分配 generation；
6. conversation/workItem/generation 任一不匹配时丢弃旧响应；
7. 空会话保持真实空状态，不展示旧任务。

这只影响产品查询状态，不暂停、取消或放弃后台任务。

## 5. 自动化证据

### 5.1 历史/重启 PostgreSQL

```powershell
$env:WORKBENCH_POSTGRES_IT = 'true'
$env:AGENT_STORAGE_POSTGRES_URL = 'jdbc:postgresql://127.0.0.1:5432/enterprise_agent'
$env:AGENT_STORAGE_POSTGRES_USERNAME = 'postgres'
$env:AGENT_STORAGE_POSTGRES_PASSWORD = '1234'
mvn.cmd '-Dtest=UnifiedWorkHistoryReplayPostgresIT,UnifiedWorkEventStreamServiceTests' test
```

结果：6 tests，0 failures，0 errors，0 skipped。

### 5.2 PostgreSQL 全门禁

```powershell
$env:WORKBENCH_POSTGRES_IT = 'true'
$env:INCIDENT_POSTGRES_IT = 'true'
mvn.cmd '-Dtest=*PostgresIT' test
```

结果：57 tests，0 failures，0 errors，0 skipped。

### 5.3 全量后端

```powershell
mvn.cmd clean test
```

结果：180 tests，0 failures，0 errors，11 skipped。11 条均为仓库既有、需显式 FlowOrder/RabbitMQ/真实模型环境的 E2E；M2-D 新增 PostgreSQL 测试在全量默认环境中由既有门禁跳过，并已在 57 条 PostgreSQL 命令中真实执行。

### 5.4 前端构建与旧路由 smoke

```powershell
cd frontend
npm.cmd run build
npm.cmd run preview -- --host 127.0.0.1 --port 14173
```

`vue-tsc -b` 和 Vite production build 通过。以下 9 个路由的源声明、View 文件和 preview HTTP 入口均验证为 200：

```text
/workbench
/
/runs
/approvals
/incident-command
/capabilities
/knowledge
/observability
/api-lab
```

应用内浏览器自动化运行时仍无法初始化，因此该证据是构建、路由完整性和 HTTP smoke，不宣称视觉截图或点击回归。

## 6. 故障注入与不变量

| 场景 | 结果 |
|---|---|
| 500+ WorkEvent 分页 | 500 + 21 完整补齐 |
| 服务实例重建 | WorkEvent/正文/树均恢复 |
| 同 cursor 第二次重放 | eventId 与正文一致 |
| 慢数据库 poll | 不再触发 interval OverflowException |
| Conversation 快速切换 | 旧 generation 响应被丢弃 |
| 空会话 | 旧详情和 SSE 清空 |
| 重建后的对象数 | 1 WorkItem、1 Run、521 WorkEvent |
| 重复业务副作用 | 0 |

## 7. 范围审计

- `DefaultAgentRuntime.run()`：未修改；
- Incident/Recovery Plan 状态机：未修改；
- WorkEvent Schema/sequence：未修改；
- migration：无；
- M3 WorkCommand/预算/claim/lease：未提前实现；
- push：未执行；
- `git diff --check`：通过。

## 8. 未完成项

- 应用内浏览器环境恢复后可补响应式视觉与点击回归截图；当前没有伪造此类证据。
- 多实例 WorkCommand、分层预算和故障接管分别属于 M3-A/M3-B/M3-C。

最终判定：`M2-D PASSED`。Checkpoint 为本报告所在的本地提交。
